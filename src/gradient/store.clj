(ns gradient.store
  "Operazioni su corsi, tabelle e righe, sempre limitate ai corsi del proprietario."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [gradient.db :as db])
  (:import (java.text Collator)
           (java.time LocalDate)
           (java.util Locale)
           (java.time.format DateTimeParseException)))

(defn input-error
  ([field message] (input-error field message "INVALID_INPUT"))
  ([field message code]
   (ex-info message {::input-error true :field field :code code})))

(defn input-error? [error]
  (::input-error (ex-data error)))

(defn text
  "Spazi iniziali e finali rimossi; stringa vuota se facoltativo e assente."
  [value field & {:keys [maximum required] :or {maximum 200 required true}}]
  (let [value (str/trim (or value ""))]
    (when (and required (str/blank? value))
      (throw (input-error field "Il campo è obbligatorio.")))
    (when (> (.codePointCount value 0 (count value)) maximum)
      (throw (input-error field (str "Inserire al massimo " maximum " caratteri."))))
    value))

(defn identifier [value field]
  (or (try (parse-uuid (str value)) (catch Exception _ nil))
      (throw (input-error field "Identificatore non valido."))))

(defn date-value
  "Data ISO senza orario, oppure nil."
  [value field]
  (when (some? value)
    (try (str (LocalDate/parse value))
         (catch DateTimeParseException _
           (throw (input-error field "Data non valida."))))))

(defn parse-academic-year [value]
  (let [[_ start end] (re-matches #"([0-9]{4})/([0-9]{2}|[0-9]{4})" (str/trim (or value "")))]
    (when-not start
      (throw (input-error "academicYear" "Usare il formato 2026/27 o 2026/2027.")))
    (let [start (parse-long start)
          next-year (str (inc start))]
      (when (or (< start 1000) (>= start 9999) (not (#{next-year (subs next-year 2)} end)))
        (throw (input-error "academicYear" "L'anno accademico deve comprendere due anni consecutivi.")))
      (str start "/" (inc start)))))

;; ---------------------------------------------------------------------------
;; Rappresentazione esterna

(def course-pattern [:course/id :course/name :course/academic-year :course/degree-program])
(def table-pattern [:table/id {:table/course [:course/id]} :table/name :table/date :table/location])
(def row-pattern [:row/id {:row/table [:table/id]} :row/matricola :row/first-name :row/last-name
                  :row/email :row/dsa :row/dis])

(defn course-data [c]
  {:id (str (:course/id c)) :name (:course/name c) :academic-year (:course/academic-year c)
   :degree-program (:course/degree-program c)})

(defn table-data [t]
  {:id (str (:table/id t)) :course-id (str (-> t :table/course :course/id)) :name (:table/name t)
   :date (:table/date t) :location (:table/location t)})

(defn row-data [r]
  {:id (str (:row/id r)) :table-id (str (-> r :row/table :table/id)) :matricola (:row/matricola r)
   :first-name (:row/first-name r "") :last-name (:row/last-name r "") :email (:row/email r)
   :dsa (boolean (:row/dsa r)) :dis (boolean (:row/dis r))})

;; ---------------------------------------------------------------------------
;; Proprietà

(defn- owner-of [db [kind id]]
  (case kind
    :course (ffirst (d/q '[:find ?u :in $ ?c :where [?e :course/id ?c] [?e :course/owner ?o] [?o :user/id ?u]] db id))
    :table (ffirst (d/q '[:find ?u :in $ ?t :where [?e :table/id ?t] [?e :table/course ?c]
                          [?c :course/owner ?o] [?o :user/id ?u]] db id))))

(defn- require-owned
  "Elemento inesistente e corso altrui sono indistinguibili per il client."
  [{:keys [user-id]} db entity field]
  (when-not (and user-id (= user-id (owner-of db entity)))
    (throw (input-error field "Elemento non trovato." "NOT_FOUND"))))

(defn- transact [{:keys [conn]} tx-data]
  (d/transact conn {:tx-data tx-data}))

;; ---------------------------------------------------------------------------
;; Letture

;; Ordinamento alfabetico come la collation del database: senza distinguere
;; maiuscole e accenti, con l'identificatore come ultimo criterio stabile.
(def ^:private collator
  (doto (Collator/getInstance Locale/ITALIAN) (.setStrength Collator/PRIMARY)))

(defn- not-zero [n] (when-not (zero? n) n))

(defn- by-keys [ks a b]
  (or (some #(not-zero (.compare ^Collator collator (str (% a)) (str (% b))))
            ks)
      0))

(defn courses [{:keys [conn user-id]}]
  (->> (d/q {:query '[:find (pull ?c pattern) :in $ ?u pattern
                      :where [?o :user/id ?u] [?c :course/owner ?o]]
             :args [(d/db conn) user-id course-pattern]})
       (map (comp course-data first))
       (sort (fn [a b] (or (not-zero (compare (:academic-year b) (:academic-year a)))
                           (by-keys [:name :id] a b))))))

(defn tables [{:keys [conn] :as store} course-id]
  (let [course-id (identifier course-id "courseId")
        db (d/db conn)]
    (require-owned store db [:course course-id] "courseId")
    (->> (d/q '[:find (pull ?t pattern) :in $ ?c pattern
                :where [?course :course/id ?c] [?t :table/course ?course]]
              db course-id table-pattern)
         (map (comp table-data first))
         (sort (partial by-keys [:name :id])))))

(defn rows [{:keys [conn] :as store} table-id]
  (let [table-id (identifier table-id "tableId")
        db (d/db conn)]
    (require-owned store db [:table table-id] "tableId")
    (->> (d/q '[:find (pull ?r pattern) :in $ ?t pattern
                :where [?table :table/id ?t] [?r :row/table ?table]]
              db table-id row-pattern)
         (map (comp row-data first))
         (sort (partial by-keys [:last-name :first-name :id])))))

;; ---------------------------------------------------------------------------
;; Scritture

(defn- pull-after [{:keys [db-after]} pattern lookup]
  (d/pull db-after pattern lookup))

(defn create-course [{:keys [user-id] :as store} {:keys [name academic-year degree-program]}]
  (let [id (random-uuid)
        course (cond-> {:course/id id
                        :course/owner [:user/id user-id]
                        :course/name (text name "name")
                        :course/academic-year (parse-academic-year academic-year)}
                 (seq (text degree-program "degreeProgram" :required false))
                 (assoc :course/degree-program (text degree-program "degreeProgram" :required false)))]
    (-> (transact store [course])
        (pull-after course-pattern [:course/id id])
        course-data)))

(defn- table-entity [course-id {:keys [name date location]}]
  (let [location (text location "location" :required false)]
    (cond-> {:table/id (random-uuid)
             :table/course [:course/id course-id]
             :table/name (text name "name")}
      date (assoc :table/date (date-value date "date"))
      (seq location) (assoc :table/location location))))

(defn create-table [{:keys [conn] :as store} {:keys [course-id] :as input}]
  (let [course-id (identifier course-id "courseId")
        table (table-entity course-id input)]
    (require-owned store (d/db conn) [:course course-id] "courseId")
    (-> (transact store [table])
        (pull-after table-pattern [:table/id (:table/id table)])
        table-data)))

(defn- row-entity
  "`table-ref` è un lookup ref oppure il tempid di una tabella creata nella stessa transazione."
  [table-id table-ref {:keys [matricola first-name last-name email dsa dis]}]
  (cond-> {:row/id (random-uuid)
           :row/table table-ref
           :row/first-name (or first-name "")
           :row/last-name (or last-name "")
           :row/dsa (boolean dsa)
           :row/dis (boolean dis)}
    matricola (assoc :row/matricola matricola :row/table+matricola [table-id matricola])
    email (assoc :row/email email)))

(defn add-student [{:keys [conn] :as store} {:keys [table-id matricola first-name last-name]}]
  (let [table-id (identifier table-id "tableId")
        row (row-entity table-id [:table/id table-id] {:matricola (not-empty (text matricola "matricola" :maximum 64 :required false))
                                  :first-name (text first-name "firstName" :required false)
                                  :last-name (text last-name "lastName" :required false)})]
    (require-owned store (d/db conn) [:table table-id] "tableId")
    (try
      (-> (transact store [row])
          (pull-after row-pattern [:row/id (:row/id row)])
          row-data)
      (catch clojure.lang.ExceptionInfo error
        (if (db/unique-conflict? error)
          (throw (input-error "matricola" "Matricola già presente in questa tabella." "DUPLICATE_MATRICOLA"))
          (throw error))))))

(defn import-table
  "Crea la tabella e tutte le righe in un'unica transazione: o tutto o niente."
  [{:keys [conn] :as store} {:keys [course-id] :as input} {:keys [errors rows]}]
  (when (or (seq errors) (empty? rows))
    (throw (input-error "file" "Il CSV contiene errori o non contiene studenti.")))
  (let [course-id (identifier course-id "courseId")
        table (assoc (table-entity course-id input) :db/id "table")]
    (require-owned store (d/db conn) [:course course-id] "courseId")
    (-> (transact store (cons table (map #(row-entity (:table/id table) "table" %) rows)))
        (pull-after table-pattern [:table/id (:table/id table)])
        table-data)))
