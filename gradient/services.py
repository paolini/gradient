import hashlib
import re
import secrets
from datetime import date as DateValue
from uuid import UUID

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, exists, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import aliased

from gradient.cells import (
    BUILTIN_KINDS, BUILTIN_NAMES, CUSTOM_KINDS, CellError, GradeScale,
    grade_violation, parse_decimal, parse_flag, parse_grade, parse_text,
)
from gradient.database import Database
from gradient.models import ApiToken, Cell, Course, CourseTable, Exercise, ExerciseVariant, StudentRow, TableColumn, TableView, new_id
from gradient.csv_import import Preview

DEFAULT_SORT = [{"column": "last_name", "descending": False}, {"column": "first_name", "descending": False}]


DEFAULT_VIEW_NAME = "Predefinita"
MAX_VARIANTS = 20
MIN_OPTIONS, MAX_OPTIONS = 2, 10
MAX_TYPST_TEXT = 20000
MAX_TYPST_OPTION = 5000
MAX_TOKEN_DAYS = 3650
API_TOKEN_PREFIX = "gradient_"


@dataclass
class View:
    id: str | None  # None per la vista predefinita non ancora salvata
    name: str
    column_order: list[str]
    hidden_columns: list[str]
    row_sort: list[dict]
    active: bool


def builtin_columns(table_id: str) -> list[TableColumn]:
    return [
        TableColumn(id=new_id(), table_id=table_id, position=position, kind=kind, name=BUILTIN_NAMES[kind], config=None)
        for position, kind in enumerate(BUILTIN_KINDS)
    ]


def now() -> datetime:
    # Le colonne DateTime sono senza fuso orario: si memorizza l'ora UTC.
    return datetime.now(UTC).replace(tzinfo=None)


def digest(token: str) -> str:
    """Hash dei segreti (sessioni e token API): nel database non si salvano in chiaro."""
    return hashlib.sha256(token.encode()).hexdigest()


def duplicate_key(error: IntegrityError) -> bool:
    return bool(error.orig.args) and error.orig.args[0] == 1062


class InputError(Exception):
    def __init__(self, field: str, message: str, code: str = "INVALID_INPUT"):
        super().__init__(message)
        self.field = field
        self.code = code


def text(value: str, field: str, maximum: int = 200, required: bool = True) -> str:
    value = value.strip()
    if required and not value:
        raise InputError(field, "Il campo è obbligatorio.")
    if len(value) > maximum:
        raise InputError(field, f"Inserire al massimo {maximum} caratteri.")
    return value


def identifier(value: str, field: str) -> str:
    try:
        return str(UUID(value))
    except ValueError:
        raise InputError(field, "Identificatore non valido.") from None


class Store:
    def __init__(self, database: Database, user_id: str | None = None):
        self.database = database
        self.user_id = user_id

    async def courses(self):
        async with self.database.sessions() as session:
            return list(await session.scalars(select(Course).where(Course.owner_id == self.user_id).order_by(Course.academic_year.desc(), Course.name, Course.id)))

    async def tables(self, course_id: str):
        course_id = identifier(course_id, "courseId")
        async with self.database.sessions() as session:
            await self.require(session, Course, course_id, "courseId")
            return list(await session.scalars(select(CourseTable).where(CourseTable.course_id == course_id).order_by(CourseTable.name, CourseTable.id)))

    async def rows(self, table_id: str):
        table_id = identifier(table_id, "tableId")
        async with self.database.sessions() as session:
            await self.require(session, CourseTable, table_id, "tableId")
            rows = list(await session.scalars(select(StudentRow).where(StudentRow.table_id == table_id).order_by(StudentRow.last_name, StudentRow.first_name, StudentRow.id)))
            cells = await session.scalars(select(Cell).join(StudentRow, Cell.row_id == StudentRow.id).where(StudentRow.table_id == table_id))
            by_row: dict[str, list[Cell]] = {}
            for cell in cells:
                by_row.setdefault(cell.row_id, []).append(cell)
            return [(row, by_row.get(row.id, [])) for row in rows]

    async def columns(self, table_id: str):
        table_id = identifier(table_id, "tableId")
        async with self.database.sessions() as session:
            await self.require(session, CourseTable, table_id, "tableId")
            return await self.load_columns(session, table_id)

    async def load_columns(self, session, table_id: str) -> list[TableColumn]:
        return list(await session.scalars(select(TableColumn).where(TableColumn.table_id == table_id).order_by(TableColumn.position, TableColumn.id)))

    async def require(self, session, model, entity_id: str, field: str):
        entity = await session.get(model, entity_id)
        if entity is not None and model in (StudentRow, TableColumn):
            table = await session.get(CourseTable, entity.table_id)
            course = await session.get(Course, table.course_id) if table else None
        else:
            course = entity if model is Course else await session.get(Course, entity.course_id) if entity else None
        if course is None or course.owner_id != self.user_id:
            raise InputError(field, "Elemento non trovato.", "NOT_FOUND")
        return entity

    async def create_course(self, name: str, academic_year: str, degree_program: str | None = None):
        name = text(name, "name")
        year = academic_year.strip()
        match = re.fullmatch(r"([0-9]{4})/([0-9]{2}|[0-9]{4})", year)
        if not match:
            raise InputError("academicYear", "Usare il formato 2026/27 o 2026/2027.")
        start = int(match[1])
        if start < 1000 or start >= 9999 or match[2] not in (str(start + 1), str(start + 1)[-2:]):
            raise InputError("academicYear", "L'anno accademico deve comprendere due anni consecutivi.")
        course = Course(
            owner_id=self.user_id, name=name, academic_year=f"{start}/{start + 1}",
            degree_program=text(degree_program or "", "degreeProgram", required=False) or None,
        )
        async with self.database.sessions.begin() as session:
            session.add(course)
        return course

    async def create_table(self, course_id: str, name: str, date: DateValue | None = None, location: str | None = None):
        course_id = identifier(course_id, "courseId")
        table = CourseTable(
            id=new_id(), course_id=course_id, name=text(name, "name"), date=date,
            location=text(location or "", "location", required=False) or None,
        )
        async with self.database.sessions.begin() as session:
            await self.require(session, Course, course_id, "courseId")
            session.add(table)
            for column in builtin_columns(table.id):
                session.add(column)
        return table

    async def add_student(self, table_id: str, matricola: str | None, first_name: str, last_name: str):
        table_id = identifier(table_id, "tableId")
        row = StudentRow(
            table_id=table_id,
            matricola=text(matricola or "", "matricola", 64, required=False) or None,
            first_name=text(first_name, "firstName", required=False),
            last_name=text(last_name, "lastName", required=False),
        )
        try:
            async with self.database.sessions.begin() as session:
                await self.require(session, CourseTable, table_id, "tableId")
                session.add(row)
        except IntegrityError as error:
            if duplicate_key(error):
                raise InputError("matricola", "Matricola già presente in questa tabella.", "DUPLICATE_MATRICOLA") from None
            raise
        return row, []

    async def import_table(self, course_id: str, name: str, date: DateValue | None, location: str | None, preview: Preview):
        if preview.errors or not preview.rows:
            raise InputError("file", "Il CSV contiene errori o non contiene studenti.")
        course_id = identifier(course_id, "courseId")
        table = CourseTable(
            id=new_id(), course_id=course_id, name=text(name, "name"), date=date,
            location=text(location or "", "location", required=False) or None,
        )
        async with self.database.sessions.begin() as session:
            await self.require(session, Course, course_id, "courseId")
            session.add(table)
            await session.flush()
            session.add_all(builtin_columns(table.id))
            session.add_all([
                StudentRow(
                    table_id=table.id, matricola=row.matricola,
                    first_name=row.first_name, last_name=row.last_name,
                    email=row.email, dsa=row.dsa, dis=row.dis,
                ) for row in preview.rows
            ])
        return table

    # Colonne

    def grade_scale(self, data: dict | None) -> GradeScale:
        data = data or {}
        try:
            maximum = parse_decimal(data["maximum"], "Il massimo") if data.get("maximum") not in (None, "") else None
            threshold = parse_decimal(data["pass_threshold"], "La sufficienza") if data.get("pass_threshold") not in (None, "") else None
        except CellError as error:
            raise InputError("grade", str(error)) from None
        if maximum is not None and maximum <= 0:
            raise InputError("grade", "Il massimo deve essere maggiore di zero.")
        modifiers = data.get("modifiers") or []
        if any(modifier not in ("+", "-", "L") for modifier in modifiers):
            raise InputError("grade", "Sono ammessi soltanto i modificatori +, - e L.")
        return GradeScale(maximum, bool(data.get("allow_above_maximum")), bool(data.get("allow_negative")), threshold, list(modifiers))

    async def add_column(self, table_id: str, kind: str, name: str, grade: dict | None = None):
        table_id = identifier(table_id, "tableId")
        if kind not in CUSTOM_KINDS:
            raise InputError("kind", "Tipo di colonna non valido.")
        name = text(name, "name", 100)
        config = self.grade_scale(grade).to_json() if kind == "grade" else None
        try:
            async with self.database.sessions.begin() as session:
                await self.require(session, CourseTable, table_id, "tableId")
                last = await session.scalar(select(func.max(TableColumn.position)).where(TableColumn.table_id == table_id))
                column = TableColumn(id=new_id(), table_id=table_id, position=(last if last is not None else -1) + 1, kind=kind, name=name, config=config)
                session.add(column)
        except IntegrityError as error:
            if duplicate_key(error):
                raise InputError("name", "Esiste già una colonna con questo nome.", "DUPLICATE_NAME") from None
            raise
        return column

    async def update_column(self, column_id: str, name: str | None = None, grade: dict | None = None):
        column_id = identifier(column_id, "columnId")
        try:
            async with self.database.sessions.begin() as session:
                column = await self.require(session, TableColumn, column_id, "columnId")
                if name is not None:
                    column.name = text(name, "name", 100)
                if grade is not None:
                    if column.kind != "grade":
                        raise InputError("grade", "La scala si configura soltanto nelle colonne voto.")
                    scale = self.grade_scale(grade)
                    cells = await session.scalars(select(Cell).where(Cell.column_id == column_id))
                    invalid = [cell for cell in cells if grade_violation(cell.grade_base, cell.grade_modifier, scale)]
                    if invalid:
                        raise InputError("grade", f"{len(invalid)} voti già inseriti non rispettano la nuova configurazione.", "INVALID_EXISTING_VALUES")
                    column.config = scale.to_json()
        except IntegrityError as error:
            if duplicate_key(error):
                raise InputError("name", "Esiste già una colonna con questo nome.", "DUPLICATE_NAME") from None
            raise
        return column

    async def delete_column(self, column_id: str):
        column_id = identifier(column_id, "columnId")
        async with self.database.sessions.begin() as session:
            column = await self.require(session, TableColumn, column_id, "columnId")
            if column.kind in BUILTIN_KINDS:
                raise InputError("columnId", "Le colonne anagrafiche non possono essere eliminate; è possibile nasconderle.")
            await session.execute(delete(Cell).where(Cell.column_id == column_id))
            await session.delete(column)
        return column

    # Celle

    async def set_cell(self, row_id: str, column_id: str, value: str):
        row_id = identifier(row_id, "rowId")
        column_id = identifier(column_id, "columnId")
        try:
            async with self.database.sessions.begin() as session:
                row = await self.require(session, StudentRow, row_id, "rowId")
                column = await self.require(session, TableColumn, column_id, "columnId")
                if column.table_id != row.table_id:
                    raise InputError("columnId", "La colonna non appartiene alla tabella della riga.", "NOT_FOUND")
                try:
                    if column.kind in BUILTIN_KINDS:
                        self.set_builtin(row, column.kind, value)
                    else:
                        await self.set_custom(session, row_id, column, value)
                except CellError as error:
                    raise InputError("value", str(error)) from None
                await session.flush()
                cells = list(await session.scalars(select(Cell).where(Cell.row_id == row_id)))
        except IntegrityError as error:
            if duplicate_key(error):
                raise InputError("value", "Matricola già presente in questa tabella.", "DUPLICATE_MATRICOLA") from None
            raise
        return row, cells

    @staticmethod
    def set_builtin(row: StudentRow, kind: str, value: str):
        if kind == "matricola":
            row.matricola = parse_text(value, 64)
        elif kind in ("first_name", "last_name"):
            setattr(row, kind, parse_text(value, 200) or "")
        elif kind == "email":
            row.email = parse_text(value, 320)
        else:
            setattr(row, kind, parse_flag(value, allow_empty=False))

    @staticmethod
    async def set_custom(session, row_id: str, column: TableColumn, value: str):
        cell = await session.get(Cell, (row_id, column.id))
        values: dict = {"flag": None, "grade_base": None, "grade_modifier": "", "text_value": None}
        if column.kind == "flag":
            values["flag"] = parse_flag(value)
            empty = values["flag"] is None
        elif column.kind == "grade":
            grade = parse_grade(value, GradeScale.from_json(column.config))
            empty = grade is None
            if grade:
                values["grade_base"], values["grade_modifier"] = grade
        else:
            values["text_value"] = parse_text(value)
            empty = values["text_value"] is None
        if empty:
            # La cella vuota non esiste.
            if cell is not None:
                await session.delete(cell)
            return
        if cell is None:
            session.add(Cell(row_id=row_id, column_id=column.id, **values))
        else:
            for key, item in values.items():
                setattr(cell, key, item)

    # Viste personali

    async def views(self, table_id: str) -> list[View]:
        """Viste dell'utente; senza viste salvate ne restituisce una predefinita non memorizzata."""
        table_id = identifier(table_id, "tableId")
        async with self.database.sessions() as session:
            await self.require(session, CourseTable, table_id, "tableId")
            columns = await self.load_columns(session, table_id)
            saved = await self.saved_views(session, table_id)
            return self.effective_views(columns, saved)

    async def saved_views(self, session, table_id: str) -> list[TableView]:
        if not self.user_id:
            return []
        return list(await session.scalars(select(TableView).where(TableView.user_id == self.user_id, TableView.table_id == table_id).order_by(TableView.name, TableView.id)))

    def effective_views(self, columns: list[TableColumn], saved: list[TableView]) -> list[View]:
        if not saved:
            return [self.effective_view(columns, None, True)]
        latest = max(saved, key=lambda view: (view.last_used, view.id))
        return [self.effective_view(columns, view, view is latest) for view in saved]

    @staticmethod
    def effective_view(columns: list[TableColumn], saved: TableView | None, active: bool) -> View:
        """Unisce la vista salvata con le colonne attuali: le nuove colonne vanno in fondo."""
        ids = [column.id for column in columns]
        known = set(ids)
        order = [column_id for column_id in (saved.column_order if saved else []) if column_id in known]
        order += [column_id for column_id in ids if column_id not in order]
        hidden = [column_id for column_id in (saved.hidden_columns if saved else []) if column_id in known]
        if saved:
            sort = [item for item in saved.row_sort if item.get("column") in known]
        else:
            kinds = {column.kind: column.id for column in columns if column.kind in BUILTIN_KINDS}
            sort = [{"column": kinds[item["column"]], "descending": item["descending"]} for item in DEFAULT_SORT if item["column"] in kinds]
        return View(saved.id if saved else None, saved.name if saved else DEFAULT_VIEW_NAME, order, hidden, sort, active)

    async def save_view(self, table_id: str, view_id: str | None, name: str, column_order: list[str], hidden_columns: list[str], row_sort: list[dict]) -> View:
        """Crea (view_id assente) o aggiorna una vista e la rende quella in uso."""
        table_id = identifier(table_id, "tableId")
        if not self.user_id:
            raise InputError("tableId", "Le viste personali richiedono un utente autenticato.")
        name = text(name, "name", 100)
        column_order = [identifier(item, "columnOrder") for item in column_order]
        hidden_columns = [identifier(item, "hiddenColumns") for item in hidden_columns]
        row_sort = [{"column": identifier(item["column"], "rowSort"), "descending": bool(item["descending"])} for item in row_sort]
        try:
            async with self.database.sessions.begin() as session:
                await self.require(session, CourseTable, table_id, "tableId")
                columns = await self.load_columns(session, table_id)
                known = {column.id for column in columns}
                if set(column_order) - known or set(hidden_columns) - known or {item["column"] for item in row_sort} - known:
                    raise InputError("columnOrder", "La vista contiene colonne che non appartengono alla tabella.", "NOT_FOUND")
                if len(set(column_order)) != len(column_order):
                    raise InputError("columnOrder", "Colonne ripetute nell'ordine.")
                if view_id is None:
                    view = TableView(id=new_id(), user_id=self.user_id, table_id=table_id)
                    session.add(view)
                else:
                    view = await self.require_view(session, view_id, table_id)
                view.name, view.column_order, view.hidden_columns, view.row_sort = name, column_order, sorted(set(hidden_columns)), row_sort
                view.last_used = now()
                await session.flush()
                return self.effective_view(columns, view, True)
        except IntegrityError as error:
            if duplicate_key(error):
                raise InputError("name", "Esiste già una vista con questo nome.", "DUPLICATE_NAME") from None
            raise

    async def require_view(self, session, view_id: str, table_id: str | None = None) -> TableView:
        view = await session.get(TableView, identifier(view_id, "viewId"))
        if view is None or view.user_id != self.user_id or (table_id and view.table_id != table_id):
            raise InputError("viewId", "Vista non trovata.", "NOT_FOUND")
        await self.require(session, CourseTable, view.table_id, "viewId")
        return view

    async def select_view(self, view_id: str) -> View:
        async with self.database.sessions.begin() as session:
            view = await self.require_view(session, view_id)
            view.last_used = now()
            columns = await self.load_columns(session, view.table_id)
            return self.effective_view(columns, view, True)

    async def delete_view(self, view_id: str) -> list[View]:
        async with self.database.sessions.begin() as session:
            view = await self.require_view(session, view_id)
            table_id = view.table_id
            await session.delete(view)
            await session.flush()
            columns = await self.load_columns(session, table_id)
            return self.effective_views(columns, await self.saved_views(session, table_id))

    # Archivio degli esercizi

    def require_user(self):
        if not self.user_id:
            raise InputError("user", "L'operazione richiede un utente autenticato.", "NOT_FOUND")

    async def require_exercise(self, session, exercise_id: str) -> Exercise:
        self.require_user()
        exercise = await session.get(Exercise, identifier(exercise_id, "id"))
        if exercise is None or exercise.owner_id != self.user_id:
            raise InputError("id", "Esercizio non trovato.", "NOT_FOUND")
        return exercise

    async def exercises(self, search: str | None = None):
        self.require_user()
        query = (select(Exercise, func.count(ExerciseVariant.id))
                 .outerjoin(ExerciseVariant, ExerciseVariant.exercise_id == Exercise.id)
                 .where(Exercise.owner_id == self.user_id)
                 .group_by(Exercise.id).order_by(Exercise.updated_at.desc(), Exercise.id))
        words = (search or "").split()
        for word in words[:10]:
            pattern = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            searched = aliased(ExerciseVariant)
            in_variants = exists().where(searched.exercise_id == Exercise.id, or_(
                searched.text.like(pattern), func.json_unquote(searched.options).like(pattern)))
            query = query.where(or_(Exercise.title.like(pattern), in_variants))
        async with self.database.sessions() as session:
            return [(exercise, count) for exercise, count in await session.execute(query)]

    async def exercise(self, exercise_id: str):
        async with self.database.sessions() as session:
            exercise = await self.require_exercise(session, exercise_id)
            return exercise, await self.load_variants(session, exercise.id)

    @staticmethod
    async def load_variants(session, exercise_id: str) -> list[ExerciseVariant]:
        return list(await session.scalars(select(ExerciseVariant).where(ExerciseVariant.exercise_id == exercise_id).order_by(ExerciseVariant.position)))

    @staticmethod
    def validate_variants(variants: list[dict]) -> list[dict]:
        if not variants:
            raise InputError("variants", "Un esercizio deve avere almeno una variante.")
        if len(variants) > MAX_VARIANTS:
            raise InputError("variants", f"Al massimo {MAX_VARIANTS} varianti per esercizio.")
        cleaned = []
        for index, variant in enumerate(variants):
            prefix = f"variants.{index}"
            body = variant["text"].strip()
            if not body:
                raise InputError(f"{prefix}.text", f"Variante {index + 1}: il testo è obbligatorio.")
            if len(body) > MAX_TYPST_TEXT:
                raise InputError(f"{prefix}.text", f"Variante {index + 1}: al massimo {MAX_TYPST_TEXT} caratteri.")
            options = [option.strip() for option in variant["options"]]
            if not MIN_OPTIONS <= len(options) <= MAX_OPTIONS:
                raise InputError(f"{prefix}.options", f"Variante {index + 1}: servono da {MIN_OPTIONS} a {MAX_OPTIONS} risposte.")
            for position, option in enumerate(options):
                if not option:
                    raise InputError(f"{prefix}.options.{position}", f"Variante {index + 1}: la risposta {chr(65 + position)} è vuota.")
                if len(option) > MAX_TYPST_OPTION:
                    raise InputError(f"{prefix}.options.{position}", f"Variante {index + 1}: la risposta {chr(65 + position)} supera {MAX_TYPST_OPTION} caratteri.")
            correct = variant["correct"]
            if not isinstance(correct, int) or not 0 <= correct < len(options):
                raise InputError(f"{prefix}.correct", f"Variante {index + 1}: indicare la risposta corretta.")
            cleaned.append({"text": body, "options": options, "correct": correct})
        return cleaned

    async def save_exercise(self, exercise_id: str | None, title: str, variants: list[dict]):
        self.require_user()
        title = text(title, "title")
        cleaned = self.validate_variants(variants)
        async with self.database.sessions.begin() as session:
            if exercise_id is None:
                exercise = Exercise(id=new_id(), owner_id=self.user_id, created_at=now())
                session.add(exercise)
            else:
                exercise = await self.require_exercise(session, exercise_id)
                # Le varianti si sostituiscono in blocco: i compiti ne conservano una copia propria.
                await session.execute(delete(ExerciseVariant).where(ExerciseVariant.exercise_id == exercise.id))
            exercise.title, exercise.updated_at = title, now()
            saved = [ExerciseVariant(id=new_id(), exercise_id=exercise.id, position=index, **variant) for index, variant in enumerate(cleaned)]
            session.add_all(saved)
        return exercise, saved

    # Token API personali

    async def api_tokens(self):
        self.require_user()
        async with self.database.sessions() as session:
            return list(await session.scalars(select(ApiToken).where(ApiToken.user_id == self.user_id).order_by(ApiToken.created_at.desc(), ApiToken.id)))

    async def create_api_token(self, name: str, read_only: bool, expires_in_days: int | None):
        """Restituisce il token e il segreto in chiaro, mostrato una sola volta e mai salvato."""
        self.require_user()
        name = text(name, "name", 100)
        if expires_in_days is not None and not 1 <= expires_in_days <= MAX_TOKEN_DAYS:
            raise InputError("expiresInDays", f"La durata deve essere fra 1 e {MAX_TOKEN_DAYS} giorni.")
        secret = API_TOKEN_PREFIX + secrets.token_urlsafe(32)
        created = now()
        token = ApiToken(id=new_id(), user_id=self.user_id, name=name, read_only=read_only, created_at=created,
                         token_hash=digest(secret),
                         expires_at=created + timedelta(days=expires_in_days) if expires_in_days else None)
        async with self.database.sessions.begin() as session:
            session.add(token)
        return token, secret

    async def revoke_api_token(self, token_id: str):
        self.require_user()
        async with self.database.sessions.begin() as session:
            token = await session.get(ApiToken, identifier(token_id, "id"))
            if token is None or token.user_id != self.user_id:
                raise InputError("id", "Token non trovato.", "NOT_FOUND")
            await session.delete(token)
        return token

    async def delete_exercise(self, exercise_id: str):
        async with self.database.sessions.begin() as session:
            exercise = await self.require_exercise(session, exercise_id)
            await session.delete(exercise)
        return exercise, []
