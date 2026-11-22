"""Interpretazione e formattazione dei valori delle celle."""
import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation

# Tipi delle colonne. Le colonne anagrafiche leggono e scrivono i campi della riga.
BUILTIN_KINDS = ("matricola", "last_name", "first_name", "email", "dsa", "dis")
BUILTIN_NAMES = {
    "matricola": "Matricola", "last_name": "Cognome", "first_name": "Nome",
    "email": "Email", "dsa": "DSA", "dis": "DIS",
}
CUSTOM_KINDS = ("flag", "grade", "text")
MODIFIERS = ("+", "-", "L")
TEXT_MAX = 1000
GRADE_LIMIT = Decimal("1000000")
GRADE_DECIMALS = 4

TRUE_WORDS = {"sì", "si", "s", "x", "1", "true", "y", "yes", "on"}
FALSE_WORDS = {"no", "n", "0", "false", "off"}


class CellError(ValueError):
    pass


@dataclass
class GradeScale:
    maximum: Decimal | None = None
    allow_above_maximum: bool = False
    allow_negative: bool = False
    pass_threshold: Decimal | None = None
    modifiers: list[str] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "maximum": None if self.maximum is None else str(self.maximum),
            "allow_above_maximum": self.allow_above_maximum,
            "allow_negative": self.allow_negative,
            "pass_threshold": None if self.pass_threshold is None else str(self.pass_threshold),
            "modifiers": list(self.modifiers),
        }

    @classmethod
    def from_json(cls, data: dict | None) -> "GradeScale":
        data = data or {}
        return cls(
            maximum=Decimal(data["maximum"]) if data.get("maximum") is not None else None,
            allow_above_maximum=bool(data.get("allow_above_maximum")),
            allow_negative=bool(data.get("allow_negative")),
            pass_threshold=Decimal(data["pass_threshold"]) if data.get("pass_threshold") is not None else None,
            modifiers=[modifier for modifier in MODIFIERS if modifier in (data.get("modifiers") or [])],
        )


def parse_decimal(value: str, what: str = "Il valore") -> Decimal:
    cleaned = value.strip().replace(",", ".")
    if not re.fullmatch(r"-?[0-9]+(\.[0-9]+)?", cleaned):
        raise CellError(f"{what} non è un numero valido.")
    try:
        number = Decimal(cleaned)
    except InvalidOperation:
        raise CellError(f"{what} non è un numero valido.") from None
    if abs(number) >= GRADE_LIMIT:
        raise CellError(f"{what} è troppo grande.")
    if -number.as_tuple().exponent > GRADE_DECIMALS:
        raise CellError(f"{what} ammette al massimo {GRADE_DECIMALS} cifre decimali.")
    return number


GRADE_PATTERN = re.compile(r"(-?[0-9]+(?:[.,][0-9]+)?)\s*(\+|-|l|e\s+lode)?", re.IGNORECASE)


def parse_grade(value: str, scale: GradeScale) -> tuple[Decimal, str] | None:
    """Restituisce valore base e modificatore, oppure None per la cella vuota."""
    value = value.strip()
    if not value:
        return None
    match = GRADE_PATTERN.fullmatch(value)
    if not match:
        raise CellError("Voto non valido. Esempi: 7, 7,5, 7+, 8-, 30L.")
    base = parse_decimal(match[1], "Il voto")
    raw_modifier = (match[2] or "").lower()
    modifier = "L" if raw_modifier in ("l",) or raw_modifier.startswith("e") else raw_modifier
    if modifier:
        if modifier not in scale.modifiers:
            raise CellError(f"Il modificatore «{modifier}» non è ammesso in questa colonna.")
        if base != base.to_integral_value():
            raise CellError("Un modificatore non può seguire un voto decimale.")
    if base < 0 and not scale.allow_negative:
        raise CellError("I valori negativi non sono ammessi in questa colonna.")
    if scale.maximum is not None and base > scale.maximum and not scale.allow_above_maximum:
        raise CellError(f"Il valore supera il massimo della colonna ({format_decimal(scale.maximum)}).")
    return base, modifier


def grade_violation(base: Decimal, modifier: str, scale: GradeScale) -> str | None:
    """Verifica un voto già memorizzato rispetto a una nuova configurazione."""
    try:
        parse_grade(format_grade(base, modifier).replace(",", "."), scale)
    except CellError as error:
        return str(error)
    return None


def parse_flag(value: str, allow_empty: bool = True) -> bool | None:
    word = value.strip().lower()
    if not word:
        if allow_empty:
            return None
        return False
    if word in TRUE_WORDS:
        return True
    if word in FALSE_WORDS:
        return False
    raise CellError("Valore non valido. Usare sì/no (anche s/n, x, 1/0) o lasciare vuoto.")


def parse_text(value: str, maximum: int = TEXT_MAX) -> str | None:
    value = value.strip()
    if len(value) > maximum:
        raise CellError(f"Inserire al massimo {maximum} caratteri.")
    return value or None


def format_decimal(number: Decimal) -> str:
    text = format(number.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text.replace(".", ",")


def format_grade(base: Decimal, modifier: str) -> str:
    return format_decimal(base) + modifier


def format_flag(value: bool) -> str:
    return "sì" if value else "no"
