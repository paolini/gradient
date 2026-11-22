# Dettagli tecnici (variante Clojure)

Questo documento descrive l'implementazione del branch `clojure`. Le decisioni funzionali sono in [DOCUMENTATION.md](DOCUMENTATION.md) e valgono anche qui.

## Stack

| Ruolo | `main` | `clojure` |
|---|---|---|
| Linguaggio backend | Python 3.14 | Clojure 1.12 su JVM 21+ |
| Server HTTP | FastAPI + Uvicorn | http-kit + Reitit + Ring |
| GraphQL | Strawberry (schema dal codice) | Lacinia (codice dallo schema) |
| Database | MariaDB + SQLAlchemy + Alembic | Datomic Local (nel processo) |
| OAuth | Authlib + HTTPX | `java.net.http` con PKCE scritto a mano |
| Frontend | React + TypeScript + Vite | ClojureScript + Reagent + shadow-cljs |
| Dipendenze | uv, npm | Clojure CLI (`deps.edn`), npm (solo React e shadow-cljs) |

## Struttura

```
src/gradient/
  config.clj      variabili d'ambiente e .env, validazione
  db.clj          schema Datomic, connessione, classificazione errori
  store.clj       corsi, tabelle, righe, importazione; controlli di proprietà
  csv_import.clj  parser CSV strict e validazione dell'importazione
  graphql.clj     resolver Lacinia collegati a resources/schema.graphql
  auth.clj        OAuth, cookie di stato firmato, sessioni
  main.clj        route, middleware, avvio
resources/schema.graphql   contratto GraphQL (fonte, non generato)
frontend/src/gradient/ui/  main, app (pagine e routing), csv_import, components, api
test/gradient/             test (Datomic in memoria)
dev/user.clj               start/stop dalla REPL
```

## Contratto GraphQL

In `main` il contratto è esportato dal codice Python (`make schema`). Qui il rapporto è invertito: `resources/schema.graphql` è la fonte e Lacinia lo carica all'avvio. Una mutation o un tipo si aggiungono prima nello schema, poi si collega il resolver in `gradient.graphql/resolvers`. `contract_test.clj` verifica che le operazioni del frontend (`frontend/src/gradient/ui/api.cljs`) siano valide rispetto allo schema.

Unica differenza dal contratto di `main`: in `AddStudentInput`, `firstName` e `lastName` sono `String = ""` invece di `String! = ""`, perché Lacinia rifiuta campi non annullabili con valore predefinito. Il backend tratta null come stringa vuota.

Il codice Clojure usa chiavi kebab-case; la conversione da e verso il camelCase del client avviene al confine, in `gradient.graphql`.

## Datomic

Datomic Local gira nello stesso processo del backend e salva su `DATOMIC_STORAGE_DIR`. I test usano `:storage-dir :mem`: tutti i test di persistenza girano senza servizi esterni.

Corrispondenza con le tabelle di `main`:

- `courses`, `course_tables`, `student_rows`, `users`, `login_sessions` diventano entità con attributi `:course/*`, `:table/*`, `:row/*`, `:user/*`, `:session/*`. Gli identificatori esterni sono UUID (`:course/id` ecc.) con `:db.unique/identity`.
- L'unicità della matricola per tabella è la tupla `:row/table+matricola` (`[uuid-tabella matricola]`) con `:db.unique/value`, asserita soltanto quando la matricola è presente: più righe senza matricola restano ammesse. Il confronto è esatto, come `utf8mb4_bin`.
- L'unicità di provider/subject è la tupla composita `:user/provider+subject`.
- La data della tabella è una stringa ISO `AAAA-MM-GG`: Datomic non ha un tipo data senza orario.
- Lo schema è additivo e idempotente: viene installato a ogni avvio (`gradient.db/connect`) e non servono migrazioni versionate. `make migrate` lo installa senza avviare il server. Rinominare o cambiare tipo a un attributo richiede invece una procedura esplicita (nuovo attributo e copia dei dati).
- Datomic conserva la storia di ogni modifica: i valori precedenti restano interrogabili con `d/as-of` e `d/history`, e la data di creazione di una tabella si ricava dall'istante della transazione. Questo non è ancora usato dall'interfaccia, ma è rilevante per il riepilogo (prova più recente) e per un eventuale registro delle modifiche dei voti. Di conseguenza anche i dati cancellati restano nella storia, compresi gli hash delle sessioni revocate; un'eventuale cancellazione definitiva richiede l'*excision*, non disponibile in Datomic Local.
- L'importazione CSV crea la tabella e tutte le righe in un'unica transazione: o tutto o niente.

Gli errori del database (anomalie Cognitect) restano generici per il client e sono classificati nei log (`gradient.db/error-details`). Gli errori imprevisti dei resolver vengono registrati con lo stack trace completo.

## CSV

`csv_import.clj` riproduce il comportamento della versione Python: BOM UTF-8 o Windows-1252, direttiva `sep=`, campi tra virgolette su più righe, modalità strict, numerazione delle righe fisiche come `csv.reader.line_num`. Senza `sep=`, il separatore è scelto con un'euristica più semplice di `csv.Sniffer`: vince il separatore con lo stesso numero di occorrenze nel maggior numero di righe. Gli stessi casi di test di `main` passano invariati.

## Autenticazione

Le regole sono quelle di `main`. Login con state e PKCE S256, scambio del codice con `client_secret_basic` e identità letta soltanto da userinfo HTTPS. Sessioni da sette giorni salvate come hash SHA-256 e revocabili. `X-Gradient-Request: 1` e Origin uguale ad `AUTH_BASE_URL` sono obbligatori per ogni POST. Lo stato OAuth è in un cookie `gradient_oauth` firmato con HMAC-SHA256 tramite `AUTH_SECRET`, valido dieci minuti. Nei test lo scambio con il provider si sostituisce tramite la chiave `:exchange` del sistema.

## Frontend

Le pagine replicano quelle di `main`: elenco dei corsi, corso, tabella con inserimento rapido e importazione CSV. Lo stato è in ratom Reagent; non si usa re-frame. `make dev` avvia shadow-cljs con ricarica a caldo su 5173 e inoltra al backend su 8000 le richieste che non corrispondono a file. `make build` produce `frontend/public/js/main.js`, servito dal backend se `GRADIENT_STATIC_DIR` esiste.

## Memoria

Con `-XX:+UseSerialGC -Xmx160m -Xss512k -XX:TieredStopAtLevel=1` (impostati nel Dockerfile) l'uberjar AOT occupa circa 170 MB di RSS dopo l'avvio e qualche richiesta. È più della versione Python, ma senza un processo MariaDB separato. In sviluppo, con il compilatore Clojure caricato, si superano i 350 MB.

## Comandi

`make test`, `make check`, `make build`, `make uberjar`, `make docker-build`. L'immagine ha due stadi: uno con JDK, Clojure CLI e Node, l'altro con il solo JRE. Gira con UID 10001 e ha `/data` come volume.
