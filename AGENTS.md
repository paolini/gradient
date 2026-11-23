# Indicazioni per gli agenti

Questo branch (`clojure`) è una riscrittura sperimentale in Clojure/ClojureScript con Datomic Local delle funzionalità di `main` al commit `64df627`. Non ha storia in comune con `main` e non va unito a esso.

Leggere [DOCUMENTATION.md](DOCUMENTATION.md) (requisiti funzionali, condivisi con `main`) e [DEVELOPMENT.md](DEVELOPMENT.md) (implementazione e differenze rispetto a `main`). Mantenere la documentazione in italiano e distinguere le decisioni accettate dalle proposte.

I vincoli funzionali elencati nell'AGENTS.md di `main` valgono anche qui: identità delle righe, matricola facoltativa ma unica per tabella, nome e cognome per singola tabella, DSA/DIS indipendenti, importazione atomica con rivalidazione lato server, isolamento dei corsi per proprietario, nessuna unione di account tramite email.

Regole tecniche del branch:

- `resources/schema.graphql` è la fonte del contratto: modificarlo prima dei resolver. `make test` verifica anche le operazioni del frontend.
- Lo schema Datomic in `gradient.db/schema` è soltanto additivo; non rinominare né cambiare tipo agli attributi esistenti senza una procedura concordata.
- Errori del database: messaggio generico al client e diagnosi nei log; stack trace completo soltanto per gli errori non classificati.
- Una sola JVM accede alla directory di Datomic Local; tenere bassi heap e thread.
- Usare `make test`, `make check`, `make build`. Versionare `deps.edn` e `frontend/package-lock.json`; non versionare `data/`, `target/`, `.cpcache/`, `.shadow-cljs/`, `node_modules/`, `frontend/public/js/` o segreti.
