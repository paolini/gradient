(ns gradient.columns
  "Colonne delle tabelle e valori delle celle, sempre limitati ai corsi del proprietario."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [gradient.cells :as cells]
            [gradient.db :as db]
            [gradient.store :as store :refer [identifier input-error]]))

(def column-pattern
  [:db/id :column/id {:column/table [:table/id]} :column/position :column/kind :column/name
   :column/grade-maximum :column/grade-allow-above-maximum :column/grade-allow-negative
   :column/grade-pass-threshold :column/grade-modifiers])

(defn builtin? [column]
  (not (cells/custom-kinds (:column/kind column))))

(defn grade-scale
  "Configurazione di una colonna voto, nella forma attesa da `cells/parse-grade`."
  [column]
  {:maximum (:column/grade-maximum column)
   :allow-above-maximum (boolean (:column/grade-allow-above-maximum column))
   :allow-negative (boolean (:column/grade-allow-negative column))
   :pass-threshold (:column/grade-pass-threshold column)
   :modifiers (filterv (set (:column/grade-modifiers column)) cells/modifiers)})

;; Nel contratto GraphQL i tipi sono valori enum (LAST_NAME), nel database keyword (:last-name).
(defn- kind->enum [kind] (keyword (str/upper-case (str/replace (name kind) "-" "_"))))
(defn- enum->kind [value] (keyword (str/replace (str/lower-case (name value)) "_" "-")))

(defn column-data [column]
  {:id (str (:column/id column))
   :table-id (str (-> column :column/table :table/id))
   :kind (kind->enum (:column/kind column))
   :name (:column/name column)
   :grade (when (= :grade (:column/kind column))
            (-> (grade-scale column)
                (update :maximum #(some-> % cells/format-decimal))
                (update :pass-threshold #(some-> % cells/format-decimal))))})

(defn table-columns
  "Colonne della tabella nell'ordine predefinito (di creazione)."
  [db table-id]
  (->> (d/q '[:find (pull ?c pattern) :in $ ?t pattern
              :where [?table :table/id ?t] [?c :column/table ?table]]
            db table-id column-pattern)
       (map first)
       (sort-by (juxt :column/position (comp str :column/id)))))

(defn columns [{:keys [conn] :as store} table-id]
  (let [table-id (identifier table-id "tableId")
        db (d/db conn)]
    (store/require-owned store db [:table table-id] "tableId")
    (map column-data (table-columns db table-id))))

(defn- reporting-field
  "Esegue `f`; un errore di interpretazione del valore diventa un errore di input su `field`."
  [field f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo error
      (throw (if (cells/cell-error? error) (input-error field (ex-message error)) error)))))

(defn- scale-input
  "Valida la scala ricevuta dal client."
  [{:keys [maximum pass-threshold allow-above-maximum allow-negative modifiers]}]
  (let [decimal (fn [value what]
                  (when-not (contains? #{nil ""} value)
                    (reporting-field "grade" #(cells/parse-decimal value what))))
        maximum (decimal maximum "Il massimo")
        threshold (decimal pass-threshold "La sufficienza")]
    (when (and maximum (<= maximum 0))
      (throw (input-error "grade" "Il massimo deve essere maggiore di zero.")))
    (when-not (every? (set cells/modifiers) modifiers)
      (throw (input-error "grade" "Sono ammessi soltanto i modificatori +, - e L.")))
    {:maximum maximum
     :allow-above-maximum (boolean allow-above-maximum)
     :allow-negative (boolean allow-negative)
     :pass-threshold threshold
     :modifiers (filterv (set modifiers) cells/modifiers)}))

(defn- scale-attributes [{:keys [maximum allow-above-maximum allow-negative pass-threshold modifiers]}]
  {:column/grade-maximum maximum
   :column/grade-allow-above-maximum allow-above-maximum
   :column/grade-allow-negative allow-negative
   :column/grade-pass-threshold pass-threshold
   :column/grade-modifiers (set modifiers)})

(defn- duplicate-name []
  (input-error "name" "Esiste già una colonna con questo nome." "DUPLICATE_NAME"))

(defn add-column [{:keys [conn] :as store} {:keys [table-id kind name grade]}]
  (let [table-id (identifier table-id "tableId")
        kind (some-> kind enum->kind)
        _ (when-not (cells/custom-kinds kind)
            (throw (input-error "kind" "Tipo di colonna non valido.")))
        name (store/text name "name" :maximum 100)
        scale (when (= :grade kind) (scale-input grade))
        db (d/db conn)]
    (store/require-owned store db [:table table-id] "tableId")
    (let [last-position (ffirst (d/q '[:find (max ?p) :in $ ?t
                                       :where [?table :table/id ?t] [?c :column/table ?table] [?c :column/position ?p]]
                                     db table-id))
          id (random-uuid)
          column (into {:column/id id
                        :column/table [:table/id table-id]
                        :column/position (inc (or last-position -1))
                        :column/kind kind
                        :column/name name
                        :column/name-key (cells/name-key name)}
                       ;; Nella creazione si omettono i valori assenti e gli insiemi vuoti.
                       (remove (fn [[_ v]] (or (nil? v) (and (set? v) (empty? v)))))
                       (some-> scale scale-attributes))]
      (-> (store/transact-unique store [column] (duplicate-name))
          (store/pull-after column-pattern [:column/id id])
          column-data))))

(defn- grade-cells [db column-eid]
  ;; :db/id nel pull: il risultato di :find è un insieme e celle uguali non devono fondersi.
  (map first (d/q '[:find (pull ?cell [:db/id :cell/grade-base :cell/grade-modifier]) :in $ ?c
                    :where [?cell :cell/column ?c] [?cell :cell/grade-base]]
                  db column-eid)))

(defn update-column [{:keys [conn] :as store} {:keys [column-id name grade]}]
  (let [column-id (identifier column-id "columnId")
        db (d/db conn)
        _ (store/require-owned store db [:column column-id] "columnId")
        column (d/pull db column-pattern [:column/id column-id])
        name (some-> name (store/text "name" :maximum 100))
        scale (when grade
                (when (not= :grade (:column/kind column))
                  (throw (input-error "grade" "La scala si configura soltanto nelle colonne voto.")))
                (let [scale (scale-input grade)
                      invalid (count (filter #(cells/grade-violation (:cell/grade-base %) (:cell/grade-modifier % "") scale)
                                             (grade-cells db (:db/id column))))]
                  (when (pos? invalid)
                    (throw (input-error "grade" (str invalid " voti già inseriti non rispettano la nuova configurazione.")
                                        "INVALID_EXISTING_VALUES")))
                  scale))
        tx (db/set-attrs-tx (:db/id column) column
                            (merge (when name {:column/name name :column/name-key (cells/name-key name)})
                                   (some-> scale scale-attributes)))]
    (-> (store/transact-unique store tx (duplicate-name))
        (store/pull-after column-pattern [:column/id column-id])
        column-data)))

(defn delete-column [{:keys [conn] :as store} {:keys [column-id]}]
  (let [column-id (identifier column-id "columnId")
        db (d/db conn)]
    (store/require-owned store db [:column column-id] "columnId")
    (let [column (d/pull db column-pattern [:column/id column-id])
          cell-ids (d/q '[:find ?cell :in $ ?c :where [?cell :cell/column ?c]] db (:db/id column))]
      (when (builtin? column)
        (throw (input-error "columnId" "Le colonne anagrafiche non possono essere eliminate; è possibile nasconderle.")))
      (store/transact store (cons [:db/retractEntity (:db/id column)]
                                  (for [[cell] cell-ids] [:db/retractEntity cell])))
      (column-data column))))

;; ---------------------------------------------------------------------------
;; Celle

(defn- builtin-tx
  "Le colonne anagrafiche scrivono i campi della riga."
  [{eid :db/id :as row} kind value]
  (db/set-attrs-tx eid row
                   (case kind
                     :matricola (let [matricola (cells/parse-text value 64)]
                                  {:row/matricola matricola
                                   :row/table+matricola (when matricola [(-> row :row/table :table/id) matricola])})
                     :first-name {:row/first-name (or (cells/parse-text value 200) "")}
                     :last-name {:row/last-name (or (cells/parse-text value 200) "")}
                     :email {:row/email (cells/parse-text value 320)}
                     :dsa {:row/dsa (cells/parse-flag value false)}
                     :dis {:row/dis (cells/parse-flag value false)})))

(def ^:private value-attributes [:cell/flag :cell/grade-base :cell/grade-modifier :cell/text])

(defn- cell-values
  "Attributi della cella per il testo digitato, oppure nil per la cella vuota."
  [column value]
  (case (:column/kind column)
    :flag (let [flag (cells/parse-flag value)]
            (when (some? flag) {:cell/flag flag}))
    :grade (when-let [[base modifier] (cells/parse-grade value (grade-scale column))]
             {:cell/grade-base base :cell/grade-modifier (not-empty modifier)})
    :text (when-let [text (cells/parse-text value)]
            {:cell/text text})))

(defn- custom-tx [db row column value]
  (let [values (cell-values column value)
        cell (ffirst (d/q '[:find (pull ?cell pattern) :in $ ?r ?c pattern
                            :where [?cell :cell/row ?r] [?cell :cell/column ?c]]
                          db (:db/id row) (:db/id column) (into [:db/id] value-attributes)))]
    (cond
      ;; La cella vuota non esiste: svuotarla significa eliminarla.
      (nil? values) (if cell [[:db/retractEntity (:db/id cell)]] [])
      cell (conj (vec (db/set-attrs-tx (:db/id cell) cell (merge (zipmap value-attributes (repeat nil)) values)))
                 {:db/id (:db/id cell) :db/ensure :cell/valid})
      :else [(into {:cell/row (:db/id row) :cell/column (:db/id column) :db/ensure :cell/valid}
                   (remove (comp nil? val))
                   values)])))

(defn set-cell
  "Interpreta il testo digitato secondo il tipo della colonna; il testo vuoto svuota la cella."
  [{:keys [conn] :as store} {:keys [row-id column-id value]}]
  (let [row-id (identifier row-id "rowId")
        column-id (identifier column-id "columnId")
        db (d/db conn)]
    (store/require-owned store db [:row row-id] "rowId")
    (store/require-owned store db [:column column-id] "columnId")
    (let [row (d/pull db [:db/id {:row/table [:table/id]} :row/matricola :row/table+matricola
                          :row/first-name :row/last-name :row/email :row/dsa :row/dis]
                      [:row/id row-id])
          column (d/pull db column-pattern [:column/id column-id])]
      (when (not= (-> row :row/table :table/id) (-> column :column/table :table/id))
        (throw (input-error "columnId" "La colonna non appartiene alla tabella della riga." "NOT_FOUND")))
      (let [tx (reporting-field "value" #(if (builtin? column)
                                           (builtin-tx row (:column/kind column) value)
                                           (custom-tx db row column value)))
            db-after (if (seq tx)
                       (:db-after (store/transact-unique store tx (input-error "value" "Matricola già presente in questa tabella."
                                                                               "DUPLICATE_MATRICOLA")))
                       db)]
        (store/row-data (d/pull db-after store/row-pattern [:row/id row-id]))))))
