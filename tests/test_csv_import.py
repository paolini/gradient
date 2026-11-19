import asyncio
import base64
from pathlib import Path

import pytest

from gradient.csv_import import MAX_BYTES, Mapping, preview_csv
from gradient.schema import schema


def encoded(value: str, encoding="utf-8") -> str:
    return base64.b64encode(value.encode(encoding)).decode("ascii")


def test_reviewed_unipi_example():
    data = base64.b64encode(Path("examples/unipi-iscritti.csv").read_bytes()).decode("ascii")
    result = preview_csv(data)
    assert result.errors == []
    assert result.header_row == 10
    assert result.delimiter == ";"
    assert result.total_rows == len(result.rows) == 14
    assert result.mapping == Mapping(matricola=0, last_name=1, first_name=2, email=3, dsa=9, dis=11)
    assert all(row.matricola.startswith("FITTIZIA") for row in result.rows)
    assert all(row.email.endswith("@example.invalid") for row in result.rows)
    assert sum(row.dsa for row in result.rows) == 2
    assert all(not row.dis for row in result.rows)


def test_quoted_fields_multiline_and_windows_encoding():
    result = preview_csv(encoded('sep=;\r\nInformazioni;Prova\r\nMatricola;Cognome;Nome;Email;Documentazione studente\r\n00123;"Dè Rossi; Bianchi";Anna;;DSA/DIS\r\n;Verdi;"Maria\r\nLuisa";;\r\n', "cp1252"))
    assert not result.errors
    assert result.rows[0].matricola == "00123"
    assert result.rows[0].last_name == "Dè Rossi; Bianchi"
    assert result.rows[0].dsa and result.rows[0].dis
    assert result.rows[1].first_name == "Maria\r\nLuisa"
    assert result.rows[1].matricola is None
    assert len(result.warnings) == 1


def test_duplicate_matricola_blocks_import_but_missing_values_are_allowed():
    result = preview_csv(encoded('Matricola,Nome,Cognome\n00123,Ada,Rossi\n 00123 ,Luca,Verdi\n,Anna,Bianchi\n,Marco,Neri\n'))
    assert len(result.errors) == 1
    assert result.errors[0].field == "matricola"
    assert result.errors[0].row == 3
    assert "riga 2" in result.errors[0].message
    assert len(result.warnings) == 2


def test_mapping_and_explicit_independent_flags():
    value = encoded("ID;Family;Given;SupportA;SupportB\n0001;Rossi;Anna;sì;no\n0002;Verdi;Luca;;x\n")
    result = preview_csv(value, Mapping(matricola=0, last_name=1, first_name=2, dsa=3, dis=4))
    assert not result.errors
    assert [(row.dsa, row.dis) for row in result.rows] == [(True, False), (False, True)]
    assert result.rows[0].matricola == "0001"


@pytest.mark.parametrize("value, mapping", [
    (encoded("Matricola;Nome\n1;Anna;extra\n"), None),
    (encoded("Matricola;Nome\n"), None),
    (encoded("Matricola;Nome\n;\n"), None),
    (encoded("Matricola;Nome\n1;" + "x" * 201), None),
    (encoded("Matricola;Nome;DSA\n1;Anna;sconosciuto"), None),
    (encoded("Matricola;Nome\n1;Anna"), Mapping(matricola=-1)),
    (encoded("Matricola;Nome\n1;Anna"), Mapping(matricola=10)),
    (encoded("Matricola;Nome\n1;Anna"), Mapping(matricola=0, first_name=0)),
    ("invalid-base64!", None),
    (encoded(""), None),
    (encoded("x" * (MAX_BYTES + 1)), None),
])
def test_invalid_csv_returns_errors_without_echoing_data(value, mapping):
    assert preview_csv(value, mapping).errors


def test_preview_does_not_require_database_and_import_revalidates():
    async def run():
        content = encoded("Matricola;Nome;Cognome\n1;Ada;Rossi\n1;Anna;Verdi\n")
        result = await schema.execute('''mutation($input: PreviewCsvInput!) {
          previewCsv(input: $input) { totalRows errors { row field } }
        }''', variable_values={"input": {"contentBase64": content}}, context_value={})
        assert not result.errors
        assert result.data["previewCsv"]["totalRows"] == 2
        assert result.data["previewCsv"]["errors"] == [{"row": 3, "field": "matricola"}]
        # There is deliberately no store: invalid input must never reach persistence.
        result = await schema.execute('''mutation($input: ImportCsvInput!) {
          importCsvTable(input: $input) { table { id } importedCount errors { row field } }
        }''', variable_values={"input": {
            "table": {"courseId": "00000000-0000-0000-0000-000000000001", "name": "Compitino"},
            "file": {"contentBase64": content},
        }}, context_value={})
        assert not result.errors
        assert result.data["importCsvTable"] == {
            "table": None, "importedCount": 0, "errors": [{"row": 3, "field": "matricola"}],
        }
    asyncio.run(run())


def test_preview_is_limited_but_all_rows_are_validated():
    content = "Matricola;Nome\n" + "\n".join(f"{i};Anna" for i in range(101)) + "\n0;Duplicato"
    result = asyncio.run(schema.execute('''mutation($input: PreviewCsvInput!) {
      previewCsv(input: $input) { totalRows rows { matricola } errors { row } }
    }''', variable_values={"input": {"contentBase64": encoded(content)}}, context_value={}))
    assert not result.errors
    preview = result.data["previewCsv"]
    assert preview["totalRows"] == 102
    assert len(preview["rows"]) == 100
    assert preview["errors"] == [{"row": 103}]
