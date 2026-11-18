from asyncmy.constants import CR, ER
from sqlalchemy.exc import DBAPIError, SQLAlchemyError
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from gradient.config import Settings


def database_error_details(error: SQLAlchemyError) -> tuple[str, str]:
    args = error.orig.args if isinstance(error, DBAPIError) else ()
    number = args[0] if args else None
    if number in (CR.CR_CONNECTION_ERROR, CR.CR_CONN_HOST_ERROR, CR.CR_UNKNOWN_HOST):
        return (
            "DATABASE_UNREACHABLE",
            "Database non raggiungibile. Verifica che MariaDB sia avviato e che host e porta siano corretti.",
        )
    if number in (CR.CR_SERVER_GONE_ERROR, CR.CR_SERVER_LOST, CR.CR_SERVER_LOST_EXTENDED):
        return (
            "DATABASE_CONNECTION_LOST",
            "Connessione al database interrotta. Verifica che MariaDB sia disponibile e riprova.",
        )
    if number == ER.ACCESS_DENIED_ERROR:
        return (
            "DATABASE_AUTH_FAILED",
            "Accesso al database non riuscito. Verifica nome utente e password nella configurazione del backend.",
        )
    if number in (ER.DBACCESS_DENIED_ERROR, ER.TABLEACCESS_DENIED_ERROR, ER.COLUMNACCESS_DENIED_ERROR):
        return (
            "DATABASE_ACCESS_DENIED",
            "L'utente del database non ha i permessi necessari. Verifica i permessi sul database configurato.",
        )
    if number == ER.BAD_DB_ERROR:
        return (
            "DATABASE_NOT_FOUND",
            "Il database configurato non esiste. Verifica il nome del database e che sia stato creato.",
        )
    if number in (ER.NO_SUCH_TABLE, ER.BAD_FIELD_ERROR):
        return (
            "DATABASE_SCHEMA_NOT_READY",
            "La struttura del database non è inizializzata o aggiornata. Esegui make migrate e riprova.",
        )
    return "DATABASE_ERROR", "Si è verificato un errore nel database. Riprova o contatta chi gestisce il servizio."


class Database:
    def __init__(self, settings: Settings):
        self.engine = create_async_engine(
            settings.database_url, pool_size=2, max_overflow=0, pool_pre_ping=True,
        )
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def close(self):
        await self.engine.dispose()
