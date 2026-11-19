# CSV di esempio

`unipi-iscritti.csv` conserva la struttura di un'esportazione UniPi: BOM UTF-8, direttiva `sep=;`, righe introduttive e intestazioni degli iscritti.

Nome, cognome, matricola, email e userid sono stati sostituiti con valori fittizi. Il committente ha controllato il file, autorizzandone l'utilizzo come esempio e nei test. Nel controllo finale anche le note libere degli studenti sono state sostituite con testo fittizio per eliminare eventuali nomi residui. I due flag DSA nelle note di sistema servono a verificare il riconoscimento di quel formato e non identificano persone reali. L'originale scaricato dal sito di ateneo non fa parte del repository.

Il parser importa soltanto le colonne associate nell'anteprima; note, userid e le altre colonne non associate non vengono salvati.
