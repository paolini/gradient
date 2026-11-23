import asyncio
import os
from datetime import timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select, update

from gradient.app import create_app
from gradient.auth import COOKIE, now, save_login
from gradient.models import ApiToken, Course, LoginSession, User
from gradient.schema import schema
from test_auth import settings

CREATE = 'mutation($input: CreateApiTokenInput!) { createApiToken(input: $input) { token { id name readOnly expiresAt } secret errors { field code } } }'


def run(query, api_token=None, store=None):
    context = {"store": store, "user": SimpleNamespace(id="u", name="Ada", email="a@example.org", provider="google"), "api_token": api_token}
    return asyncio.run(schema.execute(query, context_value=context))


def test_malformed_bearer_is_rejected_without_database():
    with TestClient(create_app(settings())) as client:
        for header in ["Bearer", "Bearer altro", "Basic gradient_x", "Bearer gradient_" + "x" * 200]:
            response = client.post("/graphql", headers={"Authorization": header}, json={"query": "{ me { id } }"})
            assert response.status_code == 401
            assert response.headers["www-authenticate"] == "Bearer"


def test_read_only_token_blocks_every_mutation():
    read_only = SimpleNamespace(read_only=True)
    result = run('mutation { previewCsv(input: {contentBase64: ""}) { totalRows } }', read_only)
    assert result.data is None
    assert result.errors[0].extensions["code"] == "READ_ONLY_TOKEN"
    # Il controllo usa l'operazione che Strawberry sceglie davvero, anche senza operationName.
    document = 'mutation B { createCourse(input: {name: "X", academicYear: "2026/27"}) { errors { code } } } query A { me { name } }'
    for name in [None, "B"]:
        result = asyncio.run(schema.execute(document, operation_name=name, context_value={"store": None, "api_token": read_only}))
        assert result.data is None and result.errors[0].extensions["code"] == "READ_ONLY_TOKEN"
    assert run('{ me { name } }', read_only).data == {"me": {"name": "Ada"}}


def test_tokens_cannot_manage_tokens():
    for api_token in [SimpleNamespace(read_only=False), SimpleNamespace(read_only=True)]:
        result = run('{ apiTokens { id } }', api_token)
        assert result.errors[0].extensions["code"] == "SESSION_REQUIRED"
    result = run('mutation { revokeApiToken(id: "x") { errors { code } } }', SimpleNamespace(read_only=False))
    assert result.errors[0].extensions["code"] == "SESSION_REQUIRED"


@pytest.mark.database
@pytest.mark.skipif(os.getenv('GRADIENT_TEST_DB') != '1', reason='Usare make test-db')
def test_real_api_tokens():
    subject = str(uuid4())
    app = create_app()
    with TestClient(app) as client:
        db = app.state.store.database
        async def cleanup():
            async with db.sessions.begin() as session:
                ids = select(User.id).where(User.subject == subject)
                await session.execute(delete(ApiToken).where(ApiToken.user_id.in_(ids)))
                await session.execute(delete(Course).where(Course.owner_id.in_(ids)))
                await session.execute(delete(LoginSession).where(LoginSession.user_id.in_(ids)))
                await session.execute(delete(User).where(User.subject == subject))
        try:
            session_token = client.portal.call(save_login, db, 'google', {'sub': subject, 'email': 'test@example.invalid', 'email_verified': True})
            browser = {'X-Gradient-Request': '1', 'Cookie': f'{COOKIE}={session_token}'}
            def create(**input):
                body = client.post('/graphql', headers=browser, json={'query': CREATE, 'variables': {'input': input}}).json()
                return body['data']['createApiToken']
            full = create(name='Script', expiresInDays=30)
            reader = create(name='Esportazione', readOnly=True)
            assert full['secret'].startswith('gradient_') and full['token']['expiresAt'] and not full['token']['readOnly']
            assert reader['token']['expiresAt'] is None and reader['token']['readOnly']
            assert create(name='Troppo', expiresInDays=0)['errors'][0]['field'] == 'expiresInDays'

            # Il Bearer non richiede X-Gradient-Request e ignora l'Origin.
            def api(secret, query, **headers):
                return client.post('/graphql', headers={'Authorization': f'Bearer {secret}', **headers}, json={'query': query})
            mutation = 'mutation { createCourse(input: {name: "Da script", academicYear: "2026/27"}) { course { id } errors { code } } }'
            assert api(full['secret'], mutation, Origin='https://altro.invalid').json()['data']['createCourse']['course']
            response = api(reader['secret'], mutation).json()
            assert response['errors'][0]['extensions']['code'] == 'READ_ONLY_TOKEN'
            assert [course['id'] for course in api(reader['secret'], '{ courses { id } }').json()['data']['courses']]
            assert api(full['secret'], '{ apiTokens { id } }').json()['errors'][0]['extensions']['code'] == 'SESSION_REQUIRED'

            listed = client.post('/graphql', headers=browser, json={'query': '{ apiTokens { name lastUsedAt } }'}).json()['data']['apiTokens']
            assert {token['name'] for token in listed} == {'Script', 'Esportazione'}
            assert all(token['lastUsedAt'] for token in listed)
            assert 'secret' not in str(listed)

            # Un cookie valido non salva un Bearer errato.
            assert client.post('/graphql', headers={**browser, 'Authorization': 'Bearer gradient_sbagliato'}, json={'query': '{ me { id } }'}).status_code == 401

            response = client.post('/graphql', headers=browser, json={'query': 'mutation($id: ID!) { revokeApiToken(id: $id) { token { id } errors { code } } }', 'variables': {'id': full['token']['id']}}).json()
            assert response['data']['revokeApiToken']['token']['id'] == full['token']['id']
            assert api(full['secret'], '{ me { id } }').status_code == 401
            async def expire():
                async with db.sessions.begin() as session:
                    await session.execute(update(ApiToken).where(ApiToken.id == reader['token']['id']).values(expires_at=now() - timedelta(seconds=1)))
            client.portal.call(expire)
            assert api(reader['secret'], '{ me { id } }').status_code == 401
        finally:
            client.portal.call(cleanup)
