(ns gradient.db
  "Datomic Local: schema, connessione e classificazione degli errori."
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]))

(defn- attr [ident type & {:as options}]
  (merge {:db/ident ident
          :db/valueType (keyword "db.type" (name type))
          :db/cardinality :db.cardinality/one}
         options))

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
         :db/tupleTypes [:db.type/uuid :db.type/string] :db/unique :db.unique/value)])

(defn connect
  "Apre il database e installa lo schema. `:storage-dir :mem` lo tiene in memoria (test)."
  [{:keys [storage-dir db-name]}]
  (let [client (d/client {:server-type :datomic-local :storage-dir storage-dir :system "gradient"})]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data schema})
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
