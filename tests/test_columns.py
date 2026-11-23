import base64
import os
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError, OperationalError

from gradient.app import create_app
from gradient.auth import COOKIE, save_login
from gradient.models import Cell, Course, CourseTable, LoginSession, StudentRow, User

pytestmark = [
    pytest.mark.database,
    pytest.mark.skipif(os.getenv("GRADIENT_TEST_DB") != "1", reason="Usare make test-db per abilitare MariaDB"),
]

ROW = "id matricola firstName lastName dsa dis cells { columnId display flag gradeBase gradeModifier text }"
COLUMN = "id kind name grade { maximum allowAboveMaximum allowNegative passThreshold modifiers }"


def gql(client, document, **variables):
    response = client.post("/graphql", json={"query": document, "variables": variables})
    assert response.status_code == 200, response.text
    body = response.json()
    assert "errors" not in body, body
    return body["data"]


@pytest.fixture
def users():
    """Due utenti reali con sessione; i dati creati vengono rimossi al termine."""
    subject = str(uuid4())
    app = create_app()
    with TestClient(app, headers={"X-Gradient-Request": "1"}) as client:
        db = app.state.store.database

        async def create(provider):
            return await save_login(db, provider, {"sub": subject, "email": "test@example.invalid", "email_verified": True})

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
            yield client, client.portal.call(create, "google"), client.portal.call(create, "unipi")
        finally:
            client.portal.call(cleanup)


def setup_table(client):
    course_id = gql(client, 'mutation { createCourse(input: {name: "Colonne", academicYear: "2026/27"}) { course { id } } }')["createCourse"]["course"]["id"]
    table_id = gql(client, "mutation($id: ID!) { createTable(input: {courseId: $id, name: \"Compitino\"}) { table { id } } }", id=course_id)["createTable"]["table"]["id"]
    return course_id, table_id


def columns(client, table_id):
    return gql(client, f"query($id: ID!) {{ tableColumns(tableId: $id) {{ {COLUMN} }} }}", id=table_id)["tableColumns"]


def add_column(client, table_id, kind, name, grade=None):
    return gql(client, f"mutation($input: AddColumnInput!) {{ addColumn(input: $input) {{ column {{ {COLUMN} }} errors {{ field code message }} }} }}",
               input={"tableId": table_id, "kind": kind, "name": name, "grade": grade})["addColumn"]


def set_cell(client, row_id, column_id, value):
    return gql(client, f"mutation($input: SetCellInput!) {{ setCell(input: $input) {{ row {{ {ROW} }} errors {{ field code message }} }} }}",
               input={"rowId": row_id, "columnId": column_id, "value": value})["setCell"]


def add_student(client, table_id, matricola):
    return gql(client, f"mutation($id: ID!, $m: String) {{ addStudent(input: {{tableId: $id, matricola: $m, lastName: \"Rossi\"}}) {{ row {{ {ROW} }} }} }}",
               id=table_id, m=matricola)["addStudent"]["row"]


def count_cells(client, column_id):
    async def run():
        async with client.app.state.store.database.sessions() as session:
            return await session.scalar(select(func.count()).select_from(Cell).where(Cell.column_id == column_id))
    return client.portal.call(run)


def test_new_tables_have_builtin_columns(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    assert [column["kind"] for column in columns(client, table_id)] == ["MATRICOLA", "LAST_NAME", "FIRST_NAME", "EMAIL", "DSA", "DIS"]
    course_id = gql(client, '{ courses { id } }')["courses"][0]["id"]
    content = base64.b64encode(Path("examples/unipi-iscritti.csv").read_bytes()).decode("ascii")
    imported = gql(client, "mutation($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id } } }",
                   input={"table": {"courseId": course_id, "name": "Import"}, "file": {"contentBase64": content}})["importCsvTable"]["table"]["id"]
    assert len(columns(client, imported)) == 6


def test_custom_columns_and_cells(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    present = add_column(client, table_id, "FLAG", "Presente")["column"]
    grade = add_column(client, table_id, "GRADE", "Voto", {"maximum": "30", "modifiers": ["+", "-", "L"], "passThreshold": "18"})["column"]
    exercise = add_column(client, table_id, "GRADE", "Es1", {"maximum": "4"})["column"]
    note = add_column(client, table_id, "TEXT", "Note")["column"]
    assert grade["grade"] == {"maximum": "30", "allowAboveMaximum": False, "allowNegative": False, "passThreshold": "18", "modifiers": ["+", "-", "L"]}
    assert add_column(client, table_id, "TEXT", "voto")["errors"][0]["code"] == "DUPLICATE_NAME"
    assert add_column(client, table_id, "MATRICOLA", "Altra")["errors"][0]["field"] == "kind"

    row = add_student(client, table_id, "001")
    assert row["cells"] == []
    result = set_cell(client, row["id"], grade["id"], "30 e lode")
    assert result["row"]["cells"][0]["display"] == "30L"
    assert result["row"]["cells"][0]["gradeBase"] == "30"
    assert result["row"]["cells"][0]["gradeModifier"] == "L"
    assert set_cell(client, row["id"], present["id"], "no")["row"]["cells"]
    assert set_cell(client, row["id"], note["id"], "  ritirato a metà  ")["errors"] == []
    assert set_cell(client, row["id"], exercise["id"], "3,5")["errors"] == []
    for value, code in [("4+", "INVALID_INPUT"), ("4,5", "INVALID_INPUT")]:
        assert set_cell(client, row["id"], exercise["id"], value)["errors"][0]["code"] == code

    rows = gql(client, f"query($id: ID!) {{ studentRows(tableId: $id) {{ {ROW} }} }}", id=table_id)["studentRows"]
    cells = {cell["columnId"]: cell for cell in rows[0]["cells"]}
    assert cells[present["id"]]["flag"] is False and cells[present["id"]]["display"] == "no"
    assert cells[note["id"]]["text"] == "ritirato a metà"
    assert cells[exercise["id"]]["display"] == "3,5"

    # Svuotare una cella la elimina: il vuoto non è memorizzato.
    assert count_cells(client, present["id"]) == 1
    assert set_cell(client, row["id"], present["id"], "")["errors"] == []
    assert count_cells(client, present["id"]) == 0
    assert set_cell(client, row["id"], note["id"], "   ")["errors"] == []
    assert count_cells(client, note["id"]) == 0


def test_builtin_cells_update_row_fields(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    by_kind = {column["kind"]: column["id"] for column in columns(client, table_id)}
    first = add_student(client, table_id, "001")
    second = add_student(client, table_id, None)
    assert set_cell(client, second["id"], by_kind["MATRICOLA"], "001")["errors"][0]["code"] == "DUPLICATE_MATRICOLA"
    assert set_cell(client, second["id"], by_kind["MATRICOLA"], " 002 ")["row"]["matricola"] == "002"
    assert set_cell(client, first["id"], by_kind["MATRICOLA"], "")["row"]["matricola"] is None
    assert set_cell(client, first["id"], by_kind["FIRST_NAME"], "Ada")["row"]["firstName"] == "Ada"
    assert set_cell(client, first["id"], by_kind["DSA"], "sì")["row"]["dsa"] is True
    assert set_cell(client, first["id"], by_kind["DSA"], "")["row"]["dsa"] is False


def test_grade_configuration_respects_existing_values(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    grade = add_column(client, table_id, "GRADE", "Orale", {"modifiers": ["+"]})["column"]
    row = add_student(client, table_id, "001")
    set_cell(client, row["id"], grade["id"], "7+")
    update = "mutation($input: UpdateColumnInput!) { updateColumn(input: $input) { column { name grade { modifiers } } errors { code } } }"
    result = gql(client, update, input={"columnId": grade["id"], "grade": {"modifiers": []}})["updateColumn"]
    assert result["errors"][0]["code"] == "INVALID_EXISTING_VALUES"
    result = gql(client, update, input={"columnId": grade["id"], "name": "Orale finale"})["updateColumn"]
    assert result["column"]["name"] == "Orale finale"


def test_delete_column_removes_cells_but_not_builtins(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    note = add_column(client, table_id, "TEXT", "Note")["column"]
    row = add_student(client, table_id, "001")
    set_cell(client, row["id"], note["id"], "x")
    remove = "mutation($id: ID!) { deleteColumn(columnId: $id) { errors { field } } }"
    builtin = columns(client, table_id)[0]["id"]
    assert gql(client, remove, id=builtin)["deleteColumn"]["errors"]
    assert gql(client, remove, id=note["id"])["deleteColumn"]["errors"] == []
    assert count_cells(client, note["id"]) == 0
    assert len(columns(client, table_id)) == 6


VIEW = "id name active columnOrder hiddenColumns rowSort { columnId descending }"
VIEWS = f"query($id: ID!) {{ tableViews(tableId: $id) {{ {VIEW} }} }}"
SAVE = f"mutation($input: SaveViewInput!) {{ saveTableView(input: $input) {{ view {{ {VIEW} }} errors {{ field code }} }} }}"


def save_view(client, table_id, name, order, view_id=None, hidden=(), sort=()):
    return gql(client, SAVE, input={"tableId": table_id, "viewId": view_id, "name": name, "columnOrder": order,
                                     "hiddenColumns": list(hidden), "rowSort": list(sort)})["saveTableView"]


def test_personal_views(users):
    client, owner, other = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    ids = [column["id"] for column in columns(client, table_id)]

    # Senza viste salvate si usa una predefinita non memorizzata.
    [default] = gql(client, VIEWS, id=table_id)["tableViews"]
    assert default["id"] is None and default["active"] and default["name"] == "Predefinita"
    assert default["columnOrder"] == ids and default["hiddenColumns"] == []
    assert [item["columnId"] for item in default["rowSort"]] == [ids[1], ids[2]]

    first = save_view(client, table_id, "Predefinita", [ids[2], ids[1], ids[0]], hidden=[ids[3]], sort=[{"columnId": ids[0], "descending": True}])
    assert first["errors"] == []
    first = first["view"]
    assert first["columnOrder"] == [ids[2], ids[1], ids[0]] + ids[3:]
    second = save_view(client, table_id, "Solo nomi", ids, hidden=ids[3:])["view"]
    assert save_view(client, table_id, "solo nomi", ids)["errors"][0]["code"] == "DUPLICATE_NAME"

    views = {view["name"]: view for view in gql(client, VIEWS, id=table_id)["tableViews"]}
    assert set(views) == {"Predefinita", "Solo nomi"}
    assert views["Solo nomi"]["active"] and not views["Predefinita"]["active"]

    # Si riapre l'ultima vista usata.
    selected = gql(client, f"mutation($id: ID!) {{ selectTableView(viewId: $id) {{ view {{ {VIEW} }} errors {{ code }} }} }}", id=first["id"])["selectTableView"]
    assert selected["view"]["active"]
    active = [view["name"] for view in gql(client, VIEWS, id=table_id)["tableViews"] if view["active"]]
    assert active == ["Predefinita"]

    # Le colonne aggiunte dopo compaiono in fondo a ogni vista; la tabella non cambia.
    note = add_column(client, table_id, "TEXT", "Note")["column"]["id"]
    assert all(view["columnOrder"][-1] == note for view in gql(client, VIEWS, id=table_id)["tableViews"])
    assert [column["id"] for column in columns(client, table_id)] == ids + [note]

    unknown = save_view(client, table_id, "Errata", [str(uuid4())])
    assert unknown["errors"][0]["code"] == "NOT_FOUND"

    remove = f"mutation($id: ID!) {{ deleteTableView(viewId: $id) {{ views {{ {VIEW} }} errors {{ code }} }} }}"
    remaining = gql(client, remove, id=first["id"])["deleteTableView"]["views"]
    assert [view["name"] for view in remaining] == ["Solo nomi"] and remaining[0]["active"]
    remaining = gql(client, remove, id=second["id"])["deleteTableView"]["views"]
    assert remaining[0]["id"] is None

    # Le viste sono private: un altro utente non vede né modifica.
    kept = save_view(client, table_id, "Mia", ids)["view"]
    client.cookies.set(COOKIE, other)
    response = client.post("/graphql", json={"query": VIEWS, "variables": {"id": table_id}}).json()
    assert response["errors"][0]["extensions"]["code"] == "NOT_FOUND"
    assert save_view(client, table_id, "Intrusa", [])["errors"][0]["code"] == "NOT_FOUND"
    assert gql(client, remove, id=kept["id"])["deleteTableView"]["errors"][0]["code"] == "NOT_FOUND"


def test_other_users_cannot_touch_columns_or_cells(users):
    client, owner, other = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    note = add_column(client, table_id, "TEXT", "Note")["column"]
    row = add_student(client, table_id, "001")
    client.cookies.set(COOKIE, other)
    assert set_cell(client, row["id"], note["id"], "x")["errors"][0]["code"] == "NOT_FOUND"
    response = client.post("/graphql", json={"query": "query($id: ID!) { tableColumns(tableId: $id) { id } }", "variables": {"id": table_id}}).json()
    assert response["errors"][0]["extensions"]["code"] == "NOT_FOUND"
    remove = gql(client, "mutation($id: ID!) { deleteColumn(columnId: $id) { errors { code } } }", id=note["id"])["deleteColumn"]
    assert remove["errors"][0]["code"] == "NOT_FOUND"
    client.cookies.set(COOKIE, owner)
    assert count_cells(client, note["id"]) == 0
    assert len(columns(client, table_id)) == 7


def test_cell_from_another_table_is_rejected(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    course_id, first = setup_table(client)
    second = gql(client, "mutation($id: ID!) { createTable(input: {courseId: $id, name: \"Orale\"}) { table { id } } }", id=course_id)["createTable"]["table"]["id"]
    note = add_column(client, second, "TEXT", "Note")["column"]
    row = add_student(client, first, "001")
    assert set_cell(client, row["id"], note["id"], "x")["errors"][0]["code"] == "NOT_FOUND"


def test_database_rejects_cells_without_value(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    _, table_id = setup_table(client)
    note = add_column(client, table_id, "TEXT", "Note")["column"]
    row = add_student(client, table_id, "001")

    async def insert_empty():
        async with client.app.state.store.database.sessions.begin() as session:
            session.add(Cell(row_id=row["id"], column_id=note["id"]))
    with pytest.raises((IntegrityError, OperationalError)):
        client.portal.call(insert_empty)


def delete_students(client, table_id, row_ids):
    return gql(client, "mutation($input: DeleteStudentsInput!) { deleteStudents(input: $input) { deletedIds errors { field code message } } }",
               input={"tableId": table_id, "rowIds": row_ids})["deleteStudents"]


def row_ids(client, table_id):
    return {row["id"] for row in gql(client, "query($id: ID!) { studentRows(tableId: $id) { id } }", id=table_id)["studentRows"]}


def test_delete_students_removes_rows_and_cells_only_from_their_table(users):
    client, owner, other = users
    client.cookies.set(COOKIE, owner)
    course_id, table_id = setup_table(client)
    second = gql(client, "mutation($id: ID!) { createTable(input: {courseId: $id, name: \"Scritto\"}) { table { id } } }", id=course_id)["createTable"]["table"]["id"]
    grade = add_column(client, table_id, "GRADE", "Voto")["column"]
    first, kept, unnamed = add_student(client, table_id, "001"), add_student(client, table_id, "002"), add_student(client, table_id, None)
    same_student = add_student(client, second, "001")
    assert set_cell(client, first["id"], grade["id"], "7")["errors"] == []

    # Lista vuota, righe di un'altra tabella o inesistenti: non si rimuove nulla.
    assert delete_students(client, table_id, []) == {"deletedIds": [], "errors": []}
    assert delete_students(client, table_id, [first["id"], same_student["id"]])["errors"][0]["code"] == "NOT_FOUND"
    assert delete_students(client, table_id, [first["id"], str(uuid4())])["errors"][0]["code"] == "NOT_FOUND"
    assert row_ids(client, table_id) == {first["id"], kept["id"], unnamed["id"]}

    # Un altro utente non può rimuovere righe dei corsi altrui.
    client.cookies.set(COOKIE, other)
    assert delete_students(client, table_id, [first["id"]])["errors"][0]["code"] == "NOT_FOUND"
    assert delete_students(client, table_id, [])["errors"][0]["code"] == "NOT_FOUND"
    client.cookies.set(COOKIE, owner)

    result = delete_students(client, table_id, [first["id"], unnamed["id"], first["id"]])
    assert result["errors"] == [] and set(result["deletedIds"]) == {first["id"], unnamed["id"]}
    assert row_ids(client, table_id) == {kept["id"]}
    assert count_cells(client, grade["id"]) == 0
    # La stessa matricola nelle altre tabelle del corso resta invariata.
    assert row_ids(client, second) == {same_student["id"]}
    # La matricola si può aggiungere di nuovo come nuova riga.
    assert add_student(client, table_id, "001")["id"] != first["id"]
