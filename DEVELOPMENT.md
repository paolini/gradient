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
  cells.clj       interpretazione e formattazione dei valori (voti, flag, testo), senza database
  columns.clj     colonne e celle
  views.clj       viste personali
  csv_import.clj  parser CSV strict e validazione dell'importazione
  graphql.clj     resolver Lacinia collegati a resources/schema.graphql
  auth.clj        OAuth, cookie di stato firmato, sessioni
  main.clj        route, middleware, avvio
resources/schema.graphql   contratto GraphQL (fonte, non generato)
frontend/src/gradient/ui/  main, app (pagine e routing), table (pagina della tabella), grid,
                           column_dialog, columns, csv_import, components, common, api,
                           operations.cljc (operazioni GraphQL, condivise con i test)
test/gradient/             test (Datomic in memoria)
dev/user.clj               start/stop dalla REPL
```

## Contratto GraphQL

In `main` il contratto è esportato dal codice Python (`make schema`). Qui il rapporto è invertito: `resources/schema.graphql` è la fonte e Lacinia lo carica all'avvio. Una mutation o un tipo si aggiungono prima nello schema, poi si collega il resolver in `gradient.graphql/resolvers`. Le operazioni del frontend sono in `frontend/src/gradient/ui/operations.cljc`, composte con frammenti condivisi (`row-fields`, `column-fields`, `view-fields`). Essendo un file `.cljc`, lo compila shadow-cljs e lo caricano anche i test sulla JVM (`frontend/src` è nel classpath degli alias `:test` e `:dev`): `contract_test.clj` verifica ogni operazione rispetto allo schema.

Differenze dal contratto di `main`, tutte dovute al rifiuto di Lacinia per i campi non annullabili con valore predefinito:

- in `AddStudentInput`, `firstName` e `lastName` sono `String = ""` invece di `String! = ""`;
- in `GradeScaleInput`, `allowAboveMaximum` e `allowNegative` sono `Boolean = false`, e `modifiers` è `[String!]` senza valore predefinito invece di `[String!]! = []`;
- in `RowSortInput`, `descending` è `Boolean = false`.

Il backend tratta null come il valore predefinito (stringa vuota, false, elenco vuoto).

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
- L'importazione CSV crea la tabella, le colonne anagrafiche e tutte le righe in un'unica transazione: o tutto o niente.
- `table_columns`, `cells` e `table_views` diventano entità `:column/*`, `:cell/*` e `:view/*`. Il tipo di colonna è una keyword (`:last-name`, `:grade`, ...); il contratto usa l'enum `LAST_NAME`, `GRADE`, ... e la conversione avviene in `gradient.columns`. La scala dei voti è in attributi distinti (`:column/grade-maximum` e `:column/grade-pass-threshold` di tipo `bigdec`, modificatori come attributo multiplo), non in un campo JSON.
- I nomi delle colonne sono unici nella tabella senza distinguere maiuscole e minuscole tramite `:column/name-key` (nome in minuscolo) e la tupla composita `:column/table+name-key`. Lo stesso vale per i nomi delle viste di un utente (`:view/user+table+name-key`). In MariaDB lo garantisce la collation.
- Una cella è identificata dalla tupla composita `:cell/row+column`. Il vincolo CHECK di `main` («esattamente un valore; il modificatore solo con un voto») è il predicato di entità `gradient.db/one-cell-value?`, dichiarato nella specifica `:cell/valid`. Datomic lo verifica soltanto nelle transazioni che asseriscono `:db/ensure :cell/valid`, come fanno tutte quelle di `gradient.columns`: a differenza del CHECK non protegge da transazioni scritte a mano senza `:db/ensure`. Svuotare una cella la elimina con `:db/retractEntity`.
- Datomic non ha liste ordinate: ordine delle colonne, colonne nascoste e ordinamento delle righe di una vista sono vettori EDN in attributi stringa.
- `:view/last-used` ha la precisione del millisecondo (in `main` microsecondi). Per riconoscere sempre l'ultima vista usata, ogni utilizzo riceve un istante successivo a quello di tutte le altre viste della tabella.
- Le tabelle create prima delle colonne configurabili ricevono le sei colonne anagrafiche all'avvio (`gradient.db/connect`), come nella migrazione `0005` di `main`. L'operazione è idempotente.

Gli errori del database (anomalie Cognitect) restano generici per il client e sono classificati nei log (`gradient.db/error-details`). Gli errori imprevisti dei resolver vengono registrati con lo stack trace completo.

## CSV

`csv_import.clj` riproduce il comportamento della versione Python: BOM UTF-8 o Windows-1252, direttiva `sep=`, campi tra virgolette su più righe, modalità strict, numerazione delle righe fisiche come `csv.reader.line_num`. Senza `sep=`, il separatore è scelto con un'euristica più semplice di `csv.Sniffer`: vince il separatore con lo stesso numero di occorrenze nel maggior numero di righe. Gli stessi casi di test di `main` passano invariati.

## Autenticazione

Le regole sono quelle di `main`. Login con state e PKCE S256, scambio del codice con `client_secret_basic` e identità letta soltanto da userinfo HTTPS. Sessioni da sette giorni salvate come hash SHA-256 e revocabili. `X-Gradient-Request: 1` e Origin uguale ad `AUTH_BASE_URL` sono obbligatori per ogni POST. Lo stato OAuth è in un cookie `gradient_oauth` firmato con HMAC-SHA256 tramite `AUTH_SECRET`, valido dieci minuti. Nei test lo scambio con il provider si sostituisce tramite la chiave `:exchange` del sistema.

## Frontend

Le pagine replicano quelle di `main`: elenco dei corsi, corso, tabella con inserimento rapido e importazione CSV. Lo stato è in ratom Reagent; non si usa re-frame.

La griglia (`grid.cljs`) riproduce `Grid.tsx`, con una differenza dovuta a Reagent: il rendering avviene al fotogramma successivo, non subito dopo l'evento. I tasti digitati tra l'apertura dell'editor e la sua comparsa nel DOM vengono quindi aggiunti alla bozza dal gestore della griglia invece di andare persi. Il focus sulla cella attiva si sposta in `component-did-update`.

La finestra di dialogo sposta il focus sul primo campo dopo `showModal`, che altrimenti lo metterebbe sul pulsante di chiusura: `:auto-focus` di React non basta dentro un `<dialog>` modale. `make dev` avvia shadow-cljs con ricarica a caldo su 5173 e inoltra al backend su 8000 le richieste che non corrispondono a file. `make build` produce `frontend/public/js/main.js`, servito dal backend se `GRADIENT_STATIC_DIR` esiste.

## Memoria

Con `-XX:+UseSerialGC -Xmx160m -Xss512k -XX:TieredStopAtLevel=1` (impostati nel Dockerfile) l'uberjar AOT occupa circa 170 MB di RSS dopo l'avvio e qualche richiesta. È più della versione Python, ma senza un processo MariaDB separato. In sviluppo, con il compilatore Clojure caricato, si superano i 350 MB.

## Comandi

`make test`, `make check`, `make build`, `make uberjar`, `make docker-build`. L'immagine ha due stadi: uno con JDK, Clojure CLI e Node, l'altro con il solo JRE. Gira con UID 10001 e ha `/data` come volume.
