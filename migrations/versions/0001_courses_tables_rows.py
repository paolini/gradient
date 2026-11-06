"""Create courses, configurable tables and student rows."""
from alembic import op
import sqlalchemy as sa

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "courses",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("academic_year", sa.String(9), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_courses"),
        mysql_engine="InnoDB", mysql_charset="utf8mb4",
    )
    op.create_table(
        "course_tables",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("course_id", sa.String(36), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_course_tables"),
        sa.ForeignKeyConstraint(["course_id"], ["courses.id"], name="fk_course_tables_course_id_courses"),
        mysql_engine="InnoDB", mysql_charset="utf8mb4",
    )
    op.create_index("ix_course_tables_course_id", "course_tables", ["course_id"])
    op.create_table(
        "student_rows",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("table_id", sa.String(36), nullable=False),
        sa.Column("matricola", sa.String(64, collation="utf8mb4_bin"), nullable=True),
        sa.Column("first_name", sa.String(200), nullable=False),
        sa.Column("last_name", sa.String(200), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_student_rows"),
        sa.ForeignKeyConstraint(["table_id"], ["course_tables.id"], name="fk_student_rows_table_id_course_tables"),
        sa.UniqueConstraint("table_id", "matricola", name="uq_student_rows_table_matricola"),
        mysql_engine="InnoDB", mysql_charset="utf8mb4",
    )
    op.create_index("ix_student_rows_table_id", "student_rows", ["table_id"])


def downgrade():
    op.drop_table("student_rows")
    op.drop_table("course_tables")
    op.drop_table("courses")
