import os
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select

from gradient.app import create_app
from gradient.models import Course, CourseTable, StudentRow

pytestmark = [
    pytest.mark.database,
    pytest.mark.skipif(os.getenv("GRADIENT_TEST_DB") != "1", reason="Usare make test-db per abilitare MariaDB"),
]


def query(client, document, **variables):
    response = client.post("/graphql", json={"query": document, "variables": variables})
    assert response.status_code == 200
    body = response.json()
    assert "errors" not in body, body
    return body["data"]


@pytest.fixture
def database():
    owned_courses = []
    with TestClient(create_app()) as client:
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
    with TestClient(create_app()) as restarted:
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
    with TestClient(create_app()) as restarted:
        courses = query(restarted, "{ courses { id degreeProgram } }")["courses"]
        assert next(item for item in courses if item["id"] == created["id"])["degreeProgram"] == "Matematica"
        tables = query(restarted, "query($id: ID!) { courseTables(courseId: $id) { id date location } }", id=created["id"])["courseTables"]
        assert next(item for item in tables if item["id"] == table_id) == {
            "id": table_id, "date": "2026-11-18", "location": "Aula A",
        }
        assert next(item for item in tables if item["id"] == empty_table_id) == {
            "id": empty_table_id, "date": None, "location": None,
        }
