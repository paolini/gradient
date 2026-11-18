"""Add degree program, optional table date and location."""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("courses", sa.Column("degree_program", sa.String(200), nullable=True))
    op.add_column("course_tables", sa.Column("date", sa.Date(), nullable=True))
    op.add_column("course_tables", sa.Column("location", sa.String(200), nullable=True))


def downgrade():
    op.drop_column("course_tables", "location")
    op.drop_column("course_tables", "date")
    op.drop_column("courses", "degree_program")
