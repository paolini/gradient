import asyncio

import pytest
from asyncmy import errors
from sqlalchemy.exc import OperationalError, ProgrammingError, SQLAlchemyError

from gradient.schema import schema


@pytest.mark.parametrize("operation", [
    "{ courses { id } }",
    'mutation { createCourse(input: {name: "Analisi", academicYear: "2026/27"}) { course { id } } }',
])
@pytest.mark.parametrize("error, code, expected_message", [
    (OperationalError(None, None, errors.OperationalError(2003, "password=secret")),
     "DATABASE_UNREACHABLE", "Verifica che MariaDB sia avviato"),
    (OperationalError(None, None, errors.OperationalError(2005, "password=secret")),
     "DATABASE_UNREACHABLE", "host e porta"),
    (OperationalError(None, None, errors.OperationalError(2013, "password=secret")),
     "DATABASE_CONNECTION_LOST", "Connessione al database interrotta"),
    (OperationalError(None, None, errors.OperationalError(1045, "password=secret")),
     "DATABASE_AUTH_FAILED", "nome utente e password"),
    (OperationalError(None, None, errors.OperationalError(1044, "password=secret")),
     "DATABASE_ACCESS_DENIED", "permessi necessari"),
    (OperationalError(None, None, errors.OperationalError(1049, "password=secret")),
     "DATABASE_NOT_FOUND", "database configurato non esiste"),
    (ProgrammingError(None, None, errors.ProgrammingError(1146, "password=secret")),
     "DATABASE_SCHEMA_NOT_READY", "make migrate"),
    (ProgrammingError(None, None, errors.ProgrammingError(1054, "password=secret")),
     "DATABASE_SCHEMA_NOT_READY", "make migrate"),
    (SQLAlchemyError("password=secret"),
     "DATABASE_ERROR", "Si è verificato un errore nel database"),
])
def test_graphql_database_errors(operation, error, code, expected_message, caplog):
    class UnavailableStore:
        async def courses(self):
            raise error

        async def create_course(self, name, academic_year, degree_program=None):
            raise error

    result = asyncio.run(schema.execute(operation, context_value={"store": UnavailableStore()}))
    assert result.errors and len(result.errors) == 1
    assert result.errors[0].extensions == {"code": "DATABASE_ERROR"}
    expected_public_message = (
        "Impossibile salvare i dati. Verificare la connessione e le migrazioni."
        if operation.startswith("mutation")
        else "Database non disponibile o non inizializzato. Verificare la connessione e le migrazioni."
    )
    assert result.errors[0].message == expected_public_message
    response = result.errors[0].formatted
    assert "secret" not in str(response)
    diagnostics = [record.getMessage() for record in caplog.records if record.name == "gradient.schema"]
    assert len(diagnostics) == 1
    assert expected_message in diagnostics[0]
    assert f"[{code}]" in diagnostics[0]
    assert "secret" not in diagnostics[0]
    traceback_logs = [record for record in caplog.records if record.name == "strawberry.execution"]
    if code == "DATABASE_ERROR":
        assert len(traceback_logs) == 1
        assert traceback_logs[0].exc_info
    else:
        assert traceback_logs == []
    if code == "DATABASE_UNREACHABLE":
        assert "migrate" not in diagnostics[0]


def test_unexpected_code_error_keeps_traceback(caplog):
    class BrokenStore:
        async def courses(self):
            raise RuntimeError("Errore inatteso nel codice")

    result = asyncio.run(schema.execute("{ courses { id } }", context_value={"store": BrokenStore()}))
    assert result.errors
    records = [record for record in caplog.records if record.name == "strawberry.execution"]
    assert len(records) == 1
    assert records[0].exc_info
    assert "Errore inatteso nel codice" in records[0].getMessage()
