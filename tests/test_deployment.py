from importlib import import_module

import pytest
from fastapi.testclient import TestClient

from gradient.config import Settings


@pytest.mark.parametrize('built', [False, True])
def test_frontend_and_api_routes_remain_separate(tmp_path, monkeypatch, built):
    module = import_module('gradient.app')
    monkeypatch.setattr(module, '__file__', str(tmp_path / 'gradient' / 'app.py'))
    if built:
        dist = tmp_path / 'frontend' / 'dist'
        (dist / 'assets').mkdir(parents=True)
        (dist / 'index.html').write_text('<html>Gradient</html>')
        (dist / 'assets' / 'app.js').write_text('console.log("Gradient")')
    settings = Settings(_env_file=None, db_password='unused')
    with TestClient(module.create_app(settings)) as client:
        assert client.get('/healthz').json() == {'status': 'ok'}
        assert client.get('/').status_code == (200 if built else 404)
        if built:
            assert 'Gradient' in client.get('/').text
            assert client.get('/assets/app.js').status_code == 200
        assert client.get('/assets/missing.js').status_code == 404
        assert client.get('/auth/session').json() == {'user': None, 'providers': []}
        assert client.post('/graphql', json={'query': '{me{id}}'}).status_code == 401
        assert client.get('/auth/missing').status_code == 404
        assert client.get('/.env').status_code == 404
