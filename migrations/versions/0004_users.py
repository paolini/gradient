"""Utenti OAuth, sessioni e proprietà dei corsi."""
from alembic import op
import sqlalchemy as sa

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("users",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("provider", sa.String(16), nullable=False),
        sa.Column("subject", sa.String(255, collation="utf8mb4_bin"), nullable=False),
        sa.Column("email", sa.String(320), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("last_login", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("provider", "subject", name="uq_users_provider_subject"))
    op.create_table("login_sessions",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False))
    op.create_index("ix_login_sessions_user_id", "login_sessions", ["user_id"])
    op.create_index("ix_login_sessions_expires_at", "login_sessions", ["expires_at"])
    op.add_column("courses", sa.Column("owner_id", sa.String(36), nullable=True))
    op.create_index("ix_courses_owner_id", "courses", ["owner_id"])
    op.create_foreign_key("fk_courses_owner_id_users", "courses", "users", ["owner_id"], ["id"])


def downgrade():
    op.drop_constraint("fk_courses_owner_id_users", "courses", type_="foreignkey")
    op.drop_index("ix_courses_owner_id", table_name="courses")
    op.drop_column("courses", "owner_id")
    op.drop_table("login_sessions")
    op.drop_table("users")
