(ns gradient.graphql
  "API GraphQL: il contratto è `resources/schema.graphql`, qui ci sono soltanto i resolver."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.parser.schema :as parser]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [gradient.csv-import :as csv]
            [gradient.db :as db]
            [gradient.store :as store])
  (:import (java.time LocalDate)))

;; Il client usa camelCase, il codice Clojure kebab-case: si converte al confine.
(defn- convert-keys [f data]
  (walk/postwalk #(if (map? %) (update-keys % (comp keyword f name)) %) data))

(defn- camel [s] (str/replace s #"-(\w)" #(str/upper-case (second %))))
(defn- kebab [s] (str/replace s #"[A-Z]" #(str "-" (str/lower-case %))))
(def ->client (partial convert-keys camel))
(def ->clojure (partial convert-keys kebab))

(defn- database-failure [error message]
  (db/log-error error)
  (resolve/resolve-as nil {:message message :extensions {:code "DATABASE_ERROR"}}))

(defn- unexpected-failure [error]
  ;; Errori non classificati: traceback completo nei log, messaggio generico al client.
  (log/error error "Errore imprevisto in un resolver GraphQL.")
  (resolve/resolve-as nil {:message "Errore interno del server." :extensions {:code "INTERNAL_ERROR"}}))

(defn- query
  "Letture: errori di input e di database diventano errori GraphQL."
  [f]
  (fn [context args _]
    (try
      (->client (f (:store context) (->clojure args)))
      (catch Exception error
        (cond
          (store/input-error? error)
          (let [{:keys [code field]} (ex-data error)]
            (resolve/resolve-as nil {:message (ex-message error) :extensions {:code code :field field}}))
          (db/database-error? error)
          (database-failure error "Database non disponibile o non inizializzato. Verificare la connessione.")
          :else (unexpected-failure error))))))

(defn- mutate
  "Scritture: gli errori di input fanno parte del risultato (`errors`), non della risposta GraphQL."
  [field f]
  (fn [context args _]
    (try
      (->client {field (f (:store context) (:input (->clojure args))) :errors []})
      (catch Exception error
        (cond
          (store/input-error? error)
          (let [{:keys [code] error-field :field} (ex-data error)]
            {:errors [{:field error-field :code code :message (ex-message error)}]})
          (db/database-error? error)
          (database-failure error "Impossibile salvare i dati. Verificare la connessione.")
          :else (unexpected-failure error))))))

(defn- read-csv [{:keys [content-base64 mapping]}]
  (csv/preview content-base64 (when mapping (select-keys mapping csv/fields))))

(defn- preview-csv [_ args _]
  (let [result (read-csv (:input (->clojure args)))]
    (->client (update result :rows #(vec (take 100 %))))))

(def ^:private save-imported-table
  (mutate :table (fn [store {:keys [table preview]}] (store/import-table store table preview))))

(defn- import-csv-table [context args info]
  ;; Il file viene sempre rivalidato: l'anteprima del client non è affidabile.
  (let [{:keys [table file]} (:input (->clojure args))
        result (read-csv file)
        warnings (->client (:warnings result))]
    (if (seq (:errors result))
      {:table nil :importedCount 0 :errors (->client (:errors result)) :warnings warnings}
      (let [saved (save-imported-table context {:input {:table table :preview result}} info)]
        (if (resolve/is-resolver-result? saved)
          saved
          {:table (:table saved)
           :importedCount (if (:table saved) (count (:rows result)) 0)
           :errors (mapv #(-> % (select-keys [:field :message]) (assoc :row nil)) (:errors saved))
           :warnings warnings})))))

(def resolvers
  {:Query/me (fn [context _ _] (->client (select-keys (:user context) [:id :name :email :provider])))
   :Query/courses (query (fn [s _] (store/courses s)))
   :Query/courseTables (query (fn [s {:keys [course-id]}] (store/tables s course-id)))
   :Query/studentRows (query (fn [s {:keys [table-id]}] (store/rows s table-id)))
   :Mutation/previewCsv preview-csv
   :Mutation/importCsvTable import-csv-table
   :Mutation/createCourse (mutate :course store/create-course)
   :Mutation/createTable (mutate :table store/create-table)
   :Mutation/addStudent (mutate :row store/add-student)})

(def date-scalar
  {:parse (fn [value]
            (try (str (LocalDate/parse value))
                 (catch Exception _ (schema/coercion-failure "Data non valida: usare AAAA-MM-GG."))))
   :serialize str})

(defn load-schema []
  (-> (parser/parse-schema (slurp (io/resource "schema.graphql")))
      (util/inject-resolvers resolvers)
      (util/inject-scalar-transformers {:Date date-scalar})
      schema/compile))

(defn execute [compiled {:keys [query variables operationName]} context]
  (lacinia/execute compiled query variables context {:operation-name operationName}))
