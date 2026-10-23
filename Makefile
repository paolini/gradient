UV ?= uv
NPM ?= npm

.PHONY: help install check build dev

help:
	@echo "install  Installa le dipendenze Python e frontend dai lockfile"
	@echo "check    Verifica le dipendenze Python e i tipi del frontend"
	@echo "build    Compila il frontend per la produzione"
	@echo "dev      Avvia il server di sviluppo del frontend"

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
