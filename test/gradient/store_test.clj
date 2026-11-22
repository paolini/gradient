(ns gradient.store-test
  "Persistenza tramite GraphQL su Datomic in memoria, con sessioni reali."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [datomic.client.api :as d]
            [gradient.main :as main]
            [gradient.test-support :refer [encoded gql login! test-config test-system]])
  (:import (java.nio.file Files Path)
           (java.util Base64)))

(def course-fields "id name academicYear degreeProgram")
(def table-fields "id courseId name date location")
(def row-fields "id tableId matricola firstName lastName email dsa dis")

(defn ok [response]
  (is (nil? (:errors response)) (pr-str response))
  (:data response))

(defn create-course [system token & {:as input}]
  (get-in (gql system token (str "mutation($input: CreateCourseInput!) { createCourse(input: $input) { course { " course-fields " } errors { field code message } } }")
               {:input (merge {:name "Analisi 1" :academicYear "2026/27"} input)})
          [:data :createCourse]))

(defn create-table [system token course-id & {:as input}]
  (get-in (gql system token (str "mutation($input: CreateTableInput!) { createTable(input: $input) { table { " table-fields " } errors { field code message } } }")
               {:input (merge {:courseId course-id :name "Compitino"} input)})
          [:data :createTable]))

(defn add-student [system token table-id & {:as input}]
  (get-in (gql system token (str "mutation($input: AddStudentInput!) { addStudent(input: $input) { row { " row-fields " } errors { field code message } } }")
               {:input (merge {:tableId table-id :firstName "Ada" :lastName "Rossi"} input)})
          [:data :addStudent]))

(defn tables [system token course-id]
  (gql system token (str "query($id: ID!) { courseTables(courseId: $id) { " table-fields " } }") {:id course-id}))

(defn rows [system token table-id]
  (gql system token (str "query($id: ID!) { studentRows(tableId: $id) { " row-fields " } }") {:id table-id}))

(deftest persistence-across-restart
  (let [dir (str (Files/createTempDirectory "gradient-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        config (test-config :storage-dir dir)
        system (main/system config)
        token (login! system)
        course (:course (create-course system token))
        table (:table (create-table system token (:id course)))
        row (:row (add-student system token (:id table) :matricola " 001234 "))]
    (is (= "2026/2027" (:academicYear course)))
    (is (= "001234" (:matricola row)))
    ;; Un nuovo sistema sulla stessa directory rilegge i dati e la sessione.
    (let [system (main/system config)]
      (is (= [course] (:courses (ok (gql system token (str "{ courses { " course-fields " } }"))))))
      (is (= [table] (:courseTables (ok (tables system token (:id course))))))
      (is (= [row] (:studentRows (ok (rows system token (:id table)))))))))

(deftest matricola-uniqueness-missing-values-and-local-names
  (let [system (test-system)
        token (login! system)
        course-id (get-in (create-course system token) [:course :id])
        first-table (get-in (create-table system token course-id) [:table :id])
        second-table (get-in (create-table system token course-id :name "Scritto") [:table :id])]
    (is (empty? (:errors (add-student system token first-table :matricola "00123"))))
    (let [duplicate (add-student system token first-table :matricola "00123" :firstName "Luca")]
      (is (nil? (:row duplicate)))
      (is (= "DUPLICATE_MATRICOLA" (get-in duplicate [:errors 0 :code]))))
    (testing "la stessa matricola in un'altra tabella ha dati anagrafici propri"
      (is (empty? (:errors (add-student system token second-table :matricola "00123" :lastName "Bianchi")))))
    (testing "più righe senza matricola sono ammesse"
      (let [a (:row (add-student system token first-table))
            b (:row (add-student system token first-table :matricola "  "))]
        (is (and (nil? (:matricola a)) (nil? (:matricola b))))
        (is (not= (:id a) (:id b)))))
    (testing "la matricola distingue maiuscole e minuscole, come utf8mb4_bin"
      (is (empty? (:errors (add-student system token first-table :matricola "ab1"))))
      (is (empty? (:errors (add-student system token first-table :matricola "AB1")))))
    (is (= "Rossi" (:lastName (first (filter :matricola (:studentRows (ok (rows system token first-table))))))))))

(deftest missing-parent-and-invalid-input-return-structured-errors
  (let [system (test-system)
        token (login! system)]
    (is (= "NOT_FOUND" (get-in (add-student system token (str (random-uuid))) [:errors 0 :code])))
    (is (= "INVALID_INPUT" (get-in (add-student system token "non-un-uuid") [:errors 0 :code])))
    (are [year] (= "academicYear" (get-in (create-course system token :academicYear year) [:errors 0 :field]))
      "2026" "2026/2028" "2026/28" "0999/1000" "2026-27")
    (is (= "2026/2027" (get-in (create-course system token :academicYear " 2026/2027 ") [:course :academicYear])))
    (testing "le letture con ID non validi diventano errori GraphQL con codice"
      (let [response (tables system token "x")]
        (is (= "INVALID_INPUT" (get-in response [:errors 0 :extensions :code])))))))

(deftest course-and-table-details
  (let [system (test-system)
        token (login! system)]
    (are [details expected]
         (let [course (create-course system token :degreeProgram (:degree details))
               table (create-table system token (get-in course [:course :id])
                                   :date (:date details) :location (:location details))]
           (= expected [(get-in course [:course :degreeProgram])
                        (get-in table [:table :date]) (get-in table [:table :location])]))
      {} [nil nil nil]
      {:degree "  Matematica  " :date "2028-02-29" :location "  Aula A  "} ["Matematica" "2028-02-29" "Aula A"]
      {:degree "  " :location "  "} [nil nil nil])
    (testing "date non valide rifiutate prima del database"
      (are [value] (let [response (gql system token "mutation($input: CreateTableInput!) { createTable(input: $input) { table { id } } }"
                                       {:input {:courseId (str (random-uuid)) :name "Prova" :date value}})]
                     (and (seq (:errors response)) (nil? (:data response))))
        "2026-02-29" "18/11/2026" "2026-11-18T12:00:00" ""))
    (testing "lunghezza dei dettagli"
      (is (= "degreeProgram" (get-in (create-course system token :degreeProgram (apply str (repeat 201 "x"))) [:errors 0 :field])))
      (is (= "location" (get-in (create-table system token (str (random-uuid)) :location (apply str (repeat 201 "x"))) [:errors 0 :field]))))))

(def import-query
  (str "mutation($input: ImportCsvInput!) { importCsvTable(input: $input) { table { " table-fields " } importedCount errors { row field message } warnings { row } } }"))

(deftest import-reviewed-csv-persists-all-students
  (let [system (test-system)
        token (login! system)
        course-id (get-in (create-course system token) [:course :id])
        content (.encodeToString (Base64/getEncoder) (Files/readAllBytes (Path/of "examples/unipi-iscritti.csv" (make-array String 0))))
        result (:importCsvTable (ok (gql system token import-query
                                         {:input {:table {:courseId course-id :name "Orale" :date "2026-05-04"}
                                                  :file {:contentBase64 content}}})))
        imported (:studentRows (ok (rows system token (get-in result [:table :id]))))]
    (is (empty? (:errors result)))
    (is (= 14 (:importedCount result)))
    (is (= "2026-05-04" (get-in result [:table :date])))
    (is (= 14 (count imported) (count (set (map :matricola imported)))))
    (is (every? #(str/ends-with? (:email %) "@example.invalid") imported))
    (is (= 2 (count (filter :dsa imported))))
    (is (not-any? :dis imported))))

(deftest failed-import-rolls-back-table-and-rows
  (let [system (test-system)
        token (login! system)
        course-id (get-in (create-course system token) [:course :id])
        ;; La transazione fallisce dopo la validazione: nessuna tabella deve restare.
        transact d/transact
        response (with-redefs [d/transact (fn [conn arg]
                                            (transact conn (update arg :tx-data concat
                                                                   [{:row/id (random-uuid) :row/dsa "non booleano"}])))]
                   (gql system token import-query
                        {:input {:table {:courseId course-id :name "Compitino"}
                                 :file {:contentBase64 (encoded "Matricola;Nome\n1;Ada\n2;Luca\n")}}}))]
    (is (= "DATABASE_ERROR" (get-in response [:errors 0 :extensions :code])))
    (is (= [] (:courseTables (ok (tables system token course-id)))))))
