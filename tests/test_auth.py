import asyncio
import base64
import os
from datetime import timedelta
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select, update

from gradient.app import create_app
from gradient.auth import COOKIE, digest, now, profile_data, save_login
from gradient.config import Settings
from gradient.models import Course, CourseTable, LoginSession, StudentRow, User
from gradient.services import InputError, Store
from test_details import MemoryDatabase


def settings(**kwargs):
    return Settings(_env_file=None, db_password="unused", auth_secret="x" * 40,
                    google_client_id="google-test", google_client_secret="test-secret",
                    wso2_client_id="unipi-test", wso2_client_secret="test-secret", **kwargs)


def test_anonymous_and_csrf_are_rejected_without_database():
    with TestClient(create_app(settings())) as client:
        assert client.get('/auth/session').json() == {"user": None, "providers": ["google", "unipi"]}
        assert client.post('/graphql', json={"query": "{ courses { id } }"}).status_code == 401
        assert client.get('/graphql').status_code == 401
        assert client.post('/auth/logout').status_code == 403
        assert client.post('/auth/logout', headers={"X-Gradient-Request": "1", "Origin": "https://evil.invalid"}).status_code == 403
        assert client.post('/auth/logout', headers={"X-Gradient-Request": "1"}).status_code == 200


@pytest.mark.parametrize('provider', ['google', 'unipi'])
def test_oauth_redirect_pkce_and_invalid_state(provider):
    with TestClient(create_app(settings())) as client:
        response = client.get(f'/auth/login/{provider}', follow_redirects=False)
        params = parse_qs(urlsplit(response.headers['location']).query)
        assert params['code_challenge_method'] == ['S256']
        assert params['redirect_uri'] == [f'http://127.0.0.1:5173/auth/callback/{provider}']
        assert set(params['scope'][0].split()) == {'openid', 'email', 'profile'}
        assert 'httponly' in response.headers['set-cookie'].lower()
        response = client.get(f'/auth/callback/{provider}?state=wrong&code=bad', follow_redirects=False)
        assert response.headers['location'] == '/?auth_error=1'
        assert COOKIE not in response.cookies
        assert client.get('/auth/login/unknown').status_code == 404


def test_google_and_legacy_unipi_profiles():
    assert profile_data('google', {'sub': '123', 'email': 'a@example.org', 'email_verified': True, 'name': 'Ada'}) == ('123', 'a@example.org', 'Ada')
    with pytest.raises(ValueError):
        profile_data('google', {'sub': '123', 'email': 'a@example.org'})
    prefix = 'http://wso2.org/claims/'
    assert profile_data('unipi', {prefix+'unipiUser': 'ada', prefix+'emailaddress': 'ada@unipi.it', prefix+'givenname': 'Ada', prefix+'lastname': 'Rossi'}) == ('ada', 'ada@unipi.it', 'Ada Rossi')
    with pytest.raises(ValueError):
        profile_data('unipi', {'email': 'a@unipi.it'})


def test_course_and_table_access_checks():
    async def run():
        db = MemoryDatabase()
        owner = Store(db, 'owner')
        other = Store(db, 'other')
        course = await owner.create_course('Analisi', '2026/27')
        table = await owner.create_table(course.id, 'Prova')
        assert course.owner_id == 'owner'
        for model, record in [(Course, course), (CourseTable, table)]:
            await owner.require(db.sessions, model, record.id, 'id')
            with pytest.raises(InputError, match='Elemento non trovato'):
                await other.require(db.sessions, model, record.id, 'id')
        with pytest.raises(InputError):
            await other.create_table(course.id, 'Abusiva')
        with pytest.raises(InputError):
            await other.add_student(table.id, '001', 'Ada', 'Rossi')
    asyncio.run(run())


def test_auth_configuration():
    with pytest.raises(ValueError):
        settings(auth_base_url='http://example.org')
    with pytest.raises(ValueError):
        Settings(_env_file=None, db_password='unused', google_client_id='test')


@pytest.mark.parametrize('provider', ['google', 'unipi'])
def test_callback_exchanges_code_and_reads_trusted_userinfo(monkeypatch, provider):
    import gradient.auth as auth
    from authlib.integrations.httpx_client import AsyncOAuth2Client
    seen = []
    def handler(request):
        seen.append(request)
        if request.url.path.endswith('/token'):
            assert b'code_verifier=' in request.content
            assert b'code=good' in request.content
            return httpx.Response(200, json={'access_token': 'access', 'token_type': 'Bearer'})
        assert request.headers['authorization'] == 'Bearer access'
        return httpx.Response(200, json={'sub': 'subject', 'email': 'a@example.org', 'email_verified': True})
    def factory(**kwargs):
        return AsyncOAuth2Client(**kwargs, transport=httpx.MockTransport(handler))
    async def save(database, selected, profile, old_token):
        assert selected == provider
        assert profile['sub'] == 'subject'
        return 'opaque-session'
    monkeypatch.setattr(auth, 'AsyncOAuth2Client', factory)
    monkeypatch.setattr(auth, 'save_login', save)
    with TestClient(create_app(settings())) as client:
        response = client.get(f'/auth/login/{provider}', follow_redirects=False)
        state = parse_qs(urlsplit(response.headers['location']).query)['state'][0]
        response = client.get(f'/auth/callback/{provider}?state={state}&code=good', follow_redirects=False)
        assert response.headers['location'] == '/'
        assert response.cookies[COOKIE] == 'opaque-session'
        assert len(seen) == 2
        response = client.get(f'/auth/callback/{provider}?state={state}&code=good', follow_redirects=False)
        assert response.headers['location'] == '/?auth_error=1'
        assert len(seen) == 2


@pytest.mark.database
@pytest.mark.skipif(os.getenv('GRADIENT_TEST_DB') != '1', reason='Usare make test-db')
def test_real_sessions_ownership_logout_and_expiry():
    subject = str(uuid4())
    app = create_app()
    with TestClient(app, headers={'X-Gradient-Request': '1'}) as client:
        db = app.state.store.database
        async def create(provider):
            return await save_login(db, provider, {'sub': subject, 'email': 'test@example.invalid', 'email_verified': True})
        async def cleanup():
            async with db.sessions.begin() as session:
                ids = select(User.id).where(User.subject == subject)
                courses = select(Course.id).where(Course.owner_id.in_(ids))
                tables = select(CourseTable.id).where(CourseTable.course_id.in_(courses))
                await session.execute(delete(StudentRow).where(StudentRow.table_id.in_(tables)))
                await session.execute(delete(CourseTable).where(CourseTable.course_id.in_(courses)))
                await session.execute(delete(Course).where(Course.owner_id.in_(ids)))
                await session.execute(delete(LoginSession).where(LoginSession.user_id.in_(ids)))
                await session.execute(delete(User).where(User.subject == subject))
        try:
            first = client.portal.call(create, 'google')
            second = client.portal.call(create, 'unipi')
            client.cookies.set(COOKIE, first)
            me = client.get('/auth/session').json()['user']
            response = client.post('/graphql', json={'query': 'mutation { createCourse(input: {name:"Privato", academicYear:"2026/27"}) {course {id} errors {code}} }'}).json()
            course_id = response['data']['createCourse']['course']['id']
            response = client.post('/graphql', json={'query': 'mutation($id:ID!){createTable(input:{courseId:$id,name:"Prova"}){table{id}}}', 'variables': {'id': course_id}}).json()
            table_id = response['data']['createTable']['table']['id']
            assert client.post('/graphql', json={'query': '{me{id}}'}).json()['data']['me']['id'] == me['id']
            client.cookies.set(COOKIE, second)
            assert client.get('/auth/session').json()['user']['id'] != me['id']
            assert client.post('/graphql', json={'query': '{courses {id}}'}).json()['data']['courses'] == []
            response = client.post('/graphql', json={'query': 'mutation($id:ID!){createTable(input:{courseId:$id,name:"No"}){errors{code}}}', 'variables': {'id': course_id}}).json()
            assert response['data']['createTable']['errors'][0]['code'] == 'NOT_FOUND'
            for query, entity_id in [
                ('query($id:ID!){courseTables(courseId:$id){id}}', course_id),
                ('query($id:ID!){studentRows(tableId:$id){id}}', table_id),
            ]:
                response = client.post('/graphql', json={'query': query, 'variables': {'id': entity_id}}).json()
                assert response['errors'][0]['extensions']['code'] == 'NOT_FOUND'
            response = client.post('/graphql', json={'query': 'mutation($id:ID!){addStudent(input:{tableId:$id,matricola:"001"}){errors{code}}}', 'variables': {'id': table_id}}).json()
            assert response['data']['addStudent']['errors'][0]['code'] == 'NOT_FOUND'
            response = client.post('/graphql', json={'query': 'mutation($input:ImportCsvInput!){importCsvTable(input:$input){table{id} errors{message}}}', 'variables': {'input': {
                'table': {'courseId': course_id, 'name': 'Abusiva'},
                'file': {'contentBase64': base64.b64encode(b'Matricola;Nome\n001;Ada').decode()},
            }}}).json()
            assert response['data']['importCsvTable']['table'] is None
            assert response['data']['importCsvTable']['errors']
            assert client.post('/graphql', headers={'Origin': 'https://evil.invalid'}, json={'query': '{me{id}}'}).status_code == 403
            assert client.post('/auth/logout').status_code == 200
            client.cookies.set(COOKIE, second)
            assert client.get('/auth/session').json()['user'] is None
            async def expire():
                async with db.sessions.begin() as session:
                    await session.execute(update(LoginSession).where(LoginSession.token_hash == digest(first)).values(expires_at=now()-timedelta(seconds=1)))
            client.portal.call(expire)
            client.cookies.set(COOKIE, first)
            assert client.post('/graphql', json={'query': '{courses{id}}'}).status_code == 401
        finally:
            client.portal.call(cleanup)
