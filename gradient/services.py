import re
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from gradient.database import Database
from gradient.models import Course, CourseTable, StudentRow


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
    def __init__(self, database: Database):
        self.database = database

    async def courses(self):
        async with self.database.sessions() as session:
            return list(await session.scalars(select(Course).order_by(Course.academic_year.desc(), Course.name, Course.id)))

    async def tables(self, course_id: str):
        course_id = identifier(course_id, "courseId")
        async with self.database.sessions() as session:
            await self.require(session, Course, course_id, "courseId")
            return list(await session.scalars(select(CourseTable).where(CourseTable.course_id == course_id).order_by(CourseTable.name, CourseTable.id)))

    async def rows(self, table_id: str):
        table_id = identifier(table_id, "tableId")
        async with self.database.sessions() as session:
            await self.require(session, CourseTable, table_id, "tableId")
            return list(await session.scalars(select(StudentRow).where(StudentRow.table_id == table_id).order_by(StudentRow.last_name, StudentRow.first_name, StudentRow.id)))

    @staticmethod
    async def require(session, model, entity_id: str, field: str):
        if await session.get(model, entity_id) is None:
            raise InputError(field, "Elemento non trovato.", "NOT_FOUND")

    async def create_course(self, name: str, academic_year: str):
        name = text(name, "name")
        year = academic_year.strip()
        match = re.fullmatch(r"([0-9]{4})/([0-9]{2}|[0-9]{4})", year)
        if not match:
            raise InputError("academicYear", "Usare il formato 2026/27 o 2026/2027.")
        start = int(match[1])
        if start < 1000 or start >= 9999 or match[2] not in (str(start + 1), str(start + 1)[-2:]):
            raise InputError("academicYear", "L'anno accademico deve comprendere due anni consecutivi.")
        course = Course(name=name, academic_year=f"{start}/{start + 1}")
        async with self.database.sessions.begin() as session:
            session.add(course)
        return course

    async def create_table(self, course_id: str, name: str):
        course_id = identifier(course_id, "courseId")
        table = CourseTable(course_id=course_id, name=text(name, "name"))
        async with self.database.sessions.begin() as session:
            await self.require(session, Course, course_id, "courseId")
            session.add(table)
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
            if error.orig.args[0] == 1062:
                raise InputError("matricola", "Matricola già presente in questa tabella.", "DUPLICATE_MATRICOLA") from None
            raise
        return row
