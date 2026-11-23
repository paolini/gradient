# Gradient (variante Clojure)

Registro web delle valutazioni universitarie: compitini, scritti e orali in tabelle simili a un foglio di calcolo, con login Google o UniPi. Backend Clojure con GraphQL (Lacinia) su Datomic Local, frontend ClojureScript con Reagent.

Questo branch (`clojure`) è un esperimento: riscrive da zero in Clojure le funzionalità di `main` al commit `64df627`, per confrontare il risultato. Non ha storia in comune con `main`.

Approfondimenti: [DOCUMENTATION.md](DOCUMENTATION.md) (requisiti e decisioni funzionali), [DEVELOPMENT.md](DEVELOPMENT.md) (dettagli tecnici e differenze rispetto a `main`).

## Sviluppo locale

Prerequisiti: Java 21 o successivo, [Clojure CLI](https://clojure.org/guides/install_clojure), Node.js con npm e Make. Non serve un database esterno: Datomic Local gira dentro il processo del backend e salva i dati in `data/`.

1. Copiare la configurazione:

   ```bash
   cp .env.example .env
   ```

2. Login: in `.env` impostare `AUTH_SECRET` (almeno 32 caratteri casuali) e le credenziali di almeno un provider (`GOOGLE_CLIENT_*` o `WSO2_CLIENT_*`):

   ```bash
   openssl rand -base64 48
   ```

   Registrare presso il provider la callback `${AUTH_BASE_URL}/auth/callback/google` oppure `${AUTH_BASE_URL}/auth/callback/unipi`, con scope `openid email profile`, authorization code e PKCE S256. In sviluppo `AUTH_BASE_URL` è `http://127.0.0.1:5173`: aprire esattamente questa origine.

3. Installare e avviare:

   ```bash
   make install  # dipendenze Clojure e npm
   make backend  # backend su http://127.0.0.1:8000 (crea il database al primo avvio)
   make dev      # in un secondo terminale: shadow-cljs su http://127.0.0.1:5173
   ```

   In alternativa a `make backend`: `make repl`, poi `(start)` e `(restart)` dalla REPL.

Verifiche: `make test` (tutti i test, anche quelli di persistenza, con Datomic in memoria) e `make check`. `make help` elenca tutti i comandi.

## Produzione

L'immagine Docker contiene frontend e backend sulla porta 8000: `make docker-build` (tag predefinito `gradient:clojure`).

1. Preparare `.env.production` a partire da [.env.example](.env.example), con credenziali reali, un `AUTH_SECRET` casuale persistente e `AUTH_BASE_URL=https://gradient.example.org`. Registrare le callback OAuth su questa origine. Nel container `DATOMIC_STORAGE_DIR` è già `/data`.

2. Avviare con un volume persistente per i dati:

   ```bash
   docker run -d --name gradient --restart unless-stopped \
     --env-file .env.production -v gradient-data:/data \
     -p 127.0.0.1:8000:8000 gradient:clojure
   ```

   Lo schema Datomic viene installato all'avvio (è additivo e idempotente). Un solo container alla volta deve usare il volume.

3. Pubblicare tutto dietro un reverse proxy HTTPS verso la porta 8000. Il proxy deve preservare l'header `Origin`, non mettere in cache le risposte autenticate e non registrare le query di `/auth/callback/`.

Il controllo di salute è `GET /healthz`. Per il backup basta copiare il volume a container fermo.
