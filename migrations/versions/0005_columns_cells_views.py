"""Colonne configurabili, celle e viste personali delle tabelle."""
from uuid import uuid4

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None

BUILTIN = [
    ("matricola", "Matricola"), ("last_name", "Cognome"), ("first_name", "Nome"),
    ("email", "Email"), ("dsa", "DSA"), ("dis", "DIS"),
]
OPTIONS = {"mysql_engine": "InnoDB", "mysql_charset": "utf8mb4"}


def upgrade():
    columns = op.create_table("table_columns",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("table_id", sa.String(36), sa.ForeignKey("course_tables.id", name="fk_table_columns_table_id_course_tables", ondelete="CASCADE"), nullable=False),
        sa.Column("position", sa.Integer(), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("config", sa.JSON(), nullable=True),
        sa.UniqueConstraint("table_id", "name", name="uq_table_columns_table_name"),
        **OPTIONS)
    op.create_index("ix_table_columns_table_id", "table_columns", ["table_id"])
    op.create_table("cells",
        sa.Column("row_id", sa.String(36), sa.ForeignKey("student_rows.id", name="fk_cells_row_id_student_rows", ondelete="CASCADE"), primary_key=True),
        sa.Column("column_id", sa.String(36), sa.ForeignKey("table_columns.id", name="fk_cells_column_id_table_columns", ondelete="CASCADE"), primary_key=True),
        sa.Column("flag", sa.Boolean(), nullable=True),
        sa.Column("grade_base", sa.Numeric(10, 4), nullable=True),
        sa.Column("grade_modifier", sa.String(8), server_default="", nullable=False),
        sa.Column("text_value", sa.String(1000), nullable=True),
        sa.CheckConstraint(
            "(flag IS NOT NULL) + (grade_base IS NOT NULL) + (text_value IS NOT NULL) = 1"
            " AND (grade_modifier = '' OR grade_base IS NOT NULL)",
            name="ck_cells_one_value"),
        **OPTIONS)
    op.create_index("ix_cells_column_id", "cells", ["column_id"])
    op.create_table("table_views",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", name="fk_table_views_user_id_users", ondelete="CASCADE"), nullable=False),
        sa.Column("table_id", sa.String(36), sa.ForeignKey("course_tables.id", name="fk_table_views_table_id_course_tables", ondelete="CASCADE"), nullable=False),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("column_order", sa.JSON(), nullable=False),
        sa.Column("hidden_columns", sa.JSON(), nullable=False),
        sa.Column("row_sort", sa.JSON(), nullable=False),
        sa.Column("last_used", mysql.DATETIME(fsp=6), nullable=False),
        sa.UniqueConstraint("user_id", "table_id", "name", name="uq_table_views_user_id"),
        **OPTIONS)
    op.create_index("ix_table_views_table_id", "table_views", ["table_id"])

    # Le tabelle esistenti ricevono le colonne anagrafiche predefinite.
    tables = op.get_bind().execute(sa.text("SELECT id FROM course_tables")).scalars().all()
    rows = [
        {"id": str(uuid4()), "table_id": table_id, "position": position, "kind": kind, "name": name, "config": None}
        for table_id in tables for position, (kind, name) in enumerate(BUILTIN)
    ]
    if rows:
        op.bulk_insert(columns, rows)


def downgrade():
    op.drop_table("table_views")
    op.drop_table("cells")
    op.drop_table("table_columns")
