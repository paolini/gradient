"""Compilazione controllata dei testi Typst degli esercizi."""
import multiprocessing
import os
import re
import tempfile
import threading
from concurrent.futures import ProcessPoolExecutor, TimeoutError as FutureTimeout
from concurrent.futures.process import BrokenProcessPool
from dataclasses import asdict, dataclass
from pathlib import Path

PACKAGES = Path(__file__).parent / "typst_packages"
# Pacchetti importabili nei testi; le loro dipendenze sono nella stessa cartella.
ALLOWED_PACKAGES = {"cetz": "0.5.3", "cetz-plot": "0.1.4"}
IMPORT_PATTERN = re.compile(r'"@([^/"\s]+)/([^:"\s]+)(?::([^"\s]*))?"')
LOCATION_PATTERN = re.compile(r"(\w+)\.typ:(\d+):(\d+)")
MAX_PAGES = 5
LETTERS = "ABCDEFGHIJ"

TIMEOUT_SECONDS = 20
# Il processo occupa 50-90 MB: si chiude dopo un periodo di inattività e si ricrea quando serve.
IDLE_SECONDS = 300
# Proxy inesistente nel solo processo di compilazione: anche gli import costruiti
# dinamicamente, che sfuggono al controllo testuale, non possono scaricare pacchetti.
BLOCKED_PROXY = "http://127.0.0.1:9"


@dataclass
class TypstIssue:
    field: str  # "text" oppure "options.N"
    message: str
    line: int | None = None
    column: int | None = None


class TypstRenderError(Exception):
    def __init__(self, issues: list[TypstIssue]):
        super().__init__("; ".join(issue.message for issue in issues))
        self.issues = issues


def check_imports(source: str, field: str) -> list[TypstIssue]:
    """Rifiuta prima della compilazione i pacchetti non approvati, che Typst scaricherebbe."""
    issues = []
    for namespace, name, version in IMPORT_PATTERN.findall(source):
        allowed = ALLOWED_PACKAGES.get(name)
        if namespace != "preview" or allowed is None:
            issues.append(TypstIssue(field, f"Pacchetto non ammesso: @{namespace}/{name}. Pacchetti disponibili: "
                                     + ", ".join(f"@preview/{key}:{value}" for key, value in ALLOWED_PACKAGES.items()) + "."))
        elif version != allowed:
            issues.append(TypstIssue(field, f"Versione non disponibile: usare @preview/{name}:{allowed}."))
    return issues


PREVIEW_TEMPLATE = """#set page(width: 12cm, height: auto, margin: (x: 5mm, y: 4mm))
#set text(lang: "it", size: 11pt)
#set par(justify: true)
#include "text.typ"
#v(0.6em)
#grid(columns: (auto, auto, 1fr), column-gutter: 0.5em, row-gutter: 0.65em, align: (left + top, left + top, left + top),
{options})
"""


def _field_name(file: str) -> str:
    return "text" if file == "text" else f"options.{int(file[1:])}"


def _issues_from_error(error) -> list[TypstIssue]:
    diagnostic = str(getattr(error, "diagnostic", "") or "")
    locations = [match for match in LOCATION_PATTERN.findall(diagnostic) if match[0] == "text" or re.fullmatch(r"o\d+", match[0])]
    message = str(getattr(error, "message", "") or error).strip() or "Errore di compilazione Typst."
    if "download package" in message:
        message = "Pacchetto non disponibile. Pacchetti ammessi: " + ", ".join(f"@preview/{key}:{value}" for key, value in ALLOWED_PACKAGES.items()) + "."
    if locations:
        file, line, column = locations[0]
        return [TypstIssue(_field_name(file), message, int(line), int(column))]
    return [TypstIssue("text", message)]


def _compile_variant(text: str, options: list[str], correct: int | None) -> tuple[str, list]:
    """Eseguita nel processo di compilazione; restituisce dati serializzabili, non eccezioni."""
    import typst

    cells = []
    for index in range(len(options)):
        mark = '[#text(fill: rgb("#21634f"))[✓]]' if index == correct else "[]"
        cells.append(f'  {mark}, [*{LETTERS[index]})*], [#include "o{index}.typ"],')
    with tempfile.TemporaryDirectory(prefix="gradient-typst-") as directory:
        root = Path(directory)
        # Ogni frammento in un file separato: un errore resta confinato e indica il campo.
        (root / "text.typ").write_text(text, encoding="utf-8")
        for index, option in enumerate(options):
            (root / f"o{index}.typ").write_text(option, encoding="utf-8")
        main = root / "main.typ"
        main.write_text(PREVIEW_TEMPLATE.replace("{options}", "\n".join(cells)), encoding="utf-8")
        local = root / "local-packages"
        local.mkdir()
        try:
            pages = typst.compile(
                str(main), root=str(root), format="svg", ignore_system_fonts=True,
                package_path=str(local), package_cache_path=str(PACKAGES),
            )
        except typst.TypstError as error:
            return "error", [asdict(issue) for issue in _issues_from_error(error)]
    if isinstance(pages, bytes):
        pages = [pages]
    return "ok", [page.decode("utf-8") for page in pages[:MAX_PAGES]]


def _isolate_network():
    for name in ("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "https_proxy", "http_proxy", "all_proxy"):
        os.environ[name] = BLOCKED_PROXY
    for name in ("NO_PROXY", "no_proxy"):
        os.environ.pop(name, None)


class TypstWorker:
    """Un processo di compilazione alla volta: contiene la RAM e si può terminare se si blocca."""

    def __init__(self, timeout: float = TIMEOUT_SECONDS, idle: float = IDLE_SECONDS):
        self.timeout = timeout
        self.idle = idle
        self._lock = threading.Lock()
        self._executor: ProcessPoolExecutor | None = None
        self._idle_timer: threading.Timer | None = None

    def _get_executor(self) -> ProcessPoolExecutor:
        if self._executor is None:
            self._executor = ProcessPoolExecutor(
                max_workers=1, mp_context=multiprocessing.get_context("spawn"),
                initializer=_isolate_network, max_tasks_per_child=50,
            )
        return self._executor

    def _reset(self):
        executor, self._executor = self._executor, None
        if executor is None:
            return
        for process in list(getattr(executor, "_processes", {}).values()):
            process.kill()
        executor.shutdown(wait=False, cancel_futures=True)

    def _schedule_idle_close(self):
        if self._idle_timer is not None:
            self._idle_timer.cancel()
        self._idle_timer = threading.Timer(self.idle, self.close)
        self._idle_timer.daemon = True
        self._idle_timer.start()

    def run(self, function, *args):
        with self._lock:
            self._schedule_idle_close()
            future = self._get_executor().submit(function, *args)
            try:
                return future.result(timeout=self.timeout)
            except FutureTimeout:
                self._reset()
                raise TypstRenderError([TypstIssue("text", f"La compilazione ha superato {self.timeout:g} secondi ed è stata interrotta.")]) from None
            except BrokenProcessPool:
                self._reset()
                raise TypstRenderError([TypstIssue("text", "La compilazione Typst è terminata in modo anomalo.")]) from None

    @property
    def running(self) -> bool:
        return self._executor is not None

    def close(self):
        with self._lock:
            if self._idle_timer is not None:
                self._idle_timer.cancel()
                self._idle_timer = None
            if self._executor is not None:
                self._executor.shutdown(wait=True, cancel_futures=True)
                self._executor = None


worker = TypstWorker()


def render_variant(text: str, options: list[str], correct: int | None) -> list[str]:
    """Compila testo e opzioni di una variante in SVG, una stringa per pagina."""
    issues = check_imports(text, "text")
    for index, option in enumerate(options):
        issues += check_imports(option, f"options.{index}")
    if issues:
        raise TypstRenderError(issues)
    status, result = worker.run(_compile_variant, text, options, correct)
    if status == "error":
        raise TypstRenderError([TypstIssue(**issue) for issue in result])
    return result
