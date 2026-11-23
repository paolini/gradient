(ns gradient.columns-test
  "Colonne, celle e viste personali tramite GraphQL (stessi casi di tests/test_columns.py in main)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [gradient.test-support :refer [gql login! test-system]])
  (:import (java.nio.file Files Path)
           (java.util Base64)))

(def row-fields "id matricola firstName lastName dsa dis cells { columnId display flag gradeBase gradeModifier text }")
(def column-fields "id kind name grade { maximum allowAboveMaximum allowNegative passThreshold modifiers }")
(def view-fields "id name active columnOrder hiddenColumns rowSort { columnId descending }")

(defn data
  "Dati della risposta; la risposta non deve contenere errori GraphQL."
  [response]
  (is (nil? (:errors response)) (pr-str response))
  (:data response))

(defn setup-table [system token]
  (let [course-id (get-in (data (gql system token "mutation { createCourse(input: {name: \"Colonne\", academicYear: \"2026/27\"}) { course { id } } }"))
                          [:createCourse :course :id])
        table-id (get-in (data (gql system token "mutation($id: ID!) { createTable(input: {courseId: $id, name: \"Compitino\"}) { table { id } } }" {:id course-id}))
                         [:createTable :table :id])]
    [course-id table-id]))

(defn columns [system token table-id]
  (:tableColumns (data (gql system token (str "query($id: ID!) { tableColumns(tableId: $id) { " column-fields " } }") {:id table-id}))))

(defn add-column [system token table-id kind column-name & [grade]]
  (:addColumn (data (gql system token (str "mutation($input: AddColumnInput!) { addColumn(input: $input) { column { " column-fields " } errors { field code message } } }")
                         {:input {:tableId table-id :kind kind :name column-name :grade grade}}))))

(defn set-cell [system token row-id column-id value]
  (:setCell (data (gql system token (str "mutation($input: SetCellInput!) { setCell(input: $input) { row { " row-fields " } errors { field code message } } }")
                       {:input {:rowId row-id :columnId column-id :value value}}))))

(defn add-student [system token table-id matricola]
  (get-in (data (gql system token (str "mutation($id: ID!, $m: String) { addStudent(input: {tableId: $id, matricola: $m, lastName: \"Rossi\"}) { row { " row-fields " } } }")
                     {:id table-id :m matricola}))
          [:addStudent :row]))

(defn count-cells [system column-id]
  (count (d/q '[:find ?cell :in $ ?id :where [?c :column/id ?id] [?cell :cell/column ?c]]
              (d/db (:conn system)) (parse-uuid column-id))))

(deftest new-tables-have-builtin-columns
  (let [system (test-system)
        token (login! system)
        [course-id table-id] (setup-table system token)
        content (.encodeToString (Base64/getEncoder) (Files/readAllBytes (Path/of "examples/unipi-iscritti.csv" (make-array String 0))))
        imported (get-in (data (gql system token "mutation($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id } } }"
                                    {:input {:table {:courseId course-id :name "Import"} :file {:contentBase64 content}}}))
                         [:importCsvTable :table :id])]
    (is (= ["MATRICOLA" "LAST_NAME" "FIRST_NAME" "EMAIL" "DSA" "DIS"] (map :kind (columns system token table-id))))
    (is (= 6 (count (columns system token imported))))))

(deftest tables-created-before-columns-receive-builtins
  (let [system (test-system)
        token (login! system)
        [course-id _] (setup-table system token)
        table-id (random-uuid)]
    ;; Una tabella senza colonne, come quelle create prima di questa funzionalità.
    (d/transact (:conn system) {:tx-data [{:table/id table-id :table/course [:course/id (parse-uuid course-id)] :table/name "Vecchia"}]})
    (#'gradient.db/add-missing-builtin-columns (:conn system))
    (#'gradient.db/add-missing-builtin-columns (:conn system))
    (is (= 6 (count (columns system token (str table-id)))))))

(deftest custom-columns-and-cells
  (let [system (test-system)
        token (login! system)
        [_ table-id] (setup-table system token)
        present (:column (add-column system token table-id "FLAG" "Presente"))
        grade (:column (add-column system token table-id "GRADE" "Voto" {:maximum "30" :modifiers ["+" "-" "L"] :passThreshold "18"}))
        exercise (:column (add-column system token table-id "GRADE" "Es1" {:maximum "4"}))
        note (:column (add-column system token table-id "TEXT" "Note"))
        row (add-student system token table-id "001")]
    (is (= {:maximum "30" :allowAboveMaximum false :allowNegative false :passThreshold "18" :modifiers ["+" "-" "L"]}
           (:grade grade)))
    (is (= "DUPLICATE_NAME" (get-in (add-column system token table-id "TEXT" "voto") [:errors 0 :code])))
    (is (= "kind" (get-in (add-column system token table-id "MATRICOLA" "Altra") [:errors 0 :field])))

    (is (= [] (:cells row)))
    (let [result (set-cell system token (:id row) (:id grade) "30 e lode")
          cell (get-in result [:row :cells 0])]
      (is (= ["30L" "30" "L"] [(:display cell) (:gradeBase cell) (:gradeModifier cell)])))
    (is (seq (get-in (set-cell system token (:id row) (:id present) "no") [:row :cells])))
    (is (= [] (:errors (set-cell system token (:id row) (:id note) "  ritirato a metà  "))))
    (is (= [] (:errors (set-cell system token (:id row) (:id exercise) "3,5"))))
    (doseq [value ["4+" "4,5"]]
      (is (= "INVALID_INPUT" (get-in (set-cell system token (:id row) (:id exercise) value) [:errors 0 :code]))))

    (let [rows (:studentRows (data (gql system token (str "query($id: ID!) { studentRows(tableId: $id) { " row-fields " } }") {:id table-id})))
          cells (into {} (map (juxt :columnId identity)) (:cells (first rows)))]
      (is (= [false "no"] ((juxt :flag :display) (cells (:id present)))))
      (is (= "ritirato a metà" (:text (cells (:id note)))))
      (is (= "3,5" (:display (cells (:id exercise))))))

    (testing "svuotare una cella la elimina: il vuoto non è memorizzato"
      (is (= 1 (count-cells system (:id present))))
      (is (= [] (:errors (set-cell system token (:id row) (:id present) ""))))
      (is (= 0 (count-cells system (:id present))))
      (is (= [] (:errors (set-cell system token (:id row) (:id note) "   "))))
      (is (= 0 (count-cells system (:id note)))))

    (testing "cambiare il valore di una cella non lascia valori precedenti"
      ;; L'ordine delle celle nella risposta non è definito: si cerca quella della colonna.
      (let [cell (->> (get-in (set-cell system token (:id row) (:id grade) "7") [:row :cells])
                      (filter #(= (:id grade) (:columnId %)))
                      first)]
        (is (= ["7" ""] [(:display cell) (:gradeModifier cell)]))))))

(deftest builtin-cells-update-row-fields
  (let [system (test-system)
        token (login! system)
        [_ table-id] (setup-table system token)
        by-kind (into {} (map (juxt :kind :id)) (columns system token table-id))
        first-row (add-student system token table-id "001")
        second-row (add-student system token table-id nil)]
    (is (= "DUPLICATE_MATRICOLA" (get-in (set-cell system token (:id second-row) (by-kind "MATRICOLA") "001") [:errors 0 :code])))
    (is (= "002" (get-in (set-cell system token (:id second-row) (by-kind "MATRICOLA") " 002 ") [:row :matricola])))
    (is (nil? (get-in (set-cell system token (:id first-row) (by-kind "MATRICOLA") "") [:row :matricola])))
    (testing "la matricola liberata si può riusare"
      (is (= "001" (get-in (set-cell system token (:id second-row) (by-kind "MATRICOLA") "001") [:row :matricola]))))
    (is (= "Ada" (get-in (set-cell system token (:id first-row) (by-kind "FIRST_NAME") "Ada") [:row :firstName])))
    (is (true? (get-in (set-cell system token (:id first-row) (by-kind "DSA") "sì") [:row :dsa])))
    (is (false? (get-in (set-cell system token (:id first-row) (by-kind "DSA") "") [:row :dsa])))))

(deftest grade-configuration-respects-existing-values
  (let [system (test-system)
        token (login! system)
        [_ table-id] (setup-table system token)
        grade (:column (add-column system token table-id "GRADE" "Orale" {:modifiers ["+"]}))
        row (add-student system token table-id "001")
        update "mutation($input: UpdateColumnInput!) { updateColumn(input: $input) { column { name grade { maximum modifiers } } errors { code } } }"]
    (set-cell system token (:id row) (:id grade) "7+")
    (is (= "INVALID_EXISTING_VALUES"
           (get-in (data (gql system token update {:input {:columnId (:id grade) :grade {:modifiers []}}})) [:updateColumn :errors 0 :code])))
    (is (= "Orale finale"
           (get-in (data (gql system token update {:input {:columnId (:id grade) :name "Orale finale"}})) [:updateColumn :column :name])))
    (testing "una scala compatibile si salva e il massimo si può togliere"
      (is (= {:maximum "10" :modifiers ["+" "-"]}
             (get-in (data (gql system token update {:input {:columnId (:id grade) :grade {:maximum "10" :modifiers ["-" "+"]}}}))
                     [:updateColumn :column :grade])))
      (is (= {:maximum nil :modifiers ["+"]}
             (get-in (data (gql system token update {:input {:columnId (:id grade) :grade {:modifiers ["+"]}}}))
                     [:updateColumn :column :grade]))))))

(deftest delete-column-removes-cells-but-not-builtins
  (let [system (test-system)
        token (login! system)
        [_ table-id] (setup-table system token)
        note (:column (add-column system token table-id "TEXT" "Note"))
        row (add-student system token table-id "001")
        remove-column "mutation($id: ID!) { deleteColumn(columnId: $id) { errors { field } } }"
        builtin (:id (first (columns system token table-id)))]
    (set-cell system token (:id row) (:id note) "x")
    (is (seq (get-in (data (gql system token remove-column {:id builtin})) [:deleteColumn :errors])))
    (is (= [] (get-in (data (gql system token remove-column {:id (:id note)})) [:deleteColumn :errors])))
    (is (= 0 (count-cells system (:id note))))
    (is (= 6 (count (columns system token table-id))))
    (testing "il nome di una colonna eliminata torna disponibile"
      (is (= [] (:errors (add-column system token table-id "TEXT" "Note")))))))

(def views-query (str "query($id: ID!) { tableViews(tableId: $id) { " view-fields " } }"))
(def save-query (str "mutation($input: SaveViewInput!) { saveTableView(input: $input) { view { " view-fields " } errors { field code } } }"))
(def remove-view (str "mutation($id: ID!) { deleteTableView(viewId: $id) { views { " view-fields " } errors { code } } }"))

(defn save-view [system token table-id view-name order & {:keys [view-id hidden sort]}]
  (:saveTableView (data (gql system token save-query
                             {:input {:tableId table-id :viewId view-id :name view-name :columnOrder order
                                      :hiddenColumns (vec hidden) :rowSort (vec sort)}}))))

(defn table-views [system token table-id]
  (:tableViews (data (gql system token views-query {:id table-id}))))

(deftest personal-views
  (let [system (test-system)
        owner (login! system "sub-1")
        other (login! system "sub-2")
        [_ table-id] (setup-table system owner)
        ids (mapv :id (columns system owner table-id))]

    (testing "senza viste salvate si usa una predefinita non memorizzata"
      (let [[default & more] (table-views system owner table-id)]
        (is (nil? more))
        (is (and (nil? (:id default)) (:active default) (= "Predefinita" (:name default))))
        (is (= ids (:columnOrder default)))
        (is (= [] (:hiddenColumns default)))
        (is (= [(ids 1) (ids 2)] (map :columnId (:rowSort default))))))

    (let [first-view (save-view system owner table-id "Predefinita" [(ids 2) (ids 1) (ids 0)]
                                :hidden [(ids 3)] :sort [{:columnId (ids 0) :descending true}])
          _ (is (= [] (:errors first-view)))
          first-view (:view first-view)
          second-view (:view (save-view system owner table-id "Solo nomi" ids :hidden (subvec ids 3)))]
      (is (= (into [(ids 2) (ids 1) (ids 0)] (subvec ids 3)) (:columnOrder first-view)))
      (is (= "DUPLICATE_NAME" (get-in (save-view system owner table-id "solo nomi" ids) [:errors 0 :code])))

      (let [views (into {} (map (juxt :name identity)) (table-views system owner table-id))]
        (is (= #{"Predefinita" "Solo nomi"} (set (keys views))))
        (is (and (:active (views "Solo nomi")) (not (:active (views "Predefinita"))))))

      (testing "si riapre l'ultima vista usata"
        (let [selected (:selectTableView (data (gql system owner (str "mutation($id: ID!) { selectTableView(viewId: $id) { view { " view-fields " } errors { code } } }")
                                                    {:id (:id first-view)})))]
          (is (:active (:view selected)))
          (is (= ["Predefinita"] (map :name (filter :active (table-views system owner table-id)))))))

      (testing "le colonne aggiunte dopo compaiono in fondo a ogni vista; la tabella non cambia"
        (let [note (get-in (add-column system owner table-id "TEXT" "Note") [:column :id])]
          (is (every? #(= note (last (:columnOrder %))) (table-views system owner table-id)))
          (is (= (conj ids note) (mapv :id (columns system owner table-id))))))

      (is (= "NOT_FOUND" (get-in (save-view system owner table-id "Errata" [(str (random-uuid))]) [:errors 0 :code])))

      (let [remaining (get-in (data (gql system owner remove-view {:id (:id first-view)})) [:deleteTableView :views])]
        (is (= ["Solo nomi"] (map :name remaining)))
        (is (:active (first remaining))))
      (is (nil? (get-in (data (gql system owner remove-view {:id (:id second-view)})) [:deleteTableView :views 0 :id]))))

    (testing "le viste sono private: un altro utente non le vede né le modifica"
      (let [kept (:view (save-view system owner table-id "Mia" ids))]
        (is (= "NOT_FOUND" (get-in (gql system other views-query {:id table-id}) [:errors 0 :extensions :code])))
        (is (= "NOT_FOUND" (get-in (save-view system other table-id "Intrusa" []) [:errors 0 :code])))
        (is (= "NOT_FOUND" (get-in (data (gql system other remove-view {:id (:id kept)})) [:deleteTableView :errors 0 :code])))))))

(deftest other-users-cannot-touch-columns-or-cells
  (let [system (test-system)
        owner (login! system "sub-1")
        other (login! system "sub-2")
        [_ table-id] (setup-table system owner)
        note (:column (add-column system owner table-id "TEXT" "Note"))
        row (add-student system owner table-id "001")]
    (is (= "NOT_FOUND" (get-in (set-cell system other (:id row) (:id note) "x") [:errors 0 :code])))
    (is (= "NOT_FOUND" (get-in (gql system other "query($id: ID!) { tableColumns(tableId: $id) { id } }" {:id table-id})
                               [:errors 0 :extensions :code])))
    (is (= "NOT_FOUND" (get-in (data (gql system other "mutation($id: ID!) { deleteColumn(columnId: $id) { errors { code } } }" {:id (:id note)}))
                               [:deleteColumn :errors 0 :code])))
    (is (= 0 (count-cells system (:id note))))
    (is (= 7 (count (columns system owner table-id))))))

(deftest cell-from-another-table-is-rejected
  (let [system (test-system)
        token (login! system)
        [course-id first-table] (setup-table system token)
        second-table (get-in (data (gql system token "mutation($id: ID!) { createTable(input: {courseId: $id, name: \"Orale\"}) { table { id } } }" {:id course-id}))
                             [:createTable :table :id])
        note (:column (add-column system token second-table "TEXT" "Note"))
        row (add-student system token first-table "001")]
    (is (= "NOT_FOUND" (get-in (set-cell system token (:id row) (:id note) "x") [:errors 0 :code])))))

(deftest database-rejects-cells-without-value
  (let [system (test-system)
        token (login! system)
        [_ table-id] (setup-table system token)
        note (:column (add-column system token table-id "TEXT" "Note"))
        row (add-student system token table-id "001")
        insert (fn [values]
                 (try
                   (d/transact (:conn system)
                               {:tx-data [(merge {:cell/row [:row/id (parse-uuid (:id row))]
                                                  :cell/column [:column/id (parse-uuid (:id note))]
                                                  :db/ensure :cell/valid}
                                                 values)]})
                   :accepted
                   (catch clojure.lang.ExceptionInfo e (:db/error (ex-data e)))))]
    (is (= :db.error/entity-pred (insert {})))
    (is (= :db.error/entity-pred (insert {:cell/text "a" :cell/flag true})))
    (is (= :db.error/entity-pred (insert {:cell/text "a" :cell/grade-modifier "+"})))
    (is (= :accepted (insert {:cell/text "a"})))))
