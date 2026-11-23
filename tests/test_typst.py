import asyncio

import pytest

from gradient.schema import schema
import time

from gradient.typst_render import ALLOWED_PACKAGES, PACKAGES, TypstRenderError, TypstWorker, _compile_variant, check_imports, render_variant

PREVIEW = '''query($v: ExerciseVariantInput!) { exercisePreview(variant: $v) { pages errors { field message line column } } }'''


def preview(text, options, correct=0):
    result = asyncio.run(schema.execute(PREVIEW, variable_values={"v": {"text": text, "options": options, "correct": correct}}, context_value={}))
    assert not result.errors
    return result.data["exercisePreview"]


def test_preview_renders_svg():
    result = preview("Quanto vale $lim_(x -> 0) sin(x) / x$?", ["$0$", "$1$", "Non esiste"], 1)
    assert result["errors"] == []
    assert len(result["pages"]) == 1 and result["pages"][0].startswith("<svg")


@pytest.mark.parametrize("text, options, field, line", [
    ("Domanda #foo(", ["a", "b"], "text", 1),
    ("Domanda", ["a", "b\n#bar("], "options.1", 2),
])
def test_errors_point_to_the_field(text, options, field, line):
    result = preview(text, options)
    assert result["pages"] == []
    assert result["errors"][0]["field"] == field
    assert result["errors"][0]["line"] == line


def test_only_approved_packages_and_versions():
    assert not check_imports('#import "@preview/cetz:0.5.3": canvas', "text")
    assert "non ammesso" in check_imports('#import "@preview/tablex:0.0.8"', "text")[0].message
    assert "Versione" in check_imports('#import "@preview/cetz:0.4.0"', "text")[0].message
    assert check_imports('#import "@local/mio:1.0.0"', "options.0")[0].field == "options.0"
    for name, version in ALLOWED_PACKAGES.items():
        assert (PACKAGES / "preview" / name / version / "typst.toml").exists()


def test_approved_package_compiles_offline():
    pages = render_variant('#import "@preview/cetz:0.5.3"\n#cetz.canvas({ import cetz.draw: *; circle((0, 0)) })', ["a", "b"], 0)
    assert pages[0].startswith("<svg")


def test_dynamic_imports_cannot_download(tmp_path):
    # Il controllo testuale non vede questo import: lo blocca il processo isolato dalla rete.
    with pytest.raises(TypstRenderError) as error:
        render_variant('#import ("@pre" + "view/tablex:0.0.8"): *', ["a", "b"], 0)
    assert "non disponibile" in error.value.issues[0].message
    assert not (PACKAGES / "preview" / "tablex").exists()


def test_files_outside_the_document_are_not_readable():
    with pytest.raises(TypstRenderError):
        render_variant('#read("/etc/passwd")', ["a", "b"], 0)


def test_worker_closes_when_idle():
    worker = TypstWorker(idle=0.3)
    try:
        assert worker.run(_compile_variant, "ciao", ["a", "b"], 0)[0] == "ok"
        assert worker.running
        time.sleep(1)
        assert not worker.running
        # Si ricrea alla compilazione successiva.
        assert worker.run(_compile_variant, "di nuovo", ["a", "b"], 0)[0] == "ok"
    finally:
        worker.close()
