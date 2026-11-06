# Indicazioni per gli agenti

## Stato e documentazione

Il progetto è nella fase di definizione dei requisiti e sviluppo del primo prototipo. Leggere [README.md](README.md) prima di proporre modifiche: contiene la descrizione funzionale e le decisioni concordate con il committente.

Mantenere la documentazione in italiano. Distinguere le decisioni accettate dalle proposte e dalle questioni aperte. Aggiornare la documentazione quando vengono concordate nuove decisioni, senza presentare una funzionalità prevista come già implementata.

## Stato del prototipo e ripresa del lavoro

È presente il codice del primo flusso: configurazione MariaDB da `.env`, modelli e migrazione iniziale per corsi, tabelle e righe studente, API GraphQL di creazione e lettura e interfaccia React. Il contratto esportato è in `schema.graphql`. Non sono ancora implementati autenticazione, importazione CSV, colonne personalizzabili, voti, formule o collegamenti tra tabelle.

Alla ricognizione del 6 novembre 2026, le ultime verifiche riuscite erano quattro test senza database, controllo dei tipi frontend, compatibilità delle dipendenze e build frontend. Non considerare ancora verificato il flusso completo: l'applicazione della migrazione e i test di integrazione MariaDB non sono stati completati con successo. I precedenti tentativi erano stati ostacolati dalla configurazione locale, anche perché sulla porta 3306 rispondeva un container MySQL. L'utente ha poi comunicato di aver fermato MySQL e avviato MariaDB con la porta pubblicata; occorre verificare nuovamente lo stato effettivo senza assumere che i servizi siano ancora avviati.

Il prossimo lavoro funzionale è applicare la migrazione con `make migrate`, eseguire `make test-db` e verificare nel browser creazione e rilettura dei dati dopo un ricaricamento. Riavviare il backend dopo modifiche a `.env` o alle dipendenze. Non cancellare volumi né alterare utenti del database per risolvere problemi di connessione senza una richiesta specifica.

## Scelte tecniche e aspetti ancora aperti

Le scelte concordate sono MariaDB per il database, Python con FastAPI e Uvicorn per il backend, SQLAlchemy per i modelli e l'accesso al database, Alembic per le migrazioni, GraphQL con Strawberry per l'API e React con Vite per il frontend. Il contratto client/server esplicito è una motivazione centrale della scelta di GraphQL. Le altre librerie, il provider di autenticazione e la distribuzione sono ancora da confermare. Non trattare ulteriori suggerimenti come decisioni approvate. Seguire la richiesta corrente dell'utente per l'implementazione.

La rappresentazione dei dati descritta nel README è logica e non impone tipi o strutture specifici di un database.

Usare uv per dipendenze e ambiente Python, npm per il frontend, un Makefile per i comandi comuni e Docker per l'immagine di deploy. L'utente ha autorizzato il primo flusso con connessione MariaDB, migrazione, creazione e lettura di corsi, tabelle e righe studente tramite GraphQL e interfaccia React.

La base usa Python 3.14 e TypeScript per il frontend. Versionare `uv.lock` e `frontend/package-lock.json`; non versionare `.venv`, `node_modules`, output di build o segreti. Usare `make check`, `make build` e `make test`; `make test-db` verifica separatamente MariaDB e richiede `make migrate`. I test database puliscono soltanto i propri dati temporanei. Esportare il contratto con `make schema` dopo modifiche GraphQL; i test verificano anche le operazioni frontend. Conservare sessioni separate per operazione asincrona, senza condividere un'AsyncSession tra resolver concorrenti.

L'utilizzo previsto è ridotto, con pochissimi utenti. Contenere il consumo di RAM sul server è un obiettivo progettuale da considerare nelle proposte tecniche.

I file di esempio e la documentazione devono rivolgersi a uno sviluppatore generico, usando valori convenzionali come la porta MariaDB 3306. Non adattare i valori predefiniti all'ambiente personale del committente; eventuali configurazioni particolari si gestiscono localmente. Il database di sviluppo può essere gestito esternamente. `compose.example.yaml` è facoltativo; non avviare automaticamente database dai comandi Make. Il backend e Alembic leggono i parametri documentati in `.env.example` tramite pydantic-settings, con precedenza alle variabili d'ambiente. Non mostrare credenziali o modificare configurazioni esterne al repository senza una richiesta specifica. Non applicare migrazioni automaticamente all'avvio del backend.

## Vincoli funzionali da preservare

- Modellare ogni corso come una specifica edizione dell'insegnamento. Distinguere gli utenti che accedono dagli studenti valutati, che possono non avere un account.
- Consentire tabelle del corso anche senza una prova associata. Ogni riga rappresenta uno studente del corso; collegare le righe tra tabelle tramite identità, non posizione.
- Dare a ogni riga un identificatore interno stabile. La matricola può essere temporaneamente mancante ed è la chiave di collegamento tra tabelle, non la chiave primaria tecnica della riga. Consentire più righe senza matricola, ma non matricole duplicate nella stessa tabella. Trattare una nuova matricola da reimmatricolazione come uno studente distinto; non unire automaticamente righe tramite nome e cognome.
- Conservare nome e cognome come colonne indipendenti della singola tabella: la stessa matricola può avere dati anagrafici differenti tra prove. Non sincronizzarli automaticamente tra tabelle o corsi.
- Prevedere l'upload del CSV degli iscritti scaricato dal sito di ateneo, tipicamente per creare e popolare la tabella della prova, con successive aggiunte e rimozioni di studenti dalla tabella. Le politiche di reimportazione e aggiornamento anagrafico sono ancora aperte.
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
