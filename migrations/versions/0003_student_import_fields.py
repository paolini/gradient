"""Add email and independent DSA/DIS flags to student rows."""
from alembic import op
import sqlalchemy as sa

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("student_rows", sa.Column("email", sa.String(320), nullable=True))
    op.add_column("student_rows", sa.Column("dsa", sa.Boolean(), server_default="0", nullable=False))
    op.add_column("student_rows", sa.Column("dis", sa.Boolean(), server_default="0", nullable=False))


def downgrade():
    op.drop_column("student_rows", "dis")
    op.drop_column("student_rows", "dsa")
    op.drop_column("student_rows", "email")
