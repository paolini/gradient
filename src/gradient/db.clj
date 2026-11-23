(ns gradient.db
  "Datomic Local: schema, connessione e classificazione degli errori."
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [gradient.cells :as cells]))

(defn- attr [ident type & {:as options}]
  (merge {:db/ident ident
          :db/valueType (keyword "db.type" (name type))
          :db/cardinality :db.cardinality/one}
         options))

(defn one-cell-value?
  "Predicato di entità delle celle: una cella presente ha esattamente un valore
  e il modificatore accompagna soltanto un voto. La cella vuota non esiste."
  [db eid]
  (let [cell (d/pull db [:cell/flag :cell/grade-base :cell/grade-modifier :cell/text] eid)]
    (and (= 1 (count (keep #(get cell %) [:cell/flag :cell/grade-base :cell/text])))
         (or (nil? (:cell/grade-modifier cell)) (some? (:cell/grade-base cell))))))

;; Lo schema Datomic è additivo: installarlo più volte non ha effetti.
;; Le "migrazioni" consistono nell'aggiungere attributi a questo vettore.
(def schema
  [;; Utenti e sessioni
   (attr :user/id :uuid :db/unique :db.unique/identity)
   (attr :user/provider :string)
   (attr :user/subject :string)
   (attr :user/provider+subject :tuple
         :db/tupleAttrs [:user/provider :user/subject] :db/unique :db.unique/value)
   (attr :user/email :string)
   (attr :user/name :string)
   (attr :user/created-at :instant)
   (attr :user/last-login :instant)
   (attr :session/token-hash :string :db/unique :db.unique/identity)
   (attr :session/user :ref)
   (attr :session/expires-at :instant)
   ;; Corsi, tabelle e righe
   (attr :course/id :uuid :db/unique :db.unique/identity)
   (attr :course/owner :ref)
   (attr :course/name :string)
   (attr :course/academic-year :string)
   (attr :course/degree-program :string)
   (attr :table/id :uuid :db/unique :db.unique/identity)
   (attr :table/course :ref)
   (attr :table/name :string)
   (attr :table/date :string :db/doc "Data senza orario in formato ISO, AAAA-MM-GG.")
   (attr :table/location :string)
   (attr :row/id :uuid :db/unique :db.unique/identity)
   (attr :row/table :ref)
   (attr :row/matricola :string)
   (attr :row/first-name :string)
   (attr :row/last-name :string)
   (attr :row/email :string)
   (attr :row/dsa :boolean)
   (attr :row/dis :boolean)
   ;; Asserita soltanto quando la matricola è presente: più righe senza matricola
   ;; sono ammesse, due righe con la stessa matricola nella stessa tabella no.
   (attr :row/table+matricola :tuple
         :db/tupleTypes [:db.type/uuid :db.type/string] :db/unique :db.unique/value)
   ;; Colonne: definizioni comuni a tutti gli utenti della tabella
   (attr :column/id :uuid :db/unique :db.unique/identity)
   (attr :column/table :ref)
   (attr :column/position :long :db/doc "Ordine predefinito (di creazione); l'ordine mostrato dipende dalla vista.")
   (attr :column/kind :keyword :db/doc "Anagrafica (:matricola, :last-name, ...) oppure :flag, :grade, :text.")
   (attr :column/name :string)
   (attr :column/name-key :string :db/doc "Nome in minuscolo, per l'unicità senza distinguere maiuscole e minuscole.")
   (attr :column/table+name-key :tuple
         :db/tupleAttrs [:column/table :column/name-key] :db/unique :db.unique/value)
   (attr :column/grade-maximum :bigdec)
   (attr :column/grade-allow-above-maximum :boolean)
   (attr :column/grade-allow-negative :boolean)
   (attr :column/grade-pass-threshold :bigdec)
   (attr :column/grade-modifiers :string :db/cardinality :db.cardinality/many)
   ;; Celle: esiste soltanto la cella con un valore
   (attr :cell/row :ref)
   (attr :cell/column :ref)
   (attr :cell/row+column :tuple
         :db/tupleAttrs [:cell/row :cell/column] :db/unique :db.unique/value)
   (attr :cell/flag :boolean)
   (attr :cell/grade-base :bigdec)
   (attr :cell/grade-modifier :string :db/doc "+, - oppure L; assente se il voto non ha modificatore.")
   (attr :cell/text :string)
   {:db/ident :cell/valid
    :db.entity/attrs [:cell/row :cell/column]
    :db.entity/preds ['gradient.db/one-cell-value?]}
   ;; Viste personali
   (attr :view/id :uuid :db/unique :db.unique/identity)
   (attr :view/user :ref)
   (attr :view/table :ref)
   (attr :view/name :string)
   (attr :view/name-key :string)
   (attr :view/user+table+name-key :tuple
         :db/tupleAttrs [:view/user :view/table :view/name-key] :db/unique :db.unique/value)
   ;; Datomic non ha liste ordinate: ordine, colonne nascoste e ordinamento sono vettori EDN.
   (attr :view/column-order :string :db/doc "Vettore EDN degli UUID delle colonne, nell'ordine della vista.")
   (attr :view/hidden-columns :string :db/doc "Vettore EDN degli UUID delle colonne nascoste.")
   (attr :view/row-sort :string :db/doc "Vettore EDN di mappe {:column-id \"uuid\" :descending false}.")
   (attr :view/last-used :instant)])

(defn builtin-columns
  "Le sei colonne anagrafiche di una tabella; `table-ref` è un riferimento o un tempid."
  [table-ref]
  (for [[position kind] (map-indexed vector cells/builtin-kinds)
        :let [column-name (cells/builtin-names kind)]]
    {:column/id (random-uuid)
     :column/table table-ref
     :column/position position
     :column/kind kind
     :column/name column-name
     :column/name-key (cells/name-key column-name)}))

(defn set-attrs-tx
  "Operazioni che portano gli attributi dell'entità `eid` (valori attuali in `current`)
  ai valori di `values`: nil rimuove il valore, un insieme sostituisce un attributo multiplo."
  [eid current values]
  (mapcat (fn [[attribute value]]
            (let [old (get current attribute)]
              (cond
                (set? value) (concat (for [v old :when (not (value v))] [:db/retract eid attribute v])
                                     (for [v value :when (not (contains? (set old) v))] [:db/add eid attribute v]))
                (nil? value) (when (some? old) [[:db/retract eid attribute old]])
                (= value old) nil
                :else [[:db/add eid attribute value]])))
          values))

(defn- add-missing-builtin-columns
  "Le tabelle create prima delle colonne configurabili ricevono le colonne anagrafiche."
  [conn]
  (let [tables (d/q '[:find ?t :where [?t :table/id] (not [_ :column/table ?t])] (d/db conn))]
    (when (seq tables)
      (d/transact conn {:tx-data (mapcat (comp builtin-columns first) tables)}))))

(defn connect
  "Apre il database e installa lo schema. `:storage-dir :mem` lo tiene in memoria (test)."
  [{:keys [storage-dir db-name]}]
  (let [client (d/client {:server-type :datomic-local :storage-dir storage-dir :system "gradient"})]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data schema})
      (add-missing-builtin-columns conn)
      conn)))

(defn anomaly [error]
  (some-> error ex-data :cognitect.anomalies/category))

(defn unique-conflict? [error]
  (= :db.error/unique-conflict (some-> error ex-data :db/error)))

(defn database-error? [error]
  (some? (anomaly error)))

(defn error-details
  "Diagnosi per i log; al client va sempre un messaggio generico."
  [error]
  (case (anomaly error)
    (:cognitect.anomalies/unavailable :cognitect.anomalies/interrupted)
    ["DATABASE_UNREACHABLE" "Database non raggiungibile. Verifica la directory DATOMIC_STORAGE_DIR e i permessi."]
    :cognitect.anomalies/busy
    ["DATABASE_BUSY" "Database occupato. Riprova tra poco."]
    :cognitect.anomalies/forbidden
    ["DATABASE_ACCESS_DENIED" "Accesso alla directory del database negato. Verifica i permessi."]
    :cognitect.anomalies/not-found
    ["DATABASE_NOT_FOUND" "Il database configurato non esiste. Verifica DATOMIC_DB_NAME."]
    ["DATABASE_ERROR" "Si è verificato un errore nel database. Riprova o contatta chi gestisce il servizio."]))

(defn log-error [error]
  (let [[code message] (error-details error)]
    (if (= code "DATABASE_ERROR")
      (log/error error (str message " [" code "]"))
      (log/error (str message " [" code "]")))))
