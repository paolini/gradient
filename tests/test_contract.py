import re
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from graphql import parse, validate

from gradient.app import create_app
from gradient.config import Settings
from gradient.schema import schema
from gradient.services import InputError, identifier, text


def test_exported_schema_and_frontend_operations():
    assert Path("schema.graphql").read_text() == schema.as_str() + "\n"
    source = Path("frontend/src/api.ts").read_text()
    operations = re.findall(r"^  \w+: '(.*?)',?$", source, flags=re.MULTILINE)
    assert len(operations) == 6
    for operation in operations:
        assert not validate(schema._schema, parse(operation))


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
    with TestClient(create_app(settings)) as client:
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
