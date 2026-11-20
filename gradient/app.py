from contextlib import asynccontextmanager

import secrets

from fastapi import Depends, FastAPI, Request
from starlette.middleware.sessions import SessionMiddleware
from sqlalchemy.exc import SQLAlchemyError

from gradient.auth import router, require_user, check_origin, database_error_handler
from strawberry.fastapi import GraphQLRouter

from gradient.config import Settings
from gradient.database import Database
from gradient.schema import schema
from gradient.services import Store


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        database = Database(settings)
        app.state.settings = settings
        app.state.store = Store(database)
        try:
            yield
        finally:
            await database.close()

    application = FastAPI(title="Gradient", lifespan=lifespan)

    application.add_middleware(SessionMiddleware,
        secret_key=settings.auth_secret.get_secret_value() if settings.auth_secret else secrets.token_urlsafe(32),
        session_cookie="gradient_oauth", max_age=600, same_site="lax",
        https_only=settings.auth_base_url.startswith("https://"))
    application.include_router(router)
    application.add_exception_handler(SQLAlchemyError, database_error_handler)

    async def context(request: Request, user=Depends(require_user)):
        if request.method == "POST":
            check_origin(request)
        return {"store": Store(request.app.state.store.database, user.id), "user": user}

    application.include_router(GraphQLRouter(schema, context_getter=context, allow_queries_via_get=False), prefix="/graphql")
    return application


app = create_app()
