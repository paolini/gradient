UV ?= uv
NPM ?= npm

.PHONY: help install check build dev backend migrate schema test test-db

help:
	@echo "install  Installa le dipendenze Python e frontend dai lockfile"
	@echo "check    Verifica le dipendenze Python e i tipi del frontend"
	@echo "build    Compila il frontend per la produzione"
	@echo "dev      Avvia il server di sviluppo del frontend"
	@echo "backend  Avvia FastAPI in sviluppo su 127.0.0.1:8000"
	@echo "migrate  Applica le migrazioni alla MariaDB configurata"
	@echo "schema   Esporta il contratto GraphQL in schema.graphql"
	@echo "test     Esegue i test senza database"
	@echo "test-db  Esegue i test di integrazione sulla MariaDB configurata"

install:
	$(UV) sync --locked
	$(NPM) --prefix frontend ci

check:
	$(UV) pip check
	$(NPM) --prefix frontend run typecheck

build:
	$(NPM) --prefix frontend run build

dev:
	$(NPM) --prefix frontend run dev

backend:
	$(UV) run uvicorn gradient.app:app --host 127.0.0.1 --port 8000 --reload

migrate:
	$(UV) run alembic upgrade head

schema:
	$(UV) run python -m gradient.schema

test:
	$(UV) run python -m pytest -m 'not database'

test-db:
	GRADIENT_TEST_DB=1 $(UV) run python -m pytest -m database
