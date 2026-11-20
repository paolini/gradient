# Indicazioni per gli agenti

## Stato e documentazione

Il progetto è nella fase di definizione dei requisiti e sviluppo del primo prototipo. Leggere [README.md](README.md) prima di proporre modifiche: contiene la descrizione funzionale e le decisioni concordate con il committente.

Mantenere la documentazione in italiano. Distinguere le decisioni accettate dalle proposte e dalle questioni aperte. Aggiornare la documentazione quando vengono concordate nuove decisioni, senza presentare una funzionalità prevista come già implementata.

Per gli errori del database mantenere un messaggio generico al client; scrivere la diagnosi specifica nei log del backend, senza credenziali o dettagli grezzi del driver. Intercettare gli errori operativi riconosciuti e non duplicarne il messaggio con traceback automatici di Strawberry. Conservare invece i traceback per errori imprevisti o non classificati; non disabilitare globalmente il logging degli errori.

## Stato del prototipo e ripresa del lavoro

Sono implementati creazione e lettura di corsi, tabelle e righe studente tramite GraphQL e React, e importazione iniziale CSV con anteprima, associazione delle colonne e salvataggio atomico. La migrazione `0003` aggiunge email e flag DSA/DIS alle righe. Il contratto esportato è in `schema.graphql`. Sono implementati login Google/UniPi tramite OAuth, utenti distinti per provider/subject, sessioni revocabili e isolamento dei corsi per proprietario (migrazione `0004`). Non sono ancora implementati condivisione dei corsi, collegamento account, pannello amministrativo, reimportazioni, colonne personalizzabili, voti, formule o collegamenti tra tabelle.

Al 19 novembre 2026 sono riusciti 58 test senza database, 7 test su MariaDB, controllo dei tipi, compatibilità delle dipendenze e build frontend. Applicate le migrazioni fino a `0004`; `alembic check` non rileva differenze. I flussi OAuth sono testati con provider simulati e sessioni reali su MariaDB; il login reale Google/UniPi richiede ancora client OAuth e callback registrate. Verificati in precedenza nel browser anteprima, importazione del CSV con 14 studenti fittizi e rilettura dopo ricaricamento. I test database includono il rollback della nuova tabella se il salvataggio delle righe fallisce. Non assumere che i servizi locali siano ancora avviati nelle sessioni successive.

Il prossimo lavoro funzionale va concordato con l'utente. Riavviare il backend dopo modifiche a `.env` o alle dipendenze. Non cancellare volumi né alterare utenti del database per risolvere problemi di connessione senza una richiesta specifica.

## Scelte tecniche e aspetti ancora aperti

Le scelte concordate sono MariaDB per il database, Python con FastAPI e Uvicorn per il backend, SQLAlchemy per i modelli e l'accesso al database, Alembic per le migrazioni, GraphQL con Strawberry per l'API e React con Vite per il frontend. Il contratto client/server esplicito è una motivazione centrale della scelta di GraphQL. I provider concordati sono Google e UniPi (WSO2, come MatBit). Il login usa Authlib e HTTPX, con itsdangerous per lo stato OAuth. La distribuzione e ulteriori librerie sono ancora da confermare. Non trattare ulteriori suggerimenti come decisioni approvate. Seguire la richiesta corrente dell'utente per l'implementazione.

La rappresentazione dei dati descritta nel README è logica e non impone tipi o strutture specifici di un database.

Usare uv per dipendenze e ambiente Python, npm per il frontend, un Makefile per i comandi comuni e Docker per l'immagine di deploy. L'utente ha autorizzato il primo flusso con connessione MariaDB, migrazione, creazione e lettura di corsi, tabelle e righe studente tramite GraphQL e interfaccia React.

La base usa Python 3.14 e TypeScript per il frontend. Versionare `uv.lock` e `frontend/package-lock.json`; non versionare `.venv`, `node_modules`, output di build o segreti. Usare `make check`, `make build` e `make test`; `make test-db` verifica separatamente MariaDB e richiede `make migrate`. I test database puliscono soltanto i propri dati temporanei. Esportare il contratto con `make schema` dopo modifiche GraphQL; i test verificano anche le operazioni frontend. Conservare sessioni separate per operazione asincrona, senza condividere un'AsyncSession tra resolver concorrenti.

L'utilizzo previsto è ridotto, con pochissimi utenti. Contenere il consumo di RAM sul server è un obiettivo progettuale da considerare nelle proposte tecniche.

I file di esempio e la documentazione devono rivolgersi a uno sviluppatore generico, usando valori convenzionali come la porta MariaDB 3306. Non adattare i valori predefiniti all'ambiente personale del committente; eventuali configurazioni particolari si gestiscono localmente. Il database di sviluppo può essere gestito esternamente. `compose.example.yaml` è facoltativo; non avviare automaticamente database dai comandi Make. Il backend e Alembic leggono i parametri documentati in `.env.example` tramite pydantic-settings, con precedenza alle variabili d'ambiente. Non mostrare credenziali o modificare configurazioni esterne al repository senza una richiesta specifica. Non applicare migrazioni automaticamente all'avvio del backend.

## Vincoli funzionali da preservare

- Modellare ogni corso come una specifica edizione dell'insegnamento. Distinguere gli utenti che accedono dagli studenti valutati, che possono non avere un account.
- Il corso ha un campo testuale facoltativo per il corso di laurea. Le tabelle hanno data facoltativa senza orario e luogo facoltativo; non richiederli per i record esistenti. Questi campi sono aggiunti dalla migrazione `0002`.
- Consentire tabelle del corso anche senza una prova associata. Ogni riga rappresenta uno studente del corso; collegare le righe tra tabelle tramite identità, non posizione.
- Dare a ogni riga un identificatore interno stabile. La matricola può essere temporaneamente mancante ed è la chiave di collegamento tra tabelle, non la chiave primaria tecnica della riga. Consentire più righe senza matricola, ma non matricole duplicate nella stessa tabella. Trattare una nuova matricola da reimmatricolazione come uno studente distinto; non unire automaticamente righe tramite nome e cognome.
- Conservare nome e cognome come colonne indipendenti della singola tabella: la stessa matricola può avere dati anagrafici differenti tra prove. Non sincronizzarli automaticamente tra tabelle o corsi.
- Prevedere l'upload del CSV degli iscritti scaricato dal sito di ateneo, tipicamente per creare e popolare la tabella della prova, con successive aggiunte e rimozioni di studenti dalla tabella. Le politiche di reimportazione e aggiornamento anagrafico sono ancora aperte.
- L'importer iniziale crea solo nuove tabelle. Il server ricontrolla tutto il file al salvataggio: matricole duplicate ed errori bloccano l'intera importazione, matricole mancanti sono ammesse con avviso. Ignorare colonne non associate e non filtrare automaticamente «Cancellazioni». Il CSV in `examples/` è stato controllato e approvato dall'utente per repository e test; non includere l'originale o altri CSV reali senza autorizzazione.
- Prevedere tabelle configurabili per le singole prove e un riepilogo del corso, anch'esso con colonne personalizzate e calcolate.
- Il riepilogo non è associato a una prova: le colonne collegate ai voti delle prove sono facoltative e riordinabili. Non imporre la presenza di una colonna per ogni esame.
- Conservare DSA e DIS come flag indipendenti nelle tabelle delle prove; la cella vuota significa off. Le eventuali colonne di riepilogo applicano un OR per matricola sulle prove della stessa edizione del corso, indipendentemente dalle colonne voto visibili. Non propagare questi dati agli anni successivi o a un'anagrafica globale.
- Dare priorità alla rapidità di inserimento dei voti in una griglia simile a un foglio di calcolo.
- Rappresentare un voto inserito con valore base decimale esatto e modificatore stringa separato. Conservare il modificatore, senza ridurre il dato al solo valore numerico risultante.
- Accettare inizialmente i soli modificatori `+`, `-` e `L`, uno per voto, lasciando il campo stringa estensibile. Non abilitare implicitamente modificatori futuri.
- Conservare la lode anche quando il suo bonus nei calcoli è zero. Interpretare i modificatori secondo la configurazione della colonna.
- Rifiutare nell'inserimento combinazioni come `7,5+`, ma mantenere nel codice di valutazione la capacità di applicare il modificatore a una base decimale.
- Distinguere il mancante da zero. Trattare un mancante come zero nei calcoli non deve modificare il dato originale.
- Tenere presenza, ritiro, consegna e altri stati in colonne separate dal voto.
- Produrre nelle colonne voto calcolate soltanto numeri o valori mancanti, senza modificatori.
- Applicare arrotondamenti soltanto quando richiesti esplicitamente nelle formule.
- Non introdurre conferma del voto calcolato o sovrascritture manuali: eventuali correzioni si esprimono tramite colonne e formule, per esempio un bonus.
- Lasciare la gestione dei tentativi ripetuti fuori dalla prima fase, salvo nuove indicazioni.

## Metodo di lavoro

Quando una scelta aperta influenza il lavoro richiesto, esplicitarla e discuterla con l'utente anziché trasformarla silenziosamente in un requisito. Per le modifiche documentali verificare la coerenza tra README e queste istruzioni.

## Utenti e accesso

Chiunque completi il login Google o UniPi può accedere, ma può gestire soltanto i propri corsi. Non assegnare automaticamente i corsi preesistenti: `owner_id` mancante li rende invisibili agli utenti, in attesa di assegnazione esplicita. Non unire account tramite email; le identità provider/subject sono indipendenti. La condivisione e i ruoli ulteriori restano aperti.

Tutto GraphQL richiede autenticazione HTTP; ogni POST richiede `X-Gradient-Request: 1` e, se presente, un'Origin corrispondente ad `AUTH_BASE_URL`. I controlli di proprietà in Store coprono corsi, tabelle, righe e importazioni. Conservare sessioni database separate per operazione. I test precedenti di persistenza usano una dipendenza autenticazione sostitutiva; i test dedicati verificano cookie e sessioni reali.

Non copiare credenziali da MatBit. Configurare i client OAuth e le callback come documentato nel README. L'autenticazione usa userinfo HTTPS del provider, state e PKCE; non decodificare ID token senza verificarli. Il logout revoca la sessione locale, non quella del provider.
