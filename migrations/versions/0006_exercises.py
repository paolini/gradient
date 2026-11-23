"""Archivio personale degli esercizi a risposta multipla."""
from alembic import op
import sqlalchemy as sa

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None

OPTIONS = {"mysql_engine": "InnoDB", "mysql_charset": "utf8mb4"}


def upgrade():
    op.create_table("exercises",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("owner_id", sa.String(36), sa.ForeignKey("users.id", name="fk_exercises_owner_id_users", ondelete="CASCADE"), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        **OPTIONS)
    op.create_index("ix_exercises_owner_id", "exercises", ["owner_id"])
    op.create_table("exercise_variants",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("exercise_id", sa.String(36), sa.ForeignKey("exercises.id", name="fk_exercise_variants_exercise_id_exercises", ondelete="CASCADE"), nullable=False),
        sa.Column("position", sa.Integer(), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("options", sa.JSON(), nullable=False),
        sa.Column("correct", sa.Integer(), nullable=False),
        **OPTIONS)
    op.create_index("ix_exercise_variants_exercise_id", "exercise_variants", ["exercise_id"])


def downgrade():
    op.drop_table("exercise_variants")
    op.drop_table("exercises")
