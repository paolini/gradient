from uuid import uuid4

from sqlalchemy import ForeignKey, MetaData, String, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention={
        "ix": "ix_%(table_name)s_%(column_0_name)s",
        "uq": "uq_%(table_name)s_%(column_0_name)s",
        "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
        "pk": "pk_%(table_name)s",
    })


def new_id() -> str:
    return str(uuid4())


class Course(Base):
    __tablename__ = "courses"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(200))
    academic_year: Mapped[str] = mapped_column(String(9))


class CourseTable(Base):
    __tablename__ = "course_tables"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    course_id: Mapped[str] = mapped_column(ForeignKey("courses.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))


class StudentRow(Base):
    __tablename__ = "student_rows"
    __table_args__ = (UniqueConstraint("table_id", "matricola", name="uq_student_rows_table_matricola"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    table_id: Mapped[str] = mapped_column(ForeignKey("course_tables.id"), index=True)
    matricola: Mapped[str | None] = mapped_column(String(64, collation="utf8mb4_bin"))
    first_name: Mapped[str] = mapped_column(String(200), default="")
    last_name: Mapped[str] = mapped_column(String(200), default="")
