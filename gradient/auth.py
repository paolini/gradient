"""Login OAuth con profilo letto dal provider, sessioni revocabili locali e token API personali."""
import logging
import secrets
from datetime import datetime, timedelta, timezone

import httpx
from authlib.integrations.httpx_client import AsyncOAuth2Client
from authlib.integrations.base_client.errors import OAuthError
from authlib.oauth2 import OAuth2Error
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy import delete, or_, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError

from gradient.database import database_error_details
from gradient.services import API_TOKEN_PREFIX as TOKEN_PREFIX, digest
from gradient.models import ApiToken, LoginSession, User

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/auth")
COOKIE = "gradient_session"
LIFETIME = 60 * 60 * 24 * 7
# Aggiornare l'ultimo utilizzo al più una volta l'ora evita una scrittura a ogni richiesta.
LAST_USED_RESOLUTION = timedelta(hours=1)


def now():
    return datetime.now(timezone.utc).replace(tzinfo=None)


def providers(settings):
    result = {}
    for name, client_id, secret, authorize, token, userinfo in (
        ("google", settings.google_client_id, settings.google_client_secret,
         "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
         "https://openidconnect.googleapis.com/v1/userinfo"),
        ("unipi", settings.wso2_client_id, settings.wso2_client_secret,
         settings.wso2_authorize_url, settings.wso2_token_url, settings.wso2_userinfo_url),
    ):
        if client_id and secret.get_secret_value() and settings.auth_secret:
            result[name] = dict(client_id=client_id, client_secret=secret.get_secret_value(),
                                authorize=authorize, token=token, userinfo=userinfo)
    return result


def profile_data(provider, profile):
    def claim(name, legacy):
        return profile.get(name) or (profile.get(f"http://wso2.org/claims/{legacy}") if provider == "unipi" else None)
    subject = claim("sub", "unipiUser")
    email = claim("email", "emailaddress")
    name = claim("name", "fullname") or " ".join(
        v for v in (claim("given_name", "givenname"), claim("family_name", "lastname")) if isinstance(v, str)
    )
    if not isinstance(subject, str) or not subject or len(subject) > 255:
        raise ValueError("Subject non valido")
    if not isinstance(email, str) or not email or len(email) > 320:
        raise ValueError("Email non valida")
    if provider == "google" and profile.get("email_verified") is not True:
        raise ValueError("Email non verificata")
    return subject, email, (name if isinstance(name, str) and name else email)[:200]


def user_data(user):
    return {"id": user.id, "name": user.name, "email": user.email, "provider": user.provider}


async def current_user(request: Request):
    token = request.cookies.get(COOKIE)
    if not token or len(token) > 128:
        return None
    async with request.app.state.store.database.sessions() as session:
        return await session.scalar(select(User).join(LoginSession).where(
            LoginSession.token_hash == digest(token), LoginSession.expires_at > now()))


def invalid_token():
    return HTTPException(401, "Token API non valido, scaduto o revocato.", headers={"WWW-Authenticate": "Bearer"})


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization")
    if header is None:
        return None
    scheme, _, token = header.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token.startswith(TOKEN_PREFIX) or len(token) > 128:
        raise invalid_token()
    return token


async def token_user(request: Request, token: str):
    async with request.app.state.store.database.sessions.begin() as session:
        pair = (await session.execute(select(User, ApiToken).join(ApiToken, ApiToken.user_id == User.id).where(
            ApiToken.token_hash == digest(token), or_(ApiToken.expires_at.is_(None), ApiToken.expires_at > now())))).first()
        if pair is None:
            return None
        user, api_token = pair
        if api_token.last_used_at is None or api_token.last_used_at < now() - LAST_USED_RESOLUTION:
            api_token.last_used_at = now()
    return user, api_token


async def require_user(request: Request):
    # Un'intestazione Authorization esclude il cookie: niente ricadute silenziose sulla sessione.
    token = bearer_token(request)
    if token is not None:
        result = await token_user(request, token)
        if result is None:
            raise invalid_token()
        user, request.state.api_token = result
        return user
    user = await current_user(request)
    if user is None:
        raise HTTPException(401, "Accedi per utilizzare Gradient.")
    return user


def check_origin(request: Request):
    # JSON + intestazione personalizzata impediscono l'invio tramite form cross-site.
    # Non serve con i token API: il browser non allega da solo l'intestazione Authorization.
    if request.headers.get("x-gradient-request") != "1":
        raise HTTPException(403, "Richiesta non valida.")
    origin = request.headers.get("origin")
    if origin and origin != request.app.state.settings.auth_base_url.rstrip("/"):
        raise HTTPException(403, "Origine non consentita.")


async def database_error_handler(request, error):
    code, message = database_error_details(error)
    logger.error("%s [%s]", message, code, exc_info=code == "DATABASE_ERROR")
    return JSONResponse({"detail": "Database non disponibile. Verificare la connessione e le migrazioni."}, status_code=503)


@router.get("/session")
async def session_info(request: Request):
    user = await current_user(request)
    return JSONResponse({"user": user_data(user) if user else None,
                         "providers": list(providers(request.app.state.settings))}, headers={"Cache-Control": "no-store"})


@router.get("/login/{provider}")
async def login(request: Request, provider: str):
    settings = request.app.state.settings
    config = providers(settings).get(provider)
    if not config:
        raise HTTPException(404, "Provider non configurato.")
    verifier = secrets.token_urlsafe(48)
    redirect_uri = settings.auth_base_url.rstrip("/") + f"/auth/callback/{provider}"
    async with AsyncOAuth2Client(client_id=config["client_id"], scope="openid email profile",
                                redirect_uri=redirect_uri, code_challenge_method="S256") as client:
        url, state = client.create_authorization_url(config["authorize"], code_verifier=verifier)
    request.session.clear()
    request.session.update(provider=provider, state=state, verifier=verifier)
    return RedirectResponse(url, status_code=302, headers={"Cache-Control": "no-store"})


async def save_login(database, provider, profile, old_token=None):
    subject, email, name = profile_data(provider, profile)
    token = secrets.token_urlsafe(32)
    # Un login concorrente della stessa identità può vincere l'inserimento iniziale.
    for attempt in range(2):
        try:
            async with database.sessions.begin() as session:
                user = await session.scalar(select(User).where(User.provider == provider, User.subject == subject))
                if user is None:
                    user = User(provider=provider, subject=subject, email=email, name=name, created_at=now(), last_login=now())
                    session.add(user)
                    await session.flush()
                user.email, user.name, user.last_login = email, name, now()
                await session.execute(delete(LoginSession).where(LoginSession.expires_at <= now()))
                if old_token:
                    await session.execute(delete(LoginSession).where(LoginSession.token_hash == digest(old_token)))
                session.add(LoginSession(token_hash=digest(token), user_id=user.id, expires_at=now() + timedelta(seconds=LIFETIME)))
            return token
        except IntegrityError as error:
            if attempt or not error.orig.args or error.orig.args[0] != 1062:
                raise


@router.get("/callback/{provider}")
async def callback(request: Request, provider: str):
    settings = request.app.state.settings
    config = providers(settings).get(provider)
    pending = dict(request.session)
    request.session.clear()
    state = request.query_params.get("state", "")
    if (not config or pending.get("provider") != provider or not state
            or not secrets.compare_digest(state.encode(), pending.get("state", "").encode())
            or not request.query_params.get("code") or request.query_params.get("error")):
        return RedirectResponse("/?auth_error=1", status_code=303)
    try:
        async with AsyncOAuth2Client(client_id=config["client_id"], client_secret=config["client_secret"],
                                    redirect_uri=settings.auth_base_url.rstrip("/") + f"/auth/callback/{provider}",
                                    timeout=15) as client:
            await client.fetch_token(config["token"], code=request.query_params["code"],
                                     code_verifier=pending["verifier"], grant_type="authorization_code")
            # L'identità proviene esclusivamente dall'endpoint HTTPS userinfo autenticato;
            # gli eventuali ID token non vengono decodificati né usati come prova d'identità.
            response = await client.get(config["userinfo"])
            response.raise_for_status()
            profile = response.json()
            if not isinstance(profile, dict):
                raise ValueError("Profilo non valido")
        session_token = await save_login(request.app.state.store.database, provider, profile, request.cookies.get(COOKIE))
    except (OAuthError, OAuth2Error, httpx.HTTPError, ValueError, KeyError):
        logger.warning("Login %s non riuscito: risposta OAuth o profilo non valido.", provider)
        return RedirectResponse("/?auth_error=1", status_code=303)
    response = RedirectResponse("/", status_code=303, headers={"Cache-Control": "no-store"})
    response.set_cookie(COOKIE, session_token, max_age=LIFETIME, httponly=True,
                        secure=settings.auth_base_url.startswith("https://"), samesite="lax")
    return response


@router.post("/logout")
async def logout(request: Request):
    check_origin(request)
    token = request.cookies.get(COOKIE)
    if token:
        async with request.app.state.store.database.sessions.begin() as session:
            await session.execute(delete(LoginSession).where(LoginSession.token_hash == digest(token)))
    request.session.clear()
    response = JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"})
    response.delete_cookie(COOKIE)
    return response
