import os
import base64
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, event, select
from sqlalchemy.exc import OperationalError

from types import SimpleNamespace
from gradient.auth import require_user
from gradient.app import create_app
from gradient.models import Course, CourseTable, StudentRow

pytestmark = [
    pytest.mark.database,
    pytest.mark.skipif(os.getenv("GRADIENT_TEST_DB") != "1", reason="Usare make test-db per abilitare MariaDB"),
]


def authenticated_client():
    # Questi test preesistenti verificano la persistenza; l'accesso reale è in test_auth.py.
    app = create_app()
    app.dependency_overrides[require_user] = lambda: SimpleNamespace(id=None)
    return TestClient(app, headers={"X-Gradient-Request": "1"})


def query(client, document, **variables):
    response = client.post("/graphql", json={"query": document, "variables": variables})
    assert response.status_code == 200
    body = response.json()
    assert "errors" not in body, body
    return body["data"]


@pytest.fixture
def database():
    owned_courses = []
    with authenticated_client() as client:
        try:
            yield client, owned_courses
        finally:
            async def cleanup():
                async with client.app.state.store.database.sessions.begin() as session:
                    tables = select(CourseTable.id).where(CourseTable.course_id.in_(owned_courses))
                    await session.execute(delete(StudentRow).where(StudentRow.table_id.in_(tables)))
                    await session.execute(delete(CourseTable).where(CourseTable.course_id.in_(owned_courses)))
                    await session.execute(delete(Course).where(Course.id.in_(owned_courses)))
            client.portal.call(cleanup)


def course(database, degree_program=None):
    client, owned = database
    result = query(client, '''mutation($input: CreateCourseInput!) {
      createCourse(input: $input) { course { id name academicYear degreeProgram } errors { code } }
    }''', input={"name": f"Test Gradient {uuid4()}", "academicYear": "2026/27", "degreeProgram": degree_program})["createCourse"]
    assert result["errors"] == []
    owned.append(result["course"]["id"])
    assert result["course"]["academicYear"] == "2026/2027"
    return result["course"]


def table(client, course_id, name="Compitino", date=None, location=None):
    result = query(client, '''mutation($input: CreateTableInput!) {
      createTable(input: $input) { table { id courseId name } errors { code } }
    }''', input={"courseId": course_id, "name": name, "date": date, "location": location})["createTable"]
    assert not result["errors"]
    return result["table"]["id"]


def student(client, table_id, matricola=None, first_name="Ada", last_name="Rossi"):
    return query(client, '''mutation($input: AddStudentInput!) {
      addStudent(input: $input) { row { id tableId matricola firstName lastName } errors { field code message } }
    }''', input={"tableId": table_id, "matricola": matricola, "firstName": first_name, "lastName": last_name})["addStudent"]


def test_persistence_across_app_restart(database):
    client, _ = database
    created_course = course(database)
    table_id = table(client, created_course["id"])
    row = student(client, table_id, "001234")["row"]
    assert row["matricola"] == "001234"
    with authenticated_client() as restarted:
        courses = query(restarted, "{ courses { id } }")["courses"]
        assert {"id": created_course["id"]} in courses
        tables = query(restarted, "query($id: ID!) { courseTables(courseId: $id) { id } }", id=created_course["id"])["courseTables"]
        assert tables == [{"id": table_id}]
        rows = query(restarted, "query($id: ID!) { studentRows(tableId: $id) { id matricola firstName lastName } }", id=table_id)["studentRows"]
        assert rows == [{"id": row["id"], "matricola": "001234", "firstName": "Ada", "lastName": "Rossi"}]


def test_matricola_uniqueness_missing_values_and_local_names(database):
    client, _ = database
    course_id = course(database)["id"]
    first = table(client, course_id)
    second = table(client, course_id, "Orale")
    assert not student(client, first, "00123")["errors"]
    duplicate = student(client, first, " 00123 ")
    assert duplicate["row"] is None
    assert duplicate["errors"][0]["code"] == "DUPLICATE_MATRICOLA"
    assert not student(client, second, "00123", last_name="Bianchi")["errors"]
    missing_a = student(client, first, None)["row"]
    missing_b = student(client, first, "  ")["row"]
    assert missing_a["matricola"] is None and missing_b["matricola"] is None
    assert missing_a["id"] != missing_b["id"]
    rows = query(client, "query($id: ID!) { studentRows(tableId: $id) { matricola lastName } }", id=first)["studentRows"]
    assert next(row for row in rows if row["matricola"])["lastName"] == "Rossi"


def test_missing_parent_returns_structured_error(database):
    client, _ = database
    result = student(client, str(uuid4()), "123")
    assert result["row"] is None
    assert result["errors"][0]["code"] == "NOT_FOUND"


def test_course_and_table_details_survive_reload(database):
    client, _ = database
    created = course(database, "Matematica")
    table_id = table(client, created["id"], date="2026-11-18", location="Aula A")
    empty_table_id = table(client, created["id"], "Elenco studenti")
    with authenticated_client() as restarted:
        courses = query(restarted, "{ courses { id degreeProgram } }")["courses"]
        assert next(item for item in courses if item["id"] == created["id"])["degreeProgram"] == "Matematica"
        tables = query(restarted, "query($id: ID!) { courseTables(courseId: $id) { id date location } }", id=created["id"])["courseTables"]
        assert next(item for item in tables if item["id"] == table_id) == {
            "id": table_id, "date": "2026-11-18", "location": "Aula A",
        }
        assert next(item for item in tables if item["id"] == empty_table_id) == {
            "id": empty_table_id, "date": None, "location": None,
        }


IMPORT = '''mutation($input: ImportCsvInput!) {
  importCsvTable(input: $input) { table { id } importedCount errors { row field message } }
}'''


def test_import_reviewed_csv_persists_all_students(database):
    client, _ = database
    course_id = course(database)["id"]
    content = base64.b64encode(Path("examples/unipi-iscritti.csv").read_bytes()).decode("ascii")
    result = query(client, IMPORT, input={
        "table": {"courseId": course_id, "name": "Import CSV"},
        "file": {"contentBase64": content},
    })["importCsvTable"]
    assert not result["errors"]
    assert result["importedCount"] == 14
    with authenticated_client() as restarted:
        rows = query(restarted, "query($id: ID!) { studentRows(tableId: $id) { matricola email dsa dis } }", id=result["table"]["id"])["studentRows"]
        assert len(rows) == 14
        assert len({row["matricola"] for row in rows}) == 14
        assert all(row["email"].endswith("@example.invalid") for row in rows)
        assert sum(row["dsa"] for row in rows) == 2
        assert all(not row["dis"] for row in rows)


def test_failed_import_rolls_back_table_and_rows(database):
    client, _ = database
    course_id = course(database)["id"]
    engine = client.app.state.store.database.engine.sync_engine
    def fail_insert(connection, cursor, statement, parameters, context, executemany):
        if statement.lstrip().upper().startswith("INSERT INTO STUDENT_ROWS"):
            raise OperationalError(None, None, Exception(2013, "simulated write failure"))
    event.listen(engine, "before_cursor_execute", fail_insert)
    try:
        response = client.post("/graphql", json={"query": IMPORT, "variables": {"input": {
            "table": {"courseId": course_id, "name": "Non deve restare"},
            "file": {"contentBase64": base64.b64encode(b"Matricola;Nome\n001;Ada\n002;Anna").decode("ascii")},
        }}})
        assert response.json()["errors"][0]["extensions"]["code"] == "DATABASE_ERROR"
    finally:
        event.remove(engine, "before_cursor_execute", fail_insert)
    assert query(client, "query($id: ID!) { courseTables(courseId: $id) { id } }", id=course_id)["courseTables"] == []
