from pathlib import Path

import strawberry
from graphql import GraphQLError
from sqlalchemy.exc import SQLAlchemyError
from strawberry.types import Info

from gradient.services import InputError


@strawberry.type
class Course:
    id: strawberry.ID
    name: str
    academic_year: str


@strawberry.type
class CourseTable:
    id: strawberry.ID
    course_id: strawberry.ID
    name: str


@strawberry.type
class StudentRow:
    id: strawberry.ID
    table_id: strawberry.ID
    matricola: str | None
    first_name: str
    last_name: str


def course_type(row):
    return Course(id=strawberry.ID(row.id), name=row.name, academic_year=row.academic_year)


def table_type(row):
    return CourseTable(id=strawberry.ID(row.id), course_id=strawberry.ID(row.course_id), name=row.name)


def student_type(row):
    return StudentRow(id=strawberry.ID(row.id), table_id=strawberry.ID(row.table_id), matricola=row.matricola, first_name=row.first_name, last_name=row.last_name)


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


@strawberry.input
class CreateTableInput:
    course_id: strawberry.ID
    name: str


@strawberry.input
class AddStudentInput:
    table_id: strawberry.ID
    matricola: str | None = None
    first_name: str = ""
    last_name: str = ""


async def execute(operation):
    try:
        return await operation
    except InputError as error:
        raise GraphQLError(str(error), extensions={"code": error.code, "field": error.field}) from None
    except SQLAlchemyError:
        raise GraphQLError("Database non disponibile o non inizializzato. Verificare la connessione e le migrazioni.", extensions={"code": "DATABASE_ERROR"}) from None


async def mutate(operation, result_type, field, convert):
    try:
        row = await operation
        return result_type(**{field: convert(row)})
    except InputError as error:
        return result_type(errors=[FieldError(field=error.field, code=error.code, message=str(error))])
    except SQLAlchemyError:
        raise GraphQLError("Impossibile salvare i dati. Verificare la connessione e le migrazioni.", extensions={"code": "DATABASE_ERROR"}) from None


@strawberry.type
class Query:
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
    async def create_course(self, info: Info, input: CreateCourseInput) -> CourseResult:
        return await mutate(info.context["store"].create_course(input.name, input.academic_year), CourseResult, "course", course_type)

    @strawberry.mutation
    async def create_table(self, info: Info, input: CreateTableInput) -> TableResult:
        return await mutate(info.context["store"].create_table(str(input.course_id), input.name), TableResult, "table", table_type)

    @strawberry.mutation
    async def add_student(self, info: Info, input: AddStudentInput) -> StudentResult:
        return await mutate(info.context["store"].add_student(str(input.table_id), input.matricola, input.first_name, input.last_name), StudentResult, "row", student_type)


schema = strawberry.Schema(query=Query, mutation=Mutation)

if __name__ == "__main__":
    Path("schema.graphql").write_text(schema.as_str() + "\n", encoding="utf-8")
