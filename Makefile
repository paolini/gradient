CLOJURE ?= clojure
NPM ?= npm
DOCKER ?= docker
IMAGE ?= gradient:clojure
JVM_DEV ?= -J--sun-misc-unsafe-memory-access=allow

.PHONY: help install check build dev backend repl migrate test uberjar docker-build

help:
	@echo "install       Scarica le dipendenze Clojure e npm"
	@echo "check         Compila il frontend ClojureScript senza ottimizzazioni"
	@echo "build         Compila il frontend per la produzione in frontend/public/js"
	@echo "dev           Avvia shadow-cljs (ricarica a caldo) su http://127.0.0.1:5173"
	@echo "backend       Avvia il backend su 127.0.0.1:8000"
	@echo "repl          REPL del backend con test e codice di sviluppo"
	@echo "migrate       Crea il database Datomic e installa lo schema"
	@echo "test          Esegue tutti i test (Datomic in memoria)"
	@echo "uberjar       Costruisce target/gradient.jar"
	@echo "docker-build  Costruisce l'immagine di produzione (IMAGE=gradient:clojure)"

install:
	$(CLOJURE) -P -M:test
	$(NPM) --prefix frontend ci

check:
	cd frontend && npx shadow-cljs compile app

build:
	cd frontend && npx shadow-cljs release app

dev:
	cd frontend && npx shadow-cljs watch app

backend:
	$(CLOJURE) $(JVM_DEV) -M -m gradient.main

repl:
	$(CLOJURE) $(JVM_DEV) -M:dev:test -r

migrate:
	$(CLOJURE) $(JVM_DEV) -M -m gradient.main migrate

test:
	$(CLOJURE) -X:test

uberjar:
	$(CLOJURE) -T:uberjar uber

docker-build:
	$(DOCKER) build --tag $(IMAGE) .
