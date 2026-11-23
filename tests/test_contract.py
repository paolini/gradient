import re
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from graphql import parse, validate

from types import SimpleNamespace
from gradient.auth import require_user
from gradient.app import create_app
from gradient.config import Settings
from gradient.schema import schema
from gradient.services import InputError, identifier, text


def test_exported_schema_and_frontend_operations():
    assert Path("schema.graphql").read_text() == schema.as_str() + "\n"
    source = Path("frontend/src/api.ts").read_text()
    fragments = dict(re.findall(r"^const (\w+) = '(.*?)';$", source, flags=re.MULTILINE))
    block = source[source.index("export const operations = {"):]
    block = block[:block.index("\n};")]
    operations = [match[1] for match in re.findall(r"^  \w+: (['`])(.*?)\1,?$", block, flags=re.MULTILINE)]
    assert len(operations) == len(re.findall(r"^  \w+:", block, flags=re.MULTILINE)) == 20
    for operation in operations:
        operation = re.sub(r"\$\{(\w+)\}", lambda match: fragments[match[1]], operation)
        assert not validate(schema._schema, parse(operation)), operation


def test_settings_preserve_special_characters_in_credentials():
    settings = Settings(_env_file=None, db_password="a@:/#?%b")
    assert settings.database_url.password == "a@:/#?%b"
    assert "a@:/#?%b" not in str(settings.database_url)
    assert settings.db_port == 3306


def test_input_validation():
    assert text("  00123  ", "matricola", 64) == "00123"
    assert text("   ", "matricola", required=False) == ""
    assert identifier(str(uuid4()), "tableId")
    with pytest.raises(InputError):
        identifier("not-an-id", "tableId")
    with pytest.raises(InputError):
        text(" ", "name")
    with pytest.raises(InputError):
        text("x" * 201, "name")


def test_http_validation_without_database():
    settings = Settings(_env_file=None, db_password="unused")
    app = create_app(settings)
    app.dependency_overrides[require_user] = lambda: SimpleNamespace(id=None)
    with TestClient(app, headers={"X-Gradient-Request": "1"}) as client:
        result = client.post("/graphql", json={
            "query": 'mutation { createCourse(input: {name: " ", academicYear: "2026/27"}) { course { id } errors { field code } } }',
        })
        assert result.status_code == 200
        assert result.json()["data"]["createCourse"] == {
            "course": None, "errors": [{"field": "name", "code": "INVALID_INPUT"}],
        }
        result = client.post("/graphql", json={
            "query": 'mutation { createCourse(input: {name: "Analisi", academicYear: "2026/2029"}) { errors { field code } } }',
        })
        assert result.json()["data"]["createCourse"]["errors"][0]["field"] == "academicYear"
