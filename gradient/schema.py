import logging
from datetime import date as Date
from pathlib import Path

import strawberry
from anyio import to_thread
from graphql import GraphQLError
from sqlalchemy.exc import SQLAlchemyError
from strawberry.types import ExecutionContext, Info

from gradient.database import database_error_details
from gradient.csv_import import FIELDS, Mapping, preview_csv
from gradient.services import InputError

logger = logging.getLogger(__name__)


class HandledDatabaseError(GraphQLError):
    """Errore operativo già diagnosticato e registrato dal backend."""


class GradientSchema(strawberry.Schema):
    def process_errors(
        self,
        errors: list[GraphQLError],
        execution_context: ExecutionContext | None = None,
    ) -> None:
        unexpected = [
            error for error in errors
            if not isinstance(error, HandledDatabaseError)
            and not isinstance(error.original_error, HandledDatabaseError)
        ]
        if unexpected:
            super().process_errors(unexpected, execution_context)


@strawberry.type
class Course:
    id: strawberry.ID
    name: str
    academic_year: str
    degree_program: str | None


@strawberry.type
class CourseTable:
    id: strawberry.ID
    course_id: strawberry.ID
    name: str
    date: Date | None
    location: str | None


@strawberry.type
class StudentRow:
    id: strawberry.ID
    table_id: strawberry.ID
    matricola: str | None
    first_name: str
    last_name: str
    email: str | None
    dsa: bool
    dis: bool


def course_type(row):
    return Course(id=strawberry.ID(row.id), name=row.name, academic_year=row.academic_year, degree_program=row.degree_program)


def table_type(row):
    return CourseTable(id=strawberry.ID(row.id), course_id=strawberry.ID(row.course_id), name=row.name, date=row.date, location=row.location)


def student_type(row):
    return StudentRow(id=strawberry.ID(row.id), table_id=strawberry.ID(row.table_id), matricola=row.matricola, first_name=row.first_name, last_name=row.last_name, email=row.email, dsa=row.dsa, dis=row.dis)


@strawberry.type
class FieldError:
    field: str
    code: str
    message: str


@strawberry.type
class CourseResult:
    course: Course | None = None
    errors: list[FieldError] = strawberry.field(default_factory=list)


@strawberry.type
class TableResult:
    table: CourseTable | None = None
    errors: list[FieldError] = strawberry.field(default_factory=list)


@strawberry.type
class StudentResult:
    row: StudentRow | None = None
    errors: list[FieldError] = strawberry.field(default_factory=list)


@strawberry.input
class CreateCourseInput:
    name: str
    academic_year: str
    degree_program: str | None = None


@strawberry.input
class CreateTableInput:
    course_id: strawberry.ID
    name: str
    date: Date | None = None
    location: str | None = None


@strawberry.input
class AddStudentInput:
    table_id: strawberry.ID
    matricola: str | None = None
    first_name: str = ""
    last_name: str = ""


@strawberry.input
class CsvMappingInput:
    matricola: int | None = None
    last_name: int | None = None
    first_name: int | None = None
    email: int | None = None
    dsa: int | None = None
    dis: int | None = None


@strawberry.type
class CsvMapping:
    matricola: int | None
    last_name: int | None
    first_name: int | None
    email: int | None
    dsa: int | None
    dis: int | None


@strawberry.type
class CsvIssue:
    row: int | None
    field: str
    message: str


@strawberry.type
class CsvStudent:
    row_number: int
    matricola: str | None
    last_name: str
    first_name: str
    email: str | None
    dsa: bool
    dis: bool


@strawberry.type
class CsvPreview:
    headers: list[str]
    mapping: CsvMapping
    total_rows: int
    rows: list[CsvStudent]
    errors: list[CsvIssue]
    warnings: list[CsvIssue]
    delimiter: str
    header_row: int


@strawberry.input
class PreviewCsvInput:
    content_base64: str
    mapping: CsvMappingInput | None = None


@strawberry.input
class ImportCsvInput:
    table: CreateTableInput
    file: PreviewCsvInput


@strawberry.type
class CsvImportResult:
    table: CourseTable | None
    imported_count: int
    errors: list[CsvIssue]
    warnings: list[CsvIssue]


async def read_csv(input: PreviewCsvInput):
    mapping = Mapping(**{field: getattr(input.mapping, field) for field in FIELDS}) if input.mapping else None
    return await to_thread.run_sync(lambda: preview_csv(input.content_base64, mapping))


async def execute(operation):
    try:
        return await operation
    except InputError as error:
        raise GraphQLError(str(error), extensions={"code": error.code, "field": error.field}) from None
    except SQLAlchemyError as error:
        code, message = database_error_details(error)
        logger.error("%s [%s]", message, code)
        error_type = HandledDatabaseError if code != "DATABASE_ERROR" else GraphQLError
        raise error_type("Database non disponibile o non inizializzato. Verificare la connessione e le migrazioni.", extensions={"code": "DATABASE_ERROR"}) from None


async def mutate(operation, result_type, field, convert):
    try:
        row = await operation
        return result_type(**{field: convert(row)})
    except InputError as error:
        return result_type(errors=[FieldError(field=error.field, code=error.code, message=str(error))])
    except SQLAlchemyError as error:
        code, message = database_error_details(error)
        logger.error("%s [%s]", message, code)
        error_type = HandledDatabaseError if code != "DATABASE_ERROR" else GraphQLError
        raise error_type("Impossibile salvare i dati. Verificare la connessione e le migrazioni.", extensions={"code": "DATABASE_ERROR"}) from None


@strawberry.type
class User:
    id: strawberry.ID
    name: str
    email: str
    provider: str


@strawberry.type
class Query:
    @strawberry.field
    def me(self, info: Info) -> User:
        user = info.context["user"]
        return User(id=strawberry.ID(user.id), name=user.name, email=user.email, provider=user.provider)

    @strawberry.field
    async def courses(self, info: Info) -> list[Course]:
        return [course_type(row) for row in await execute(info.context["store"].courses())]

    @strawberry.field
    async def course_tables(self, info: Info, course_id: strawberry.ID) -> list[CourseTable]:
        return [table_type(row) for row in await execute(info.context["store"].tables(str(course_id)))]

    @strawberry.field
    async def student_rows(self, info: Info, table_id: strawberry.ID) -> list[StudentRow]:
        return [student_type(row) for row in await execute(info.context["store"].rows(str(table_id)))]


@strawberry.type
class Mutation:
    @strawberry.mutation
    async def preview_csv(self, input: PreviewCsvInput) -> CsvPreview:
        preview = await read_csv(input)
        return CsvPreview(
            headers=preview.headers, mapping=CsvMapping(**vars(preview.mapping)),
            total_rows=preview.total_rows, rows=[CsvStudent(**vars(row)) for row in preview.rows[:100]],
            errors=[CsvIssue(**vars(error)) for error in preview.errors],
            warnings=[CsvIssue(**vars(warning)) for warning in preview.warnings],
            delimiter=preview.delimiter, header_row=preview.header_row,
        )

    @strawberry.mutation
    async def import_csv_table(self, info: Info, input: ImportCsvInput) -> CsvImportResult:
        # Revalidate the file on save; never trust the client's earlier preview.
        preview = await read_csv(input.file)
        warnings = [CsvIssue(**vars(warning)) for warning in preview.warnings]
        if preview.errors:
            return CsvImportResult(table=None, imported_count=0, errors=[CsvIssue(**vars(error)) for error in preview.errors], warnings=warnings)
        table = input.table
        result = await mutate(info.context["store"].import_table(str(table.course_id), table.name, table.date, table.location, preview), TableResult, "table", table_type)
        return CsvImportResult(
            table=result.table, imported_count=len(preview.rows) if result.table else 0,
            errors=[CsvIssue(row=None, field=error.field, message=error.message) for error in result.errors], warnings=warnings,
        )

    @strawberry.mutation
    async def create_course(self, info: Info, input: CreateCourseInput) -> CourseResult:
        return await mutate(info.context["store"].create_course(input.name, input.academic_year, input.degree_program), CourseResult, "course", course_type)

    @strawberry.mutation
    async def create_table(self, info: Info, input: CreateTableInput) -> TableResult:
        return await mutate(info.context["store"].create_table(str(input.course_id), input.name, input.date, input.location), TableResult, "table", table_type)

    @strawberry.mutation
    async def add_student(self, info: Info, input: AddStudentInput) -> StudentResult:
        return await mutate(info.context["store"].add_student(str(input.table_id), input.matricola, input.first_name, input.last_name), StudentResult, "row", student_type)


schema = GradientSchema(query=Query, mutation=Mutation)

if __name__ == "__main__":
    Path("schema.graphql").write_text(schema.as_str() + "\n", encoding="utf-8")
