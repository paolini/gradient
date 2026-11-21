# Registro delle valutazioni universitarie

Applicazione web per inserire e gestire i risultati di compitini, esami scritti e orali dei corsi universitari. L'obiettivo principale è rendere l'inserimento dei voti rapido e naturale come in un foglio di calcolo, mantenendo dati strutturati e calcoli configurabili.

Il progetto è nella fase di definizione dei requisiti. Le scelte tecniche concordate sono:

- **MariaDB** per il database;
- **Python** per il backend;
- **FastAPI con Uvicorn** per il server HTTP;
- **SQLAlchemy** per i modelli e l'accesso al database;
- **Alembic** per le migrazioni dello schema;
- **GraphQL con Strawberry** per l'API;
- **React con Vite** per il frontend.

Gli strumenti di sviluppo concordati sono **uv** per dipendenze e ambiente Python, **npm** per il frontend, un **Makefile** per i comandi comuni e **Docker** per l'immagine di deploy. Il frontend usa TypeScript. Il Dockerfile costruisce un’immagine di produzione con frontend e backend sulla stessa origine.

Le altre librerie e le modalità di distribuzione sono ancora da confermare. È previsto un utilizzo ridotto, con pochissimi utenti; il contenimento del consumo di RAM sul server è un obiettivo progettuale.

GraphQL è stato scelto in particolare per il contratto client/server esplicito; Strawberry è già stato utilizzato dal committente in un altro progetto. SQLAlchemy e Alembic hanno ruoli complementari: definizione e utilizzo dei modelli il primo, evoluzione versionata dello schema il secondo.

## Accesso

È implementato il login OAuth con **Google** e **UniPi (WSO2)**. Chiunque completi il login può accedere e creare corsi; ciascun utente può leggere e modificare soltanto i propri corsi, comprese tabelle e righe studente. I controlli sono applicati dal backend anche quando vengono inviati direttamente gli ID. La condivisione dei corsi e ulteriori ruoli restano da progettare. Un eventuale accesso per gli studenti sarà discusso in seguito.

L'account viene creato al primo login ed è identificato dalla coppia provider/subject, indipendentemente dagli studenti valutati. Nome ed email sono aggiornati dai dati del provider a ogni accesso. Account Google e UniPi restano separati anche con la stessa email; il collegamento di più identità non è implementato. Per Google è richiesta un'email verificata; per UniPi il profilo è letto dall'endpoint autenticato dell'ateneo, con compatibilità per i campi WSO2 storici, come nell'integrazione di MatBit.

Le sessioni durano sette giorni e sono revocabili: il logout invalida la sessione corrente nel database, senza disconnettere l'account Google/UniPi negli altri servizi. Il cookie applicativo è HttpOnly e SameSite=Lax, Secure su HTTPS; nel database viene conservato soltanto l'hash del token casuale. I token OAuth non vengono conservati. Il flusso usa authorization code, state e PKCE S256 tramite Authlib; l'identità viene letta esclusivamente dall'endpoint HTTPS userinfo, senza utilizzare ID token non verificati. Lo stato temporaneo OAuth scade dopo dieci minuti.

La migrazione `0004` aggiunge utenti, sessioni e proprietario dei corsi. I corsi precedenti sono conservati con proprietario mancante e non sono visibili a nessun account. La loro assegnazione deve essere esplicita, dopo aver identificato il corso e l'ID dell'utente; non vengono attribuiti automaticamente al primo accesso. Non è ancora presente un pannello amministrativo di gestione o assegnazione.

## Entità e organizzazione dei dati

- **Utente**: persona che accede all'applicazione.
- **Corso**: una specifica edizione dell'insegnamento, per esempio Analisi 1 nell'anno accademico 2026/27; raccoglie studenti, tabelle e prove. Comprende il corso di laurea come campo testuale facoltativo.
- **Partecipazione al corso**: collega un utente a un corso con il relativo ruolo. I ruoli e i permessi restano da definire.
- **Studente**: persona valutata, identificata dalla matricola; non richiede un account utente. Un eventuale collegamento a un account sarà discusso con l'accesso degli studenti.
- **Tabella**: appartiene a un corso e contiene colonne configurabili; ogni riga rappresenta uno studente del corso. Tabelle diverse possono includere sottoinsiemi diversi di studenti. I collegamenti tra tabelle usano l'identità dello studente, non la posizione della riga.
- **Prova**: informazioni specifiche di un compitino, scritto o orale, associate a una tabella.

Si possono creare anche tabelle che non corrispondono a prove d'esame. Nella prima fase non sono previste tabelle con righe arbitrarie: una riga rappresenta sempre uno studente.

Ogni tabella può avere una data facoltativa (senza orario) e un luogo facoltativo. I moduli di creazione permettono di inserirli e l'interfaccia li mostra per la tabella selezionata. I campi testuali aggiuntivi ammettono fino a 200 caratteri; vuoti o composti da soli spazi vengono memorizzati come mancanti. La migrazione `0002` aggiunge questi campi senza richiedere valori per i record esistenti; eseguire `make migrate` prima di usare il backend aggiornato.

### Identità degli studenti

Ogni riga ha un **identificatore interno stabile** e rappresenta uno studente anche quando la matricola è temporaneamente sconosciuta. La **matricola è la chiave di collegamento tra tabelle**, non la chiave primaria tecnica della riga. Le righe senza matricola restano indipendenti e non vengono collegate tramite nome e cognome. Inserire una matricola permette di attivare i collegamenti; nella stessa tabella non sono ammesse matricole duplicate, mentre possono esistere più righe senza matricola.

Una reimmatricolazione con una nuova matricola può essere trattata come uno studente distinto, senza ricostruire l'identità della persona tra matricole diverse.

Nome e cognome sono colonne indipendenti della singola tabella e non costituiscono una chiave di identificazione. Lo stesso studente, identificato dalla stessa matricola, può avere nome o cognome differenti in tabelle e prove diverse. Modificare questi dati in una tabella non li aggiorna nelle altre: non è prevista un'anagrafica condivisa che ne imponga la sincronizzazione.

Non unire automaticamente studenti con matricole differenti sulla base di nome e cognome. Le modalità di aggiornamento di queste colonne durante importazioni successive nella stessa tabella restano da definire.

## Importazione degli iscritti

Nel workflow descritto per UniPi, gli studenti si registrano agli esami sul sito di ateneo e il docente scarica l'elenco degli iscritti in formato CSV.

L'importazione iniziale è implementata nel riquadro **Nuova tabella da CSV**. Selezionare un corso, caricare il file e controllare l'anteprima. L'applicazione riconosce le intestazioni UniPi anche dopo le righe introduttive; le associazioni delle colonne possono essere modificate prima del salvataggio. È possibile indicare nome, data e luogo della nuova tabella.

Vengono importati matricola, nome, cognome, email e i due flag indipendenti DSA/DIS. Le altre colonne e le informazioni introduttive non vengono memorizzate; le righe non vengono filtrate automaticamente in base alla colonna «Cancellazioni». DSA e DIS possono leggere colonne distinte, «Documentazione studente» o «Note di sistema»: l'associazione automatica cerca le sigle nelle due colonne e privilegia eventuali colonne DSA/DIS esplicite. Sono riconosciuti vuoto/no/off/0, sì/x/on/1 e le sigle DSA/DIS, anche combinate. Valori non riconosciuti vengono segnalati, senza indovinarne il significato. Delle note associate ai flag viene conservato soltanto il valore booleano, non il testo.

Le matricole restano testuali, con gli zeri iniziali. Quelle mancanti producono avvisi ma sono ammesse; duplicati, flag non riconosciuti, lunghezze eccessive e righe con un numero di celle errato bloccano il salvataggio. Gli errori indicano la riga del file. Il server ricontrolla tutto il CSV al momento dell'importazione e salva tabella e studenti in una sola transazione: un errore annulla l'intera creazione.

Sono supportati UTF-8 (anche con BOM) e Windows-1252, separatori punto e virgola, virgola, tab e pipe, la direttiva iniziale `sep=`, celle tra virgolette e su più righe. Il limite iniziale è di 2 MiB, 5000 studenti e 100 colonne. L'anteprima mostra al massimo 100 studenti, ma valida tutte le righe.

Il [CSV di esempio](examples/unipi-iscritti.csv), con 14 studenti fittizi, è stato controllato e approvato dal committente per il repository e i test. L'originale non è incluso. Il file caricato viene letto in memoria e non viene conservato né scritto nei log. L'anteprima non richiede accesso al database.

L'importazione crea sempre una nuova tabella: reimportazioni e aggiornamenti di tabelle esistenti restano da progettare. È già possibile aggiungere manualmente studenti; rimozioni e modifica dei flag dall'interfaccia restano da implementare. La futura rimozione da una tabella non dovrà cancellare i dati nelle altre tabelle.

## Tabelle delle prove

Ogni prova può avere una propria tabella, con studenti sulle righe e colonne personalizzabili. Le prove possono avere strutture differenti e informazioni specifiche dell'esame.

Le colonne possono rappresentare voti e punteggi dei singoli esercizi, risposte, informazioni testuali, flag e risultati calcolati. Flag separati possono indicare, per esempio, se uno studente era prenotato, presente o ha consegnato.

Per le prove a risposta multipla, un codice compito identifica la variante e permette di determinare le risposte corrette e calcolare il risultato. I dettagli delle regole di correzione restano da definire.

L'interfaccia deve favorire l'inserimento rapido da tastiera e il lavoro su una griglia simile a un foglio di calcolo. Le interazioni precise saranno definite durante la progettazione.

## Riepilogo del corso

Ogni corso deve avere una tabella riassuntiva non associata a una prova. Le sue colonne sono configurabili: il docente può scegliere quali risultati delle prove includere, in quale ordine, e aggiungere colonne personalizzate e calcolate, per esempio la media di tre compitini. Non è obbligatoria una colonna per ogni prova. È prevista la possibilità di aggiungere rapidamente colonne collegate ai risultati delle prove; i dettagli dell'interazione restano da definire.

I risultati delle prove devono alimentare il riepilogo senza richiedere di reinserire i voti. Il criterio con cui una prova espone il proprio risultato al riepilogo è ancora da definire.

Si distinguono colonne con dati inseriti direttamente, colonne collegate ad altre tabelle tramite matricola e colonne calcolate. Rimuovere una colonna collegata dal riepilogo non elimina la prova di origine. Anche nome e cognome nel riepilogo sono locali alla tabella; la loro inizializzazione resta da definire.

### Flag DSA e DIS

DSA e DIS sono due flag indipendenti nelle tabelle delle singole prove, provenienti dalle iscrizioni. Sono on/off: una cella vuota rappresenta off, non uno stato sconosciuto.

Il riepilogo può includere colonne facoltative che aggregano separatamente questi flag con un OR: il risultato è on se almeno una prova del corso ha il flag attivo per quella matricola. Si considerano le prove della specifica edizione del corso, anche quando le loro colonne voto non sono presenti nel riepilogo. Non si trasferiscono i flag automaticamente agli anni successivi e non si memorizza un ulteriore flag anagrafico globale. L'aggregazione riflette i dati correnti delle prove.

## Il tipo di dato voto

Un voto inserito è composto da:

- un valore numerico base decimale esatto, che può essere mancante;
- una stringa modificatore, vuota quando non è presente.

Questa è una rappresentazione logica: il formato fisico di memorizzazione in MariaDB resta da definire.

| Inserimento | Valore base | Modificatore |
| --- | --- | --- |
| `7` | 7 | vuoto |
| `7,5` | 7.5 | vuoto |
| `7+` | 7 | `+` |
| `8-` | 8 | `-` |
| `30L` o `30 e lode` | 30 | `L` |
| cella vuota | mancante | vuoto |

### Modificatori

Inizialmente l'inserimento ammette un solo modificatore tra `+`, `-` e `L`. Il campo deve rimanere una stringa capace di rappresentare estensioni future, come `++`, senza cambiare la struttura del voto. Queste estensioni non sono ancora abilitate né definite. Combinazioni come `30L+` non sono previste.

Il significato numerico dei modificatori è configurabile per colonna:

- `+` aggiunge un incremento, inizialmente un terzo di punto;
- `-` sottrae un incremento, inizialmente un terzo di punto;
- `L` indica la lode e aggiunge un bonus configurabile, anche zero. Per esempio, `30L` può contribuire ai calcoli come 30, 31 o 33.

La lode deve essere conservata indipendentemente dal suo bonus numerico. Anche gli altri modificatori restano distinti dal valore base: `7+` non viene sostituito in memoria con la sola approssimazione numerica del risultato.

L'inserimento di un modificatore su una base decimale, come `7,5+`, deve essere rifiutato. Il codice di valutazione deve comunque saper interpretare una simile combinazione qualora venga ricevuta: nell'esempio, come `7.5 + 1/3`.

### Scala e limiti

La scala appartiene alla configurazione della colonna. Deve poter indicare:

- il massimo di riferimento per i riscalamenti, per esempio 5, 10 o 30;
- se consentire valori sopra il massimo;
- se consentire valori negativi;
- una soglia di sufficienza, utilizzabile anche per la visualizzazione a colori; tipicamente 6 su 10.

Sono ammessi voti decimali. I dettagli della configurazione delle scale possono essere affinati in seguito.

### Valori mancanti

Un voto mancante è sempre distinto da uno zero. Non codifica il motivo della mancanza: assenza, ritiro, presenza e consegna possono essere descritti in altre colonne.

Deve essere possibile configurare una colonna affinché un voto mancante venga trattato come zero nei calcoli, senza trasformare il dato memorizzato in zero.

Per le formule si prevedono tre possibili comportamenti:

- il vuoto conta come zero;
- il vuoto viene escluso dal conteggio;
- il vuoto rende mancante il risultato.

La sintassi e la relazione tra configurazione della colonna e comportamento della singola formula saranno definite in seguito.

## Colonne calcolate

Le formule devono permettere calcoli tra colonne, aggregazioni come le medie e riscalamenti. Sintassi e funzioni disponibili sono ancora da definire.

Le colonne voto calcolate restituiscono esclusivamente un numero oppure un valore mancante: **non possono avere modificatori**, nemmeno la lode. Quando una formula legge un voto inserito, usa il suo valore numerico comprensivo dell'effetto del modificatore configurato.

Gli arrotondamenti devono essere richiesti esplicitamente dal docente nella formula; non costituiscono una trasformazione automatica implicita del voto.

Un voto calcolato non richiede conferma manuale. Per modificarne il risultato, il docente può aggiungere una colonna, per esempio un bonus, e includerla nella formula. Non è previsto un meccanismo di sovrascrittura manuale della cella calcolata.

## Perimetro iniziale e questioni aperte

La gestione dei tentativi ripetuti non rientra nella prima fase. Quando sarà affrontata, il riferimento abituale indicato è l'ultimo tentativo, ma la regola non è ancora progettata.

Restano da discutere in particolare:

- stack applicativo, librerie e schema del database MariaDB;
- condivisione dei corsi, ruoli e collegamento tra identità di accesso;
- eventuale accesso degli studenti;
- reimportazioni e aggiornamento dei dati anagrafici nelle tabelle esistenti;
- configurazione delle colonne e delle informazioni delle prove;
- formule, precisione dei calcoli e gestione dei valori mancanti;
- correzione delle risposte multiple e varianti dei compiti;
- comportamento dettagliato della griglia e modalità di salvataggio.

Le funzionalità ancora da discutere non vanno considerate già progettate o implementate.

## Indicazioni per chi contribuisce

Questo file descrive il progetto e le decisioni funzionali concordate. Le istruzioni operative per gli agenti sono in [AGENTS.md](AGENTS.md).

### Ambiente di sviluppo

Il primo flusso applicativo consente di creare corsi, tabelle e righe studente e consultarli tramite GraphQL e interfaccia React. Le righe hanno ID stabili, matricole facoltative e nome/cognome locali alla tabella. L'importazione iniziale CSV è implementata con anteprima, associazione delle colonne e salvataggio atomico. La migrazione `0003` aggiunge email e flag DSA/DIS alle righe esistenti, inizialmente vuoti/off. Il login Google/UniPi, le sessioni e l’isolamento dei corsi per proprietario sono implementati con la migrazione `0004`. Colonne custom, voti e formule non sono ancora implementati.

Stato delle verifiche al 20 novembre 2026: passati 71 test senza database, controllo dei tipi, compatibilità delle dipendenze e build frontend. I 7 test su MariaDB sono passati nella verifica precedente del login. Costruita e avviata l’immagine Docker: verificati utente non root, migrazioni incluse, frontend, asset, controllo di salute e protezione GraphQL, senza connessione al database. La migrazione fino a `0004` è stata applicata al database locale e `alembic check` non rileva differenze rispetto ai modelli. Verificati in precedenza nel browser caricamento, anteprima, importazione dei 14 studenti fittizi e permanenza dei dati dopo il ricaricamento. I nuovi flussi OAuth sono verificati con provider simulati; il login reale Google/UniPi richiede ancora credenziali e callback registrate. I test MariaDB verificano sessioni reali, separazione degli account con la stessa email, isolamento dei corsi, logout e scadenza.

Le dipendenze Python sono in `pyproject.toml` e `uv.lock`; il frontend React/Vite è in `frontend/`, con il proprio lockfile npm. I modelli e il backend sono nel pacchetto `gradient/`, le migrazioni in `migrations/`. Il pool del backend ha un massimo di due connessioni per processo.

Prerequisiti: Python 3.14, uv e Node.js compatibile con la versione di Vite installata. Per questa inizializzazione sono stati usati Python 3.14.4 e Node.js 22.23.4. Docker e Make servono rispettivamente per l’immagine di produzione e per i comandi comuni.

Se uv è installato in `~/.local/bin` ma non è nel PATH del terminale corrente, eseguire:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Dalla radice del repository:

```bash
make install  # uv sync --locked e npm ci per il frontend
make check    # compatibilità delle dipendenze Python e controllo dei tipi frontend
make build    # compilazione del frontend in frontend/dist
make migrate  # applica le migrazioni alla MariaDB configurata in .env
make backend  # FastAPI su http://127.0.0.1:8000
# In un secondo terminale:
make dev      # frontend; aprire l'indirizzo indicato da Vite
```

Non è necessario attivare manualmente `.venv`: i comandi Python del progetto si eseguono tramite `uv run`. Il Makefile consente di sovrascrivere `UV` e `NPM` se gli eseguibili sono installati in percorsi differenti. Le migrazioni locali vengono eseguite esplicitamente; il container consente di abilitarle all’avvio con `GRADIENT_AUTO_MIGRATE=true`.

Vite inoltra `/graphql` e `/auth` al backend locale, senza richiedere configurazioni CORS. Per provare il flusso, configurare almeno un provider, accedere, creare un corso, una tabella e uno studente e ricaricare la pagina. Tutte le richieste GraphQL richiedono una sessione autenticata; le POST richiedono l'intestazione `X-Gradient-Request: 1`. L'editor GraphQL può essere aperto tramite l'origine frontend dopo il login, configurando questa intestazione. Anche il logout richiede una POST con la stessa intestazione. Non è previsto un accesso anonimo di sviluppo.

### Configurazione del login

Configurare in `.env` i parametri descritti in [.env.example](.env.example):

- `AUTH_BASE_URL`: origine pubblica unica dell'applicazione, in sviluppo `http://127.0.0.1:5173`. Aprire esattamente questa origine; HTTPS è obbligatorio fuori da localhost.
- `AUTH_SECRET`: segreto casuale di almeno 32 caratteri per lo stato temporaneo OAuth, per esempio generato con `uv run python -c "import secrets; print(secrets.token_urlsafe(48))"` e salvato soltanto nella configurazione locale.
- `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET`: credenziali di un client OAuth web Google.
- `WSO2_CLIENT_ID` e `WSO2_CLIENT_SECRET`: credenziali del client autorizzato da UniPi; gli endpoint predefiniti sono quelli di WSO2 in MatBit, sovrascrivibili con le variabili `WSO2_*_URL` documentate.

Registrare presso i provider le callback esatte `${AUTH_BASE_URL}/auth/callback/google` e `${AUTH_BASE_URL}/auth/callback/unipi` (sostituire la variabile con il valore effettivo). Abilitare authorization code e PKCE S256, con scope `openid email profile`. Il backend scambia il codice con autenticazione del client HTTP Basic e legge il profilo userinfo. Servono credenziali valide e una configurazione consentita dal provider: i test con provider simulati non sostituiscono la verifica reale, in particolare per le impostazioni del client UniPi.

Solo i provider completamente configurati vengono mostrati nella pagina di accesso. Senza provider non è possibile accedere ai dati. Le credenziali di MatBit non vengono copiate automaticamente. Riavviare il backend dopo modifiche alla configurazione o alle dipendenze. In produzione il proxy dovrà pubblicare `/auth` e `/graphql` sulla stessa origine del frontend, senza cache delle risposte autenticate.

### Contratto GraphQL e verifiche

Il contratto è dichiarato in `gradient/schema.py` ed esportato in [schema.graphql](schema.graphql) con `make schema`. Le operazioni frontend sono in `frontend/src/api.ts`; i test ne verificano la validità rispetto allo schema e controllano che il file esportato sia aggiornato.

Le operazioni disponibili sono `me` (profilo autenticato), `courses`, `courseTables`, `studentRows`, `createCourse`, `createTable`, `addStudent`, `previewCsv` e `importCsvTable`. Le mutazioni restituiscono errori di validazione con campo, codice e messaggio; l'importazione indica anche la riga del CSV. Gli ID inesistenti e le matricole duplicate vengono segnalati senza salvare righe parziali. Gli anni accademici accettano `2026/27` o `2026/2027` e vengono memorizzati nella forma completa. Matricole vuote diventano valori mancanti, mentre gli zeri iniziali vengono conservati.

Le risposte GraphQL mantengono un messaggio generico e il codice `DATABASE_ERROR` per gli errori database. La diagnosi specifica viene scritta soltanto nei log del backend: servizio non raggiungibile, connessione interrotta, credenziali errate, permessi insufficienti, database inesistente o struttura da inizializzare o aggiornare. Se MariaDB non è raggiungibile, il log invita a verificare avvio, host e porta; `make migrate` viene suggerito quando mancano tabelle o colonne. Questi messaggi diagnostici non riportano credenziali o dettagli grezzi del driver.

Gli errori operativi riconosciuti sono intercettati e producono un solo messaggio diagnostico, senza il traceback automatico di Strawberry. Gli errori imprevisti o non classificati mantengono il traceback per consentire il debug.

```bash
make test     # contratto, configurazione e validazione, senza database
make test-db  # integrazione sulla MariaDB configurata, dopo make migrate
```

I test di integrazione creano corsi temporanei e rimuovono soltanto i propri dati al termine. Verificano persistenza tra istanze dell'applicazione, unicità delle matricole per tabella, più matricole mancanti e indipendenza dei nomi tra tabelle. Non creano né avviano database. Utilizzare un database di sviluppo.

Gli ambienti locali, `node_modules`, i risultati delle compilazioni e i file `.env` sono esclusi dal versionamento. I lockfile vanno invece conservati in Git.

### Produzione con Docker

Il [Dockerfile](Dockerfile) compila il frontend con Node e installa le dipendenze Python con `uv sync --locked --no-dev`. L'immagine finale contiene Python 3.14, ambiente virtuale, codice backend, migrazioni e frontend compilato; non contiene Node, dipendenze di sviluppo o configurazione locale. Il processo gira con UID/GID 10001, senza privilegi root, sulla porta 8000. Un singolo worker Uvicorn contiene il consumo di RAM e mantiene il pool a un massimo di due connessioni database.

```bash
make docker-build                         # gradient:latest
# Oppure un tag scelto per il rilascio:
make docker-build IMAGE=gradient:1.0
```

Preparare sul server un file `.env.production` con le variabili di [.env.example](.env.example), credenziali effettive e un `AUTH_SECRET` casuale persistente. Impostare `AUTH_BASE_URL=https://gradient.example.org` e registrare le due callback OAuth su questa origine. Passare la configurazione soltanto all'esecuzione con `--env-file`: i file `.env*`, l'ambiente Python locale e gli output frontend locali sono esclusi dal contesto di build.

MariaDB deve essere già disponibile. Nell'esempio seguente `app-network` è una rete Docker esistente condivisa con il database, e `DB_HOST` deve essere il nome DNS del relativo servizio/container, con `DB_PORT=3306`. `127.0.0.1` dentro il container identifica il container stesso. Si può usare anche un database esterno raggiungibile dalla rete del container, configurandone host e porta.

Prima di avviare la nuova versione, eseguire le migrazioni con la stessa immagine e configurazione:

```bash
docker run --rm --env-file .env.production --network app-network \
  gradient:latest alembic upgrade head

docker run -d --name gradient --restart unless-stopped \
  --env-file .env.production --network app-network \
  -p 127.0.0.1:8000:8000 gradient:latest
```

In alternativa al comando di migrazione separato, impostare `GRADIENT_AUTO_MIGRATE=true` in `.env.production` oppure aggiungere `-e GRADIENT_AUTO_MIGRATE=true` a `docker run`. L'entrypoint esegue `alembic upgrade head` prima di Uvicorn e avvia il server soltanto se la migrazione riesce. Il valore predefinito è `false`; sono accettati anche `1`/`0` e `TRUE`/`FALSE`. Un valore diverso interrompe l'avvio con un messaggio di configurazione. La variabile è letta dall'ambiente del container, non dal file `.env` tramite pydantic-settings.

Le migrazioni automatiche si applicano soltanto quando il comando da avviare è `uvicorn`. I comandi manuali, incluso `alembic upgrade head`, vengono eseguiti direttamente, anche se la variabile è abilitata. In caso di più container applicativi, eseguire la migrazione una sola volta come passo separato, evitando avvii concorrenti con migrazioni automatiche.

L'immagine non crea database o utenti MariaDB. I dati persistono nel database; il container applicativo non richiede volumi. Il comando `alembic upgrade head` sostituisce il comando di avvio predefinito.

Pubblicare l'intera applicazione dietro un reverse proxy HTTPS che inoltri tutte le richieste alla porta 8000: `/` e `/assets` servono React, `/auth` gestisce il login e `/graphql` l'API. Se il proxy è un altro container, collegarlo alla stessa rete e usare `gradient:8000`; in quel caso la pubblicazione della porta sull'host è facoltativa. Il proxy deve preservare l'header `Origin` e non memorizzare in cache le risposte autenticate. Le intestazioni proxy non sono considerate da Uvicorn: URL OAuth e sicurezza dei cookie dipendono da `AUTH_BASE_URL`, configurato esplicitamente. I log di accesso Uvicorn sono disabilitati per evitare di registrare i codici nelle query delle callback; configurare allo stesso modo il proxy per `/auth/callback/`.

Il controllo Docker interroga `GET /healthz`, che verifica la risposta del processo senza accedere al database. Uno stato healthy non garantisce la raggiungibilità di MariaDB, lo stato delle migrazioni o la configurazione OAuth. In sviluppo, se `frontend/dist` non esiste, il backend espone soltanto API e controllo di salute; continuare a usare Vite con `make dev`.

### Pubblicazione automatica su Docker Hub

Il workflow [.github/workflows/docker.yml](.github/workflows/docker.yml) costruisce e pubblica l'immagine a ogni push sul branch `main`. La versione del codice è `project.version` in `pyproject.toml`, attualmente `0.1.0`. Il workflow legge questo campo e pubblica la stessa build con entrambi i tag:

- `paolini/gradient:v0.1.0` (oppure `v1.2.3` quando la versione del codice sarà `1.2.3`);
- `paolini/gradient:main`.

La versione deve avere il formato `X.Y.Z`; una versione non valida interrompe il workflow prima della pubblicazione. Aggiornare la versione in `pyproject.toml` e il relativo `uv.lock` per ogni nuovo rilascio. Push successivi con la stessa versione aggiornano anche il relativo tag versionato; non sono tag immutabili. Il tag `main` segue l'ultima build pubblicata del branch. La piattaforma costruita è `linux/amd64`.

Configurare in GitHub, **Settings → Secrets and variables → Actions**, i repository secret `DOCKERHUB_USERNAME` (utente con accesso al repository Docker Hub `paolini/gradient`) e `DOCKERHUB_TOKEN` (token Docker Hub con permesso di scrittura). Nessuna credenziale è contenuta nel workflow. Le action sono fissate a commit specifici; Buildx utilizza la cache di GitHub Actions. Il flusso segue le [action ufficiali Docker](https://docs.docker.com/build/ci/github-actions/).

La pubblicazione effettiva richiede che questi file, incluso il Dockerfile, siano presenti su `main` e che i secret siano configurati. Il workflow non distribuisce né riavvia l'applicazione sul server.

### MariaDB nello sviluppo locale

Il backend si collega a una MariaDB esterna, anche gestita tramite un Compose condiviso con altri progetti. Il repository non impone di avviare un proprio database: i comandi Make non avviano container.

Il file [.env.example](.env.example) descrive i parametri di connessione. Il backend e Alembic leggono `.env` dalla radice del repository; le variabili d'ambiente hanno precedenza. Dopo una modifica a `.env`, riavviare il backend. Per usare un'istanza esistente, creare un database e un utente dedicati a Gradient e configurare in `.env` host, porta, nome del database e credenziali. Non serve modificare o usare il Compose di esempio. Il backend usa il driver asincrono `asyncmy` e non richiede la password root.

Per chi non ha già un database, [compose.example.yaml](compose.example.yaml) fornisce un esempio facoltativo con MariaDB 11.8, database e utente applicativo dedicati, volume persistente e controllo di disponibilità:

```bash
cp .env.example .env
docker compose -p gradient-dev -f compose.example.yaml up -d --wait
```

Le password nel file di esempio sono soltanto per lo sviluppo locale. Il database è pubblicato su `127.0.0.1:3306` per l'applicazione eseguita sull'host. Se questa porta è occupata, cambiare `DB_PORT` nel file `.env`. `DB_HOST` descrive l'indirizzo raggiunto dal backend: non cambia il binding del container, che resta limitato a localhost. Se il backend verrà eseguito in un container, host e porta andranno adattati alla rete Docker.

È possibile copiare il servizio e la relativa dichiarazione del volume nel proprio Compose condiviso, adattando variabili e porta. I valori `MARIADB_*` creano database e utenti soltanto quando il volume è inizialmente vuoto: modificare `.env` in seguito non cambia automaticamente le credenziali o il database esistenti.

Per fermare e rimuovere i container dell'esempio conservando il volume con i dati:

```bash
docker compose -p gradient-dev -f compose.example.yaml down
```

Questo file non rappresenta la configurazione di deploy e non avvia il backend o il frontend.
