# Pacchetti Typst approvati

Pacchetti del registro ufficiale Typst (`@preview`) inclusi nel repository e nell'immagine Docker. Il backend compila i testi usando questa cartella come cache dei pacchetti e non scarica nulla durante l'uso.

Nei testi degli esercizi si possono importare soltanto i pacchetti elencati in `ALLOWED_PACKAGES` in `gradient/typst_render.py`:

- `@preview/cetz:0.5.3`
- `@preview/cetz-plot:0.1.4`

Le altre cartelle sono dipendenze di questi pacchetti (`cetz` 0.5.2 per `cetz-plot`, `oxifmt`). Le licenze sono nei file `LICENSE` di ciascun pacchetto (cetz e cetz-plot: LGPL-3.0-or-later; oxifmt: MIT o Apache-2.0). Le cartelle `gallery` sono state rimosse perché non servono alla compilazione.

Per aggiungere o aggiornare un pacchetto: compilare con il pacchetto Python `typst` un documento che lo importa, indicando questa cartella come `package_cache_path`; rimuovere le gallerie, aggiornare `ALLOWED_PACKAGES` e questo elenco.
