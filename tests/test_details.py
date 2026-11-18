import asyncio
from contextlib import asynccontextmanager
from uuid import uuid4

import pytest

from gradient.schema import schema
from gradient.services import Store


class MemorySession:
    """Sostituisce solo la persistenza per verificare input e conversioni GraphQL."""
    def __init__(self):
        self.records = {}

    @asynccontextmanager
    async def begin(self):
        yield self

    async def get(self, model, entity_id):
        record = self.records.get(entity_id)
        return record if isinstance(record, model) else None

    def add(self, entity):
        entity.id = str(uuid4())
        self.records[entity.id] = entity


class MemoryDatabase:
    def __init__(self):
        self.sessions = MemorySession()


@pytest.mark.parametrize("details, expected", [
    ({}, (None, None, None)),
    ({"degree": None, "date": None, "location": None}, (None, None, None)),
    ({"degree": "  Matematica  ", "date": "2028-02-29", "location": "  Aula A  "}, ("Matematica", "2028-02-29", "Aula A")),
    ({"degree": "  ", "location": "  "}, (None, None, None)),
])
def test_details_graphql_round_trip(details, expected):
    async def run():
        store = Store(MemoryDatabase())
        result = await schema.execute('''mutation($input: CreateCourseInput!) {
          createCourse(input: $input) { course { id degreeProgram } errors { field } }
        }''', variable_values={"input": {
            "name": "Analisi", "academicYear": "2026/27",
            **({"degreeProgram": details["degree"]} if "degree" in details else {}),
        }}, context_value={"store": store})
        assert not result.errors
        course = result.data["createCourse"]
        assert course["errors"] == []
        assert course["course"]["degreeProgram"] == expected[0]
        result = await schema.execute('''mutation($input: CreateTableInput!) {
          createTable(input: $input) { table { date location } errors { field } }
        }''', variable_values={"input": {
            "courseId": course["course"]["id"], "name": "Compitino",
            **{key: value for key, value in details.items() if key in ("date", "location")},
        }}, context_value={"store": store})
        assert not result.errors
        assert result.data["createTable"] == {"table": {"date": expected[1], "location": expected[2]}, "errors": []}
    asyncio.run(run())


@pytest.mark.parametrize("value", ["2026-02-29", "18/11/2026", "2026-11-18T12:00:00", ""])
def test_invalid_date_is_rejected_before_database(value):
    result = asyncio.run(schema.execute('''mutation($input: CreateTableInput!) {
      createTable(input: $input) { table { id } }
    }''', variable_values={"input": {"courseId": str(uuid4()), "name": "Prova", "date": value}}, context_value={}))
    assert result.errors
    assert result.data is None


@pytest.mark.parametrize("operation, variables, field", [
    ('mutation($input: CreateCourseInput!) { createCourse(input: $input) { errors { field } } }',
     {"name": "Analisi", "academicYear": "2026/27", "degreeProgram": "x" * 201}, "degreeProgram"),
    ('mutation($input: CreateTableInput!) { createTable(input: $input) { errors { field } } }',
     {"courseId": str(uuid4()), "name": "Prova", "location": "x" * 201}, "location"),
])
def test_details_length_validation(operation, variables, field):
    result = asyncio.run(schema.execute(operation, variable_values={"input": variables}, context_value={"store": Store(MemoryDatabase())}))
    assert not result.errors
    payload = next(iter(result.data.values()))
    assert payload["errors"] == [{"field": field}]
