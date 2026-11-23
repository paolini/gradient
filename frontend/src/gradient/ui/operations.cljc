(ns gradient.ui.operations
  "Operazioni GraphQL dell'interfaccia. File .cljc: lo compila shadow-cljs e lo caricano
  anche i test sulla JVM, che ne verificano la validità rispetto a resources/schema.graphql.")

;; Campi condivisi tra più operazioni.
(def row-fields "id tableId matricola firstName lastName email dsa dis cells { columnId display flag gradeBase gradeModifier text }")
(def column-fields "id tableId kind name grade { maximum allowAboveMaximum allowNegative passThreshold modifiers }")
(def view-fields "id name active columnOrder hiddenColumns rowSort { columnId descending }")
(def field-errors "errors { field code message }")

(def operations
  {:courses "query Courses { courses { id name academicYear degreeProgram } }"
   :tables "query Tables($courseId: ID!) { courseTables(courseId: $courseId) { id courseId name date location } }"
   :table-data (str "query TableData($tableId: ID!) { tableColumns(tableId: $tableId) { " column-fields " }"
                    " studentRows(tableId: $tableId) { " row-fields " }"
                    " tableViews(tableId: $tableId) { " view-fields " } }")
   :create-course (str "mutation CreateCourse($input: CreateCourseInput!) { createCourse(input: $input) { course { id name academicYear degreeProgram } " field-errors " } }")
   :create-table (str "mutation CreateTable($input: CreateTableInput!) { createTable(input: $input) { table { id courseId name date location } " field-errors " } }")
   :add-student (str "mutation AddStudent($input: AddStudentInput!) { addStudent(input: $input) { row { " row-fields " } " field-errors " } }")
   :set-cell (str "mutation SetCell($input: SetCellInput!) { setCell(input: $input) { row { " row-fields " } " field-errors " } }")
   :add-column (str "mutation AddColumn($input: AddColumnInput!) { addColumn(input: $input) { column { " column-fields " } " field-errors " } }")
   :update-column (str "mutation UpdateColumn($input: UpdateColumnInput!) { updateColumn(input: $input) { column { " column-fields " } " field-errors " } }")
   :delete-column (str "mutation DeleteColumn($columnId: ID!) { deleteColumn(columnId: $columnId) { column { id } " field-errors " } }")
   :save-view (str "mutation SaveTableView($input: SaveViewInput!) { saveTableView(input: $input) { view { " view-fields " } " field-errors " } }")
   :select-view (str "mutation SelectTableView($viewId: ID!) { selectTableView(viewId: $viewId) { view { " view-fields " } " field-errors " } }")
   :delete-view (str "mutation DeleteTableView($viewId: ID!) { deleteTableView(viewId: $viewId) { views { " view-fields " } " field-errors " } }")
   :preview-csv "mutation PreviewCsv($input: PreviewCsvInput!) { previewCsv(input: $input) { headers mapping { matricola lastName firstName email dsa dis } totalRows rows { matricola firstName lastName email dsa dis } errors { row field message } warnings { row field message } delimiter headerRow } }"
   :import-csv "mutation ImportCsv($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id courseId name date location } importedCount errors { row field message } warnings { row field message } } }"})
