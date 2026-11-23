from datetime import date as DateValue, datetime
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import JSON, Boolean, CheckConstraint, Date, DateTime, ForeignKey, Integer, MetaData, Numeric, String, Text, UniqueConstraint
from sqlalchemy.dialects.mysql import DATETIME
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
    owner_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))
    academic_year: Mapped[str] = mapped_column(String(9))
    degree_program: Mapped[str | None] = mapped_column(String(200))


class CourseTable(Base):
    __tablename__ = "course_tables"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    course_id: Mapped[str] = mapped_column(ForeignKey("courses.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))

    date: Mapped[DateValue | None] = mapped_column(Date)
    location: Mapped[str | None] = mapped_column(String(200))


class StudentRow(Base):
    __tablename__ = "student_rows"
    __table_args__ = (UniqueConstraint("table_id", "matricola", name="uq_student_rows_table_matricola"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    table_id: Mapped[str] = mapped_column(ForeignKey("course_tables.id"), index=True)
    matricola: Mapped[str | None] = mapped_column(String(64, collation="utf8mb4_bin"))
    first_name: Mapped[str] = mapped_column(String(200), default="")
    last_name: Mapped[str] = mapped_column(String(200), default="")
    email: Mapped[str | None] = mapped_column(String(320))
    dsa: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0")
    dis: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0")


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("provider", "subject", name="uq_users_provider_subject"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    provider: Mapped[str] = mapped_column(String(16))
    subject: Mapped[str] = mapped_column(String(255, collation="utf8mb4_bin"))
    email: Mapped[str] = mapped_column(String(320))
    name: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime)
    last_login: Mapped[datetime] = mapped_column(DateTime)


class LoginSession(Base):
    __tablename__ = "login_sessions"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime, index=True)


class TableColumn(Base):
    """Definizione di una colonna, comune a tutti gli utenti della tabella."""
    __tablename__ = "table_columns"
    __table_args__ = (UniqueConstraint("table_id", "name", name="uq_table_columns_table_name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    table_id: Mapped[str] = mapped_column(ForeignKey("course_tables.id", ondelete="CASCADE"), index=True)
    # Ordine predefinito (di creazione); l'ordine visualizzato dipende dalla vista dell'utente.
    position: Mapped[int] = mapped_column(Integer)
    kind: Mapped[str] = mapped_column(String(16))
    name: Mapped[str] = mapped_column(String(100))
    config: Mapped[dict | None] = mapped_column(JSON)


class Cell(Base):
    """Valore presente di una cella: la cella vuota non viene memorizzata."""
    __tablename__ = "cells"
    __table_args__ = (CheckConstraint(
        "(flag IS NOT NULL) + (grade_base IS NOT NULL) + (text_value IS NOT NULL) = 1"
        " AND (grade_modifier = '' OR grade_base IS NOT NULL)",
        name="ck_cells_one_value",
    ),)

    row_id: Mapped[str] = mapped_column(ForeignKey("student_rows.id", ondelete="CASCADE"), primary_key=True)
    column_id: Mapped[str] = mapped_column(ForeignKey("table_columns.id", ondelete="CASCADE"), primary_key=True, index=True)
    flag: Mapped[bool | None] = mapped_column(Boolean)
    grade_base: Mapped[Decimal | None] = mapped_column(Numeric(10, 4))
    grade_modifier: Mapped[str] = mapped_column(String(8), default="", server_default="")
    text_value: Mapped[str | None] = mapped_column(String(1000))


class TableView(Base):
    """Vista personale con nome: ordine e visibilità delle colonne, ordinamento delle righe."""
    __tablename__ = "table_views"
    __table_args__ = (UniqueConstraint("user_id", "table_id", "name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    table_id: Mapped[str] = mapped_column(ForeignKey("course_tables.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(100))
    column_order: Mapped[list] = mapped_column(JSON)
    hidden_columns: Mapped[list] = mapped_column(JSON)
    row_sort: Mapped[list] = mapped_column(JSON)
    # Si riapre la vista usata più di recente.
    last_used: Mapped[datetime] = mapped_column(DateTime().with_variant(DATETIME(fsp=6), "mysql", "mariadb"))


class Exercise(Base):
    """Esercizio dell'archivio personale, riutilizzabile in tutti i corsi dell'utente."""
    __tablename__ = "exercises"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    owner_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime)
    updated_at: Mapped[datetime] = mapped_column(DateTime)


class ExerciseVariant(Base):
    """Variante di un esercizio: testo e opzioni in Typst, una sola risposta corretta."""
    __tablename__ = "exercise_variants"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    exercise_id: Mapped[str] = mapped_column(ForeignKey("exercises.id", ondelete="CASCADE"), index=True)
    position: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)
    options: Mapped[list] = mapped_column(JSON)
    correct: Mapped[int] = mapped_column(Integer)
