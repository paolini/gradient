# Note tecniche per lo sviluppo

Dettagli tecnici del progetto. L'avvio rapido è nel [README](README.md), la descrizione funzionale in [DOCUMENTATION.md](DOCUMENTATION.md), le istruzioni per gli agenti in [AGENTS.md](AGENTS.md).

## Scelte tecniche

Le scelte concordate sono:

- **MariaDB** per il database;
- **Python 3.14** per il backend;
- **FastAPI con Uvicorn** per il server HTTP;
- **SQLAlchemy** per i modelli e l'accesso al database;
- **Alembic** per le migrazioni dello schema;
- **GraphQL con Strawberry** per l'API;
- **React con Vite** e TypeScript per il frontend;
- **Authlib** e **HTTPX** per il login OAuth, con **itsdangerous** per lo stato OAuth.

Gli strumenti di sviluppo sono **uv** per dipendenze e ambiente Python, **npm** per il frontend, un **Makefile** per i comandi comuni e **Docker** per l'immagine di deploy.

GraphQL è stato scelto in particolare per il contratto client/server esplicito; Strawberry è già stato utilizzato dal committente in un altro progetto. SQLAlchemy e Alembic hanno ruoli complementari: definizione e utilizzo dei modelli il primo, evoluzione versionata dello schema il secondo.

Le altre librerie e le modalità di distribuzione sono ancora da confermare. È previsto un utilizzo ridotto, con pochissimi utenti; il contenimento del consumo di RAM sul server è un obiettivo progettuale.

## Struttura del repository

- `gradient/`: modelli e backend; il pool del backend ha un massimo di due connessioni per processo e usa il driver asincrono `asyncmy`.
- `migrations/`: migrazioni Alembic.
- `frontend/`: applicazione React/Vite, con il proprio lockfile npm.
- `schema.graphql`: contratto GraphQL esportato.
- `tests/`: test con e senza database.
- `examples/`: CSV di esempio con dati fittizi.

Le dipendenze Python sono in `pyproject.toml` e `uv.lock`. Gli ambienti locali, `node_modules`, i risultati delle compilazioni e i file `.env` sono esclusi dal versionamento; i lockfile vanno invece conservati in Git.

Non è necessario attivare manualmente `.venv`: i comandi Python del progetto si eseguono tramite `uv run`. Il Makefile consente di sovrascrivere `UV`, `NPM`, `DOCKER` e `IMAGE`.

## Configurazione

Il backend e Alembic leggono `.env` dalla radice del repository tramite pydantic-settings; le variabili d'ambiente hanno precedenza. I parametri sono descritti in [.env.example](.env.example). Riavviare il backend dopo modifiche a `.env` o alle dipendenze.

Le migrazioni vengono eseguite esplicitamente (`make migrate`); il backend non le applica nel proprio lifespan.

### MariaDB di sviluppo

Il backend si collega a una MariaDB esterna, anche gestita tramite un Compose condiviso con altri progetti. I comandi Make non avviano container. Per usare un'istanza esistente, creare un database e un utente dedicati a Gradient e configurare in `.env` host, porta, nome del database e credenziali. Il backend non richiede la password root.

[compose.example.yaml](compose.example.yaml) è un esempio facoltativo con MariaDB 11.8, database e utente applicativo dedicati, volume persistente e controllo di disponibilità. Le password del file di esempio sono soltanto per lo sviluppo locale. Il database è pubblicato su `127.0.0.1:3306`; se la porta è occupata, cambiare `DB_PORT` in `.env`. `DB_HOST` descrive l'indirizzo raggiunto dal backend e non cambia il binding del container, che resta limitato a localhost.

Il servizio e il relativo volume possono essere copiati nel proprio Compose condiviso. I valori `MARIADB_*` creano database e utenti soltanto quando il volume è inizialmente vuoto: modificare `.env` in seguito non cambia automaticamente credenziali o database esistenti. Il file non rappresenta la configurazione di deploy e non avvia backend o frontend. Per fermarlo conservando il volume con i dati:

```bash
docker compose -p gradient-dev -f compose.example.yaml down
```

## Autenticazione

Il login usa authorization code, state e PKCE S256 tramite Authlib. Il backend scambia il codice con autenticazione del client HTTP Basic e legge l'identità esclusivamente dall'endpoint HTTPS userinfo, senza utilizzare ID token non verificati. Lo stato temporaneo OAuth è firmato con `AUTH_SECRET` e scade dopo dieci minuti. I token OAuth non vengono conservati.

Per Google è richiesta un'email verificata. Per UniPi il profilo è letto dall'endpoint autenticato dell'ateneo, con compatibilità per i campi WSO2 storici, come nell'integrazione di MatBit; gli endpoint predefiniti sono sovrascrivibili con le variabili `WSO2_*_URL`. Le credenziali di MatBit non vengono copiate.

Il cookie applicativo è HttpOnly e SameSite=Lax, Secure su HTTPS; nel database viene conservato soltanto l'hash del token casuale di sessione. Le sessioni durano sette giorni; il logout revoca la sessione locale.

Solo i provider completamente configurati vengono mostrati nella pagina di accesso. Non è previsto un accesso anonimo di sviluppo. I test con provider simulati non sostituiscono la verifica reale, in particolare per le impostazioni del client UniPi.

Tutte le richieste GraphQL richiedono una sessione autenticata. Ogni POST, logout compreso, richiede l'intestazione `X-Gradient-Request: 1` e, se presente, un'`Origin` corrispondente ad `AUTH_BASE_URL`. L'editor GraphQL può essere aperto tramite l'origine frontend dopo il login, configurando questa intestazione. In sviluppo Vite inoltra `/graphql` e `/auth` al backend locale, senza configurazioni CORS.

## Contratto GraphQL

Il contratto è dichiarato in `gradient/schema.py` ed esportato in [schema.graphql](schema.graphql) con `make schema`. Le operazioni frontend sono in `frontend/src/api.ts`; i test ne verificano la validità rispetto allo schema e controllano che il file esportato sia aggiornato.

Le operazioni disponibili sono `me` (profilo autenticato), `courses`, `courseTables`, `studentRows`, `tableColumns`, `tableViews`, `createCourse`, `createTable`, `addStudent`, `addColumn`, `updateColumn`, `deleteColumn`, `setCell`, `saveTableView`, `selectTableView`, `deleteTableView`, `previewCsv` e `importCsvTable`. Le operazioni frontend possono comporre frammenti condivisi (`ROW`, `COLUMN`, `VIEW` in `api.ts`), espansi dal test del contratto.

`setCell` riceve il testo digitato e lo interpreta sul server secondo il tipo della colonna (`gradient/cells.py`): voti con virgola o punto e modificatori `+`, `-`, `L` o «e lode», flag `sì/no` (anche `s/n`, `x`, `1/0`), testo. Il testo vuoto svuota la cella. Sulle colonne anagrafiche aggiorna i campi della riga; DSA e DIS restano a due stati. La risposta contiene la riga aggiornata con tutte le sue celle. Le mutazioni restituiscono errori di validazione con campo, codice e messaggio; l'importazione indica anche la riga del CSV. Gli ID inesistenti e le matricole duplicate vengono segnalati senza salvare righe parziali. Gli anni accademici accettano `2026/27` o `2026/2027` e vengono memorizzati nella forma completa. Matricole vuote diventano valori mancanti, mentre gli zeri iniziali vengono conservati.

### Errori del database

Le risposte GraphQL mantengono un messaggio generico e il codice `DATABASE_ERROR`. La diagnosi specifica viene scritta soltanto nei log del backend: servizio non raggiungibile, connessione interrotta, credenziali errate, permessi insufficienti, database inesistente o struttura da inizializzare o aggiornare. Se MariaDB non è raggiungibile, il log invita a verificare avvio, host e porta; `make migrate` viene suggerito quando mancano tabelle o colonne. Questi messaggi non riportano credenziali o dettagli grezzi del driver.

Gli errori operativi riconosciuti sono intercettati e producono un solo messaggio diagnostico, senza il traceback automatico di Strawberry. Gli errori imprevisti o non classificati mantengono il traceback per consentire il debug.

## Migrazioni

- `0002`: data e luogo facoltativi delle tabelle, corso di laurea del corso.
- `0003`: email e flag DSA/DIS delle righe, inizialmente vuoti/off.
- `0004`: utenti, sessioni e proprietario dei corsi; i corsi precedenti restano senza proprietario.
- `0005`: definizioni delle colonne (`table_columns`), celle (`cells`) e viste personali (`table_views`). Aggiunge alle tabelle esistenti le sei colonne anagrafiche. Un vincolo CHECK impone che una cella presente abbia esattamente un valore; le celle vuote non sono memorizzate. Celle, colonne e viste sono eliminate in cascata con righe, tabelle e utenti. `last_used` usa i microsecondi per distinguere l'ultima vista usata.

## Test

```bash
make check    # compatibilità delle dipendenze Python e controllo dei tipi frontend
make test     # contratto, configurazione e validazione, senza database
make test-db  # integrazione sulla MariaDB configurata, dopo make migrate
```

I test di integrazione creano corsi temporanei e rimuovono soltanto i propri dati al termine. Verificano persistenza tra istanze dell'applicazione, unicità delle matricole per tabella, più matricole mancanti, indipendenza dei nomi tra tabelle, rollback dell'importazione, sessioni reali, separazione degli account con la stessa email, isolamento dei corsi, logout e scadenza. Non creano né avviano database: utilizzare un database di sviluppo.

## Immagine Docker

Il [Dockerfile](Dockerfile) compila il frontend con Node e installa le dipendenze Python con `uv sync --locked --no-dev` in stadi separati. L'immagine finale contiene Python 3.14, ambiente virtuale, codice backend, migrazioni e frontend compilato; non contiene Node, dipendenze di sviluppo o configurazione locale. I file `.env*`, l'ambiente Python locale e gli output frontend locali sono esclusi dal contesto di build.

Il processo gira con UID/GID 10001 sulla porta 8000, con un singolo worker Uvicorn per contenere il consumo di RAM e le connessioni database. FastAPI monta `frontend/dist` soltanto se presente, dopo le route API: in sviluppo, senza build, il backend espone soltanto API e controllo di salute.

`GET /healthz` verifica la risposta del processo senza accedere al database: uno stato healthy non garantisce la raggiungibilità di MariaDB, lo stato delle migrazioni o la configurazione OAuth.

Le intestazioni proxy non sono considerate da Uvicorn: URL OAuth e sicurezza dei cookie dipendono da `AUTH_BASE_URL`. I log di accesso Uvicorn sono disabilitati per evitare di registrare i codici OAuth nelle query delle callback.

### Migrazioni automatiche

Con `GRADIENT_AUTO_MIGRATE=true` l'entrypoint esegue `alembic upgrade head` prima di Uvicorn e avvia il server con `exec` soltanto se la migrazione riesce. Il valore predefinito è `false`; sono accettati anche `1`/`0` e `TRUE`/`FALSE`, mentre un valore diverso interrompe l'avvio. La variabile è letta dall'ambiente del container, non dal file `.env` tramite pydantic-settings.

Le migrazioni automatiche si applicano soltanto quando il comando da avviare è `uvicorn`; i comandi manuali, incluso `alembic upgrade head`, vengono eseguiti direttamente. Con più container applicativi, eseguire la migrazione una sola volta come passo separato.

### Pubblicazione su Docker Hub

Il workflow [.github/workflows/docker.yml](.github/workflows/docker.yml) costruisce e pubblica l'immagine `linux/amd64` a ogni push sul branch `main`. Legge la versione da `project.version` in `pyproject.toml` (attualmente `0.1.0`) e pubblica la stessa build con i tag:

- `paolini/gradient:vX.Y.Z`;
- `paolini/gradient:main`.

La versione deve avere il formato `X.Y.Z`; una versione non valida interrompe il workflow prima della pubblicazione. Per ogni nuovo rilascio aggiornare la versione in `pyproject.toml` e il relativo `uv.lock`. Push successivi con la stessa versione aggiornano anche il tag versionato, che quindi non è immutabile.

Servono i repository secret `DOCKERHUB_USERNAME` (utente con accesso a `paolini/gradient`) e `DOCKERHUB_TOKEN` (token con permesso di scrittura), configurati in **Settings → Secrets and variables → Actions**. Le action sono fissate a commit specifici e Buildx usa la cache di GitHub Actions, seguendo le [action ufficiali Docker](https://docs.docker.com/build/ci/github-actions/). Il workflow non distribuisce né riavvia l'applicazione sul server.
