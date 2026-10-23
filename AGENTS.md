# Indicazioni per gli agenti

## Stato e documentazione

Il progetto è nella fase di definizione dei requisiti. Leggere [README.md](README.md) prima di proporre modifiche: contiene la descrizione funzionale e le decisioni concordate con il committente.

Mantenere la documentazione in italiano. Distinguere le decisioni accettate dalle proposte e dalle questioni aperte. Aggiornare la documentazione quando vengono concordate nuove decisioni, senza presentare una funzionalità prevista come già implementata.

## Scelte tecniche e aspetti ancora aperti

Le scelte concordate sono MariaDB per il database, Python per il backend, SQLAlchemy per i modelli e l'accesso al database, Alembic per le migrazioni, GraphQL con Strawberry per l'API e React con Vite per il frontend. Il contratto client/server esplicito è una motivazione centrale della scelta di GraphQL. Il framework web, le altre librerie, il provider di autenticazione e la distribuzione sono ancora da confermare. Non trattare ulteriori suggerimenti come decisioni approvate. Non introdurre dipendenze, infrastruttura o un'implementazione sulla base di questo documento: seguire la richiesta corrente dell'utente.

La rappresentazione dei dati descritta nel README è logica e non impone tipi o strutture specifici di un database.

L'utilizzo previsto è ridotto, con pochissimi utenti. Contenere il consumo di RAM sul server è un obiettivo progettuale da considerare nelle proposte tecniche.

## Vincoli funzionali da preservare

- Prevedere tabelle configurabili per le singole prove e un riepilogo del corso, anch'esso con colonne personalizzate e calcolate.
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
