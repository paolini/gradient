# Registro delle valutazioni universitarie

Applicazione web per inserire e gestire i risultati di compitini, esami scritti e orali dei corsi universitari. L'obiettivo principale è rendere l'inserimento dei voti rapido e naturale come in un foglio di calcolo, mantenendo dati strutturati e calcoli configurabili.

Il progetto è nella fase di definizione dei requisiti. Database, linguaggi, framework, librerie e modalità di distribuzione sono ancora da scegliere.

## Accesso

L'applicazione deve prevedere l'autenticazione OAuth per i docenti. Il provider di autenticazione è da definire. Un eventuale accesso per gli studenti sarà discusso in seguito.

## Tabelle delle prove

Ogni prova può avere una propria tabella, con studenti sulle righe e colonne personalizzabili. Le prove possono avere strutture differenti e informazioni specifiche dell'esame.

Le colonne possono rappresentare voti e punteggi dei singoli esercizi, risposte, informazioni testuali, flag e risultati calcolati. Flag separati possono indicare, per esempio, se uno studente era prenotato, presente o ha consegnato.

Per le prove a risposta multipla, un codice compito identifica la variante e permette di determinare le risposte corrette e calcolare il risultato. I dettagli delle regole di correzione restano da definire.

L'interfaccia deve favorire l'inserimento rapido da tastiera e il lavoro su una griglia simile a un foglio di calcolo. Le interazioni precise saranno definite durante la progettazione.

## Riepilogo del corso

Ogni corso deve avere una tabella riassuntiva con una colonna per ogni prova d'esame. Anche il riepilogo può contenere colonne personalizzate e colonne calcolate, per esempio la media dei compitini.

I risultati delle prove devono alimentare il riepilogo senza richiedere di reinserire i voti. Il criterio con cui una prova espone il proprio risultato al riepilogo è ancora da definire.

## Il tipo di dato voto

Un voto inserito è composto da:

- un valore numerico base decimale esatto, che può essere mancante;
- una stringa modificatore, vuota quando non è presente.

Questa è una rappresentazione logica: non stabilisce il database o il formato fisico di memorizzazione.

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

- database, stack applicativo e librerie;
- provider di autenticazione e autorizzazioni sui corsi;
- eventuale accesso degli studenti;
- configurazione delle colonne e delle informazioni delle prove;
- formule, precisione dei calcoli e gestione dei valori mancanti;
- correzione delle risposte multiple e varianti dei compiti;
- comportamento dettagliato della griglia e modalità di salvataggio.

Le funzionalità ancora da discutere non vanno considerate già progettate o implementate.

## Indicazioni per chi contribuisce

Questo file descrive il progetto e le decisioni funzionali concordate. Le istruzioni operative per gli agenti sono in [AGENTS.md](AGENTS.md).
