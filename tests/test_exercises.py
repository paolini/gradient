import os
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select

from gradient.app import create_app
from gradient.auth import COOKIE, save_login
from gradient.models import LoginSession, User

pytestmark = [
    pytest.mark.database,
    pytest.mark.skipif(os.getenv("GRADIENT_TEST_DB") != "1", reason="Usare make test-db per abilitare MariaDB"),
]

EXERCISE = "id title variants { text options correct }"
SAVE = f"mutation($input: SaveExerciseInput!) {{ saveExercise(input: $input) {{ exercise {{ {EXERCISE} }} errors {{ field code message }} }} }}"
LIST = "query($s: String) { exercises(search: $s) { id title variantCount } }"


def gql(client, document, **variables):
    response = client.post("/graphql", json={"query": document, "variables": variables})
    assert response.status_code == 200, response.text
    body = response.json()
    assert "errors" not in body, body
    return body["data"]


@pytest.fixture
def users():
    subject = str(uuid4())
    app = create_app()
    with TestClient(app, headers={"X-Gradient-Request": "1"}) as client:
        db = app.state.store.database

        async def create(provider):
            return await save_login(db, provider, {"sub": subject, "email": "test@example.invalid", "email_verified": True})

        async def cleanup():
            async with db.sessions.begin() as session:
                ids = select(User.id).where(User.subject == subject)
                await session.execute(delete(LoginSession).where(LoginSession.user_id.in_(ids)))
                # Gli esercizi e le loro varianti sono eliminati in cascata con l'utente.
                await session.execute(delete(User).where(User.subject == subject))
        try:
            yield client, client.portal.call(create, "google"), client.portal.call(create, "unipi")
        finally:
            client.portal.call(cleanup)


def variant(text="Quanto vale $1 + 1$?", options=("$1$", "$2$", "$3$"), correct=1):
    return {"text": text, "options": list(options), "correct": correct}


def test_create_read_update_delete(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    created = gql(client, SAVE, input={"title": "  Somma  ", "variants": [variant(), variant("Quanto vale $2 + 2$?", ("$4$", "$5$"), 0)]})["saveExercise"]
    assert created["errors"] == []
    exercise = created["exercise"]
    assert exercise["title"] == "Somma"
    assert [item["correct"] for item in exercise["variants"]] == [1, 0]
    read = gql(client, f"query($id: ID!) {{ exercise(id: $id) {{ {EXERCISE} }} }}", id=exercise["id"])["exercise"]
    assert read == exercise
    updated = gql(client, SAVE, input={"id": exercise["id"], "title": "Somma facile", "variants": [variant(" Testo  ", (" a ", "b"), 0)]})["saveExercise"]["exercise"]
    assert updated["variants"] == [{"text": "Testo", "options": ["a", "b"], "correct": 0}]
    assert gql(client, LIST, s=None)["exercises"] == [{"id": exercise["id"], "title": "Somma facile", "variantCount": 1}]
    removed = gql(client, "mutation($id: ID!) { deleteExercise(id: $id) { errors { code } } }", id=exercise["id"])["deleteExercise"]
    assert removed["errors"] == []
    assert gql(client, LIST, s=None)["exercises"] == []


@pytest.mark.parametrize("variants, field", [
    ([], "variants"),
    ([variant(text="   ")], "variants.0.text"),
    ([variant(options=("solo una",), correct=0)], "variants.0.options"),
    ([variant(options=("a", " "), correct=0)], "variants.0.options.1"),
    ([variant(correct=3)], "variants.0.correct"),
    ([variant(), variant(correct=-1)], "variants.1.correct"),
])
def test_validation(users, variants, field):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    result = gql(client, SAVE, input={"title": "Prova", "variants": variants})["saveExercise"]
    assert result["exercise"] is None
    assert result["errors"][0]["field"] == field


def test_search_title_text_and_options(users):
    client, owner, _ = users
    client.cookies.set(COOKIE, owner)
    gql(client, SAVE, input={"title": "Limiti notevoli", "variants": [variant("Calcolare il limite", ("$0$", "$1$"), 1)]})
    gql(client, SAVE, input={"title": "Derivate", "variants": [variant("Derivare $x^2$", ("$2x$", "$x$"), 0)]})
    gql(client, SAVE, input={"title": "Percentuali", "variants": [variant("Il 50% di 10", ("5", "50_%"), 0)]})
    titles = lambda search: sorted(item["title"] for item in gql(client, LIST, s=search)["exercises"])
    assert titles("limit") == ["Limiti notevoli"]
    assert titles("2x") == ["Derivate"]
    assert titles("calcolare 1") == ["Limiti notevoli"]
    assert titles("%") == ["Percentuali"]
    assert titles("_") == ["Percentuali"]
    assert titles("assente") == []
    gql(client, SAVE, input={"title": "Accenti", "variants": [variant("Scegli", ("perché sì", "città"), 0)]})
    assert titles("perché") == ["Accenti"]
    assert titles("città") == ["Accenti"]
    assert len(titles("  ")) == 4


def test_archive_is_private(users):
    client, owner, other = users
    client.cookies.set(COOKIE, owner)
    exercise = gql(client, SAVE, input={"title": "Privato", "variants": [variant()]})["saveExercise"]["exercise"]
    client.cookies.set(COOKIE, other)
    assert gql(client, LIST, s=None)["exercises"] == []
    response = client.post("/graphql", json={"query": "query($id: ID!) { exercise(id: $id) { id } }", "variables": {"id": exercise["id"]}}).json()
    assert response["errors"][0]["extensions"]["code"] == "NOT_FOUND"
    assert gql(client, SAVE, input={"id": exercise["id"], "title": "Rubato", "variants": [variant()]})["saveExercise"]["errors"][0]["code"] == "NOT_FOUND"
    assert gql(client, "mutation($id: ID!) { deleteExercise(id: $id) { errors { code } } }", id=exercise["id"])["deleteExercise"]["errors"][0]["code"] == "NOT_FOUND"
    client.cookies.set(COOKIE, owner)
    assert gql(client, LIST, s=None)["exercises"][0]["title"] == "Privato"
