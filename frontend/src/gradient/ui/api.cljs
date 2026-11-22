(ns gradient.ui.api
  "Chiamate GraphQL e operazioni usate dall'interfaccia (verificate dai test sullo schema).")

(def operations
  {:courses "query Courses { courses { id name academicYear degreeProgram } }"
   :tables "query Tables($courseId: ID!) { courseTables(courseId: $courseId) { id courseId name date location } }"
   :rows "query Rows($tableId: ID!) { studentRows(tableId: $tableId) { id tableId matricola firstName lastName email dsa dis } }"
   :create-course "mutation CreateCourse($input: CreateCourseInput!) { createCourse(input: $input) { course { id name academicYear degreeProgram } errors { field code message } } }"
   :create-table "mutation CreateTable($input: CreateTableInput!) { createTable(input: $input) { table { id courseId name date location } errors { field code message } } }"
   :add-student "mutation AddStudent($input: AddStudentInput!) { addStudent(input: $input) { row { id tableId matricola firstName lastName email dsa dis } errors { field code message } } }"
   :preview-csv "mutation PreviewCsv($input: PreviewCsvInput!) { previewCsv(input: $input) { headers mapping { matricola lastName firstName email dsa dis } totalRows rows { matricola firstName lastName email dsa dis } errors { row field message } warnings { row field message } delimiter headerRow } }"
   :import-csv "mutation ImportCsv($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id courseId name date location } importedCount errors { row field message } warnings { row field message } } }"})

(defn- fail [message] (throw (js/Error. message)))

(defn graphql
  "Promessa con i dati della risposta come mappa Clojure con chiavi keyword."
  ([operation] (graphql operation {}))
  ([operation variables]
   (-> (js/fetch "/graphql"
                 #js {:method "POST"
                      :headers #js {"Content-Type" "application/json" "X-Gradient-Request" "1"}
                      :body (js/JSON.stringify (clj->js {:query (operations operation) :variables variables}))})
       (.then identity (fn [_] (fail "Backend non raggiungibile. Verifica che sia avviato.")))
       (.then (fn [response]
                (when (= 401 (.-status response))
                  (js/window.dispatchEvent (js/Event. "gradient-session-expired"))
                  (fail "Sessione scaduta. Accedi nuovamente."))
                (when-not (.-ok response)
                  (fail (str "Il backend ha risposto con errore HTTP " (.-status response) ".")))
                (.json response)))
       (.then (fn [body]
                (let [{:keys [data errors]} (js->clj body :keywordize-keys true)]
                  (when (seq errors) (fail (apply str (interpose " " (map :message errors)))))
                  (when-not data (fail "Risposta del backend non valida."))
                  data))))))

(defn require-success
  "Gli errori di validazione delle mutation diventano un'eccezione con i messaggi."
  [result]
  (when (seq (:errors result))
    (fail (apply str (interpose " " (map :message (:errors result))))))
  result)

(defn message [error fallback]
  (if (instance? js/Error error) (.-message error) fallback))
