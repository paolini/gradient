from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from gradient.config import Settings


class Database:
    def __init__(self, settings: Settings):
        self.engine = create_async_engine(
            settings.database_url, pool_size=2, max_overflow=0, pool_pre_ping=True,
        )
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def close(self):
        await self.engine.dispose()
