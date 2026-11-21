# Gradient

Registro web delle valutazioni universitarie: compitini, scritti e orali in tabelle simili a un foglio di calcolo, con login Google o UniPi. Backend FastAPI + GraphQL su MariaDB, frontend React.

Approfondimenti: [DOCUMENTATION.md](DOCUMENTATION.md) (requisiti e decisioni funzionali), [DEVELOPMENT.md](DEVELOPMENT.md) (dettagli tecnici).

## Sviluppo locale

Prerequisiti: Python 3.14, [uv](https://docs.astral.sh/uv/), Node.js con npm, Make e una MariaDB raggiungibile.

1. Copiare la configurazione:

   ```bash
   cp .env.example .env
   ```

2. Database: configurare in `.env` un database e un utente dedicati su una MariaDB esistente, oppure avviare quella di esempio:

   ```bash
   docker compose -p gradient-dev -f compose.example.yaml up -d --wait
   ```

3. Login: in `.env` impostare `AUTH_SECRET` (almeno 32 caratteri casuali) e le credenziali di almeno un provider (`GOOGLE_CLIENT_*` o `WSO2_CLIENT_*`):

   ```bash
   uv run python -c "import secrets; print(secrets.token_urlsafe(48))"
   ```

   Registrare presso il provider la callback `${AUTH_BASE_URL}/auth/callback/google` oppure `${AUTH_BASE_URL}/auth/callback/unipi`, con scope `openid email profile`, authorization code e PKCE S256. In sviluppo `AUTH_BASE_URL` è `http://127.0.0.1:5173`: aprire esattamente questa origine.

4. Installare, migrare e avviare:

   ```bash
   make install  # dipendenze Python e frontend dai lockfile
   make migrate  # applica le migrazioni
   make backend  # FastAPI su http://127.0.0.1:8000
   make dev      # in un secondo terminale: frontend Vite su http://127.0.0.1:5173
   ```

Verifiche: `make check`, `make test` e, sul database di sviluppo, `make test-db`. Dopo modifiche all'API GraphQL eseguire `make schema`. `make help` elenca tutti i comandi.

## Produzione

L'immagine Docker contiene frontend e backend sulla porta 8000. Si può usare quella pubblicata (`paolini/gradient:main` o `paolini/gradient:vX.Y.Z`) oppure costruirla con `make docker-build`.

1. Preparare `.env.production` a partire da [.env.example](.env.example), con credenziali reali, un `AUTH_SECRET` casuale persistente e `AUTH_BASE_URL=https://gradient.example.org`. Registrare le callback OAuth su questa origine.

2. MariaDB deve essere già disponibile. Nell'esempio `app-network` è una rete Docker condivisa con il database, e `DB_HOST` è il nome del relativo container (non `127.0.0.1`).

3. Migrare e avviare:

   ```bash
   docker run --rm --env-file .env.production --network app-network \
     paolini/gradient:main alembic upgrade head

   docker run -d --name gradient --restart unless-stopped \
     --env-file .env.production --network app-network \
     -p 127.0.0.1:8000:8000 paolini/gradient:main
   ```

   In alternativa, con `GRADIENT_AUTO_MIGRATE=true` il container applica le migrazioni prima di avviare il server (evitarlo con più container in parallelo).

4. Pubblicare tutto dietro un reverse proxy HTTPS verso la porta 8000. Il proxy deve preservare l'header `Origin`, non mettere in cache le risposte autenticate e non registrare le query di `/auth/callback/`.

Il controllo di salute è `GET /healthz`. L'immagine non crea database né utenti MariaDB e non richiede volumi.
