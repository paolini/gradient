# Registro delle valutazioni universitarie

Applicazione web per inserire e gestire i risultati di compitini, esami scritti e orali dei corsi universitari. L'obiettivo principale è rendere l'inserimento dei voti rapido e naturale come in un foglio di calcolo, mantenendo dati strutturati e calcoli configurabili.

Il progetto è nella fase di definizione dei requisiti. Le scelte tecniche concordate sono:

- **MariaDB** per il database;
- **Python** per il backend;
- **SQLAlchemy** per i modelli e l'accesso al database;
- **Alembic** per le migrazioni dello schema;
- **GraphQL con Strawberry** per l'API;
- **React con Vite** per il frontend.

Gli strumenti di sviluppo concordati sono **uv** per dipendenze e ambiente Python, **npm** per il frontend, un **Makefile** per i comandi comuni e **Docker** per l'immagine di deploy. La preparazione dell'immagine e i relativi comandi devono ancora essere implementati.

Il framework web, le altre librerie e le modalità di distribuzione sono ancora da confermare. È previsto un utilizzo ridotto, con pochissimi utenti; il contenimento del consumo di RAM sul server è un obiettivo progettuale.

GraphQL è stato scelto in particolare per il contratto client/server esplicito; Strawberry è già stato utilizzato dal committente in un altro progetto. SQLAlchemy e Alembic hanno ruoli complementari: definizione e utilizzo dei modelli il primo, evoluzione versionata dello schema il secondo.

## Accesso

L'applicazione deve prevedere l'autenticazione OAuth per i docenti. Il provider di autenticazione è da definire. Un eventuale accesso per gli studenti sarà discusso in seguito.

## Entità e organizzazione dei dati

- **Utente**: persona che accede all'applicazione.
- **Corso**: una specifica edizione dell'insegnamento, per esempio Analisi 1 nell'anno accademico 2026/27; raccoglie studenti, tabelle e prove.
- **Partecipazione al corso**: collega un utente a un corso con il relativo ruolo. I ruoli e i permessi restano da definire.
- **Studente**: persona valutata, identificata dalla matricola; non richiede un account utente. Un eventuale collegamento a un account sarà discusso con l'accesso degli studenti.
- **Tabella**: appartiene a un corso e contiene colonne configurabili; ogni riga rappresenta uno studente del corso. Tabelle diverse possono includere sottoinsiemi diversi di studenti. I collegamenti tra tabelle usano l'identità dello studente, non la posizione della riga.
- **Prova**: informazioni specifiche di un compitino, scritto o orale, associate a una tabella.

Si possono creare anche tabelle che non corrispondono a prove d'esame. Nella prima fase non sono previste tabelle con righe arbitrarie: una riga rappresenta sempre uno studente.

### Identità degli studenti

Ogni riga ha un **identificatore interno stabile** e rappresenta uno studente anche quando la matricola è temporaneamente sconosciuta. La **matricola è la chiave di collegamento tra tabelle**, non la chiave primaria tecnica della riga. Le righe senza matricola restano indipendenti e non vengono collegate tramite nome e cognome. Inserire una matricola permette di attivare i collegamenti; nella stessa tabella non sono ammesse matricole duplicate, mentre possono esistere più righe senza matricola.

Una reimmatricolazione con una nuova matricola può essere trattata come uno studente distinto, senza ricostruire l'identità della persona tra matricole diverse.

Nome e cognome sono colonne indipendenti della singola tabella e non costituiscono una chiave di identificazione. Lo stesso studente, identificato dalla stessa matricola, può avere nome o cognome differenti in tabelle e prove diverse. Modificare questi dati in una tabella non li aggiorna nelle altre: non è prevista un'anagrafica condivisa che ne imponga la sincronizzazione.

Non unire automaticamente studenti con matricole differenti sulla base di nome e cognome. Le modalità di aggiornamento di queste colonne durante importazioni successive nella stessa tabella restano da definire.

## Importazione degli iscritti

Nel workflow descritto per UniPi, gli studenti si registrano agli esami sul sito di ateneo e il docente scarica l'elenco degli iscritti in formato CSV.

L'applicazione deve consentire l'upload di questo CSV. Tipicamente l'importazione coincide con la creazione della tabella della prova e ne popola le righe con gli studenti registrati. Deve essere possibile aggiungere o rimuovere studenti dalla tabella anche in seguito.

L'identificazione degli studenti importati avviene tramite matricola. Il formato del CSV, la gestione dei duplicati, delle importazioni successive e dei conflitti anagrafici restano da definire. La rimozione da una tabella non implica la cancellazione dello studente dalle altre tabelle o dal corso.

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
- provider di autenticazione e autorizzazioni sui corsi;
- eventuale accesso degli studenti;
- formato CSV e regole per duplicati, reimportazioni e aggiornamento dei dati anagrafici;
- configurazione delle colonne e delle informazioni delle prove;
- formule, precisione dei calcoli e gestione dei valori mancanti;
- correzione delle risposte multiple e varianti dei compiti;
- comportamento dettagliato della griglia e modalità di salvataggio.

Le funzionalità ancora da discutere non vanno considerate già progettate o implementate.

## Indicazioni per chi contribuisce

Questo file descrive il progetto e le decisioni funzionali concordate. Le istruzioni operative per gli agenti sono in [AGENTS.md](AGENTS.md).
