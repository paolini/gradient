from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from strawberry.fastapi import GraphQLRouter

from gradient.config import Settings
from gradient.database import Database
from gradient.schema import schema
from gradient.services import Store


def create_app(settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        database = Database(settings or Settings())
        app.state.store = Store(database)
        try:
            yield
        finally:
            await database.close()

    application = FastAPI(title="Gradient", lifespan=lifespan)

    async def context(request: Request):
        return {"store": request.app.state.store}

    application.include_router(GraphQLRouter(schema, context_getter=context, allow_queries_via_get=False), prefix="/graphql")
    return application


app = create_app()
