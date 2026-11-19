"""Lettura e validazione CSV senza persistenza o logging dei dati caricati."""
import base64
import binascii
import csv
import io
import re
import unicodedata
from dataclasses import dataclass, field

MAX_BYTES = 2 * 1024 * 1024
MAX_ROWS = 5000
FIELDS = ("matricola", "last_name", "first_name", "email", "dsa", "dis")


def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).strip().casefold().split())


@dataclass
class Mapping:
    matricola: int | None = None
    last_name: int | None = None
    first_name: int | None = None
    email: int | None = None
    dsa: int | None = None
    dis: int | None = None


@dataclass
class Issue:
    row: int | None
    field: str
    message: str


@dataclass
class ImportedStudent:
    row_number: int
    matricola: str | None
    last_name: str
    first_name: str
    email: str | None
    dsa: bool
    dis: bool


@dataclass
class Preview:
    headers: list[str] = field(default_factory=list)
    mapping: Mapping = field(default_factory=Mapping)
    rows: list[ImportedStudent] = field(default_factory=list)
    errors: list[Issue] = field(default_factory=list)
    warnings: list[Issue] = field(default_factory=list)
    delimiter: str = ";"
    header_row: int = 0
    total_rows: int = 0


def parse_flag(value: str, flag: str) -> bool:
    value = normalized(value)
    if value in ("", "0", "false", "no", "off"):
        return False
    if value in ("1", "true", "sì", "si", "yes", "x", "on"):
        return True
    tokens = set(re.findall(r"\w+", value))
    if tokens and tokens <= {"dsa", "dis"}:
        return flag in tokens
    raise ValueError("Flag non riconosciuto: usare vuoto/no, sì/x oppure le sigle DSA e DIS.")


def preview_csv(content_base64: str, mapping: Mapping | None = None) -> Preview:
    result = Preview()
    def fail(message):
        result.errors.append(Issue(None, "file", message))
        return result

    if len(content_base64) > ((MAX_BYTES + 2) // 3) * 4:
        return fail("Il CSV deve essere al massimo di 2 MiB.")
    try:
        raw = base64.b64decode(content_base64, validate=True)
    except (ValueError, binascii.Error):
        return fail("File non valido.")
    if not raw or len(raw) > MAX_BYTES:
        return fail("Selezionare un CSV non vuoto, al massimo di 2 MiB.")
    for encoding in ("utf-8-sig", "cp1252"):
        try:
            content = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        return fail("Codifica del file non riconosciuta. Usare UTF-8 o Windows-1252.")
    if "\x00" in content:
        return fail("Il file contiene dati binari; selezionare un CSV.")
    stream = io.StringIO(content, newline="")
    first_line = stream.readline()
    offset = 0
    if first_line.strip().lower().startswith("sep="):
        delimiter = first_line.strip()[4:]
        offset = 1
        if delimiter not in (";", ",", "\t", "|"):
            return fail("Separatore CSV non supportato.")
    else:
        stream.seek(0)
        try:
            delimiter = csv.Sniffer().sniff(content[:8192], delimiters=";,\t|").delimiter
        except csv.Error:
            # Le righe introduttive UniPi non hanno tutte lo stesso numero di celle.
            delimiter = max((";", ",", "\t", "|"), key=content.count)
    result.delimiter = delimiter
    reader = csv.reader(stream, delimiter=delimiter, strict=True)
    records = []
    try:
        for row in reader:
            if any(cell.strip() for cell in row):
                records.append((reader.line_num + offset, row))
            if len(records) > MAX_ROWS + 101:
                return fail("Il CSV contiene troppe righe (massimo 5000 studenti).")
    except csv.Error:
        return fail(f"CSV non valido in prossimità della riga {reader.line_num + offset}.")
    if not records:
        return fail("Il CSV non contiene intestazioni.")
    header_index = next((i for i, (_, row) in enumerate(records[:100])
        if "matricola" in [normalized(cell) for cell in row]
        or {"nome", "cognome"} <= {normalized(cell) for cell in row}), 0)
    result.header_row, result.headers = records[header_index]
    if len(result.headers) > 100:
        return fail("Sono supportate al massimo 100 colonne.")
    names = [normalized(cell) for cell in result.headers]
    def find(*aliases):
        return next((i for i, name in enumerate(names) if name in aliases), None)
    def find_flag(flag):
        direct = find(flag)
        if direct is not None:
            return direct
        for alias in ("documentazione studente", "note di sistema"):
            index = find(alias)
            if index is not None and any(
                index < len(row) and re.search(r"\b" + flag + r"\b", row[index], re.IGNORECASE)
                for _, row in records[header_index + 1:]
            ):
                return index
        return find("documentazione studente")
    result.mapping = mapping or Mapping(
        matricola=find("matricola"), last_name=find("cognome", "last name"),
        first_name=find("nome", "first name"), email=find("email", "e-mail"),
        dsa=find_flag("dsa"), dis=find_flag("dis"),
    )
    for name in FIELDS:
        index = getattr(result.mapping, name)
        if index is not None and (isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < len(result.headers)):
            result.errors.append(Issue(None, name, "Colonna selezionata non valida."))
    if not any(getattr(result.mapping, name) is not None for name in ("matricola", "last_name", "first_name")):
        result.errors.append(Issue(None, "mapping", "Associare almeno matricola, cognome o nome."))
    identities = [getattr(result.mapping, name) for name in ("matricola", "last_name", "first_name", "email") if getattr(result.mapping, name) is not None]
    if len(identities) != len(set(identities)):
        result.errors.append(Issue(None, "mapping", "Matricola, nome, cognome ed email devono usare colonne distinte."))
    if result.errors:
        return result
    data = records[header_index + 1:]
    result.total_rows = len(data)
    if not data:
        return fail("Il CSV non contiene studenti.")
    if len(data) > MAX_ROWS:
        return fail("Sono supportati al massimo 5000 studenti.")
    seen = {}
    for number, row in data:
        if len(row) != len(result.headers):
            result.errors.append(Issue(number, "file", "Numero di celle diverso dalle intestazioni."))
            continue
        values = {name: row[index].strip() if (index := getattr(result.mapping, name)) is not None else "" for name in FIELDS}
        for name in ("matricola", "last_name", "first_name", "email"):
            maximum = 64 if name == "matricola" else 320 if name == "email" else 200
            if len(values[name]) > maximum:
                result.errors.append(Issue(number, name, f"Inserire al massimo {maximum} caratteri."))
        if not any(values[name] for name in ("matricola", "last_name", "first_name")):
            result.errors.append(Issue(number, "student", "Riga senza matricola, nome o cognome."))
        if values["matricola"]:
            if values["matricola"] in seen:
                result.errors.append(Issue(number, "matricola", f"Matricola duplicata rispetto alla riga {seen[values['matricola']]}."))
            seen[values["matricola"]] = number
        else:
            result.warnings.append(Issue(number, "matricola", "Matricola mancante: la riga resterà indipendente dalle altre tabelle."))
        flags = {}
        for name in ("dsa", "dis"):
            try:
                flags[name] = parse_flag(values[name], name)
            except ValueError as error:
                result.errors.append(Issue(number, name, str(error)))
                flags[name] = False
        result.rows.append(ImportedStudent(number, values["matricola"] or None, values["last_name"], values["first_name"], values["email"] or None, flags["dsa"], flags["dis"]))
    return result
