export type Course = { id: string; name: string; academicYear: string; degreeProgram: string | null };
export type CourseTable = { id: string; courseId: string; name: string; date: string | null; location: string | null };
export type Cell = { columnId: string; display: string; flag: boolean | null; gradeBase: string | null; gradeModifier: string; text: string | null };
export type StudentRow = { id: string; tableId: string; matricola: string | null; firstName: string; lastName: string; email: string | null; dsa: boolean; dis: boolean; cells: Cell[] };
export type ColumnKind = 'MATRICOLA' | 'LAST_NAME' | 'FIRST_NAME' | 'EMAIL' | 'DSA' | 'DIS' | 'FLAG' | 'GRADE' | 'TEXT';
export type GradeScale = { maximum: string | null; allowAboveMaximum: boolean; allowNegative: boolean; passThreshold: string | null; modifiers: string[] };
export type TableColumn = { id: string; tableId: string; kind: ColumnKind; name: string; grade: GradeScale | null };
export type RowSort = { columnId: string; descending: boolean };
export type ExerciseVariant = { text: string; options: string[]; correct: number };
export type Exercise = { id: string; title: string; createdAt: string; updatedAt: string; variants: ExerciseVariant[] };
export type ExerciseSummary = { id: string; title: string; updatedAt: string; variantCount: number };
export type TypstIssue = { field: string; message: string; line: number | null; column: number | null };
export type TypstPreview = { pages: string[]; errors: TypstIssue[] };
export type TableView = { id: string | null; name: string; active: boolean; columnOrder: string[]; hiddenColumns: string[]; rowSort: RowSort[] };
export type CsvMapping = { matricola: number | null; lastName: number | null; firstName: number | null; email: number | null; dsa: number | null; dis: number | null };
export type CsvIssue = { row: number | null; field: string; message: string };
export type CsvPreview = { headers: string[]; mapping: CsvMapping; totalRows: number; rows: Omit<StudentRow, 'id' | 'tableId'>[]; errors: CsvIssue[]; warnings: CsvIssue[]; delimiter: string; headerRow: number };
type FieldError = { field: string; code: string; message: string };
export type MutationResult<T> = T & { errors: FieldError[] };

export async function graphql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch('/graphql', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Gradient-Request': '1' },
      body: JSON.stringify({ query, variables }),
    });
  } catch {
    throw new Error('Backend non raggiungibile. Verifica che sia avviato.');
  }
  if (response.status === 401) {
    window.dispatchEvent(new Event('gradient-session-expired'));
    throw new Error('Sessione scaduta. Accedi nuovamente.');
  }
  if (!response.ok) throw new Error(`Il backend ha risposto con errore HTTP ${response.status}.`);
  const body = await response.json();
  if (body.errors?.length) throw new Error(body.errors.map((error: { message: string }) => error.message).join(' '));
  if (!body.data) throw new Error('Risposta del backend non valida.');
  return body.data as T;
}

const ROW = 'id tableId matricola firstName lastName email dsa dis cells { columnId display flag gradeBase gradeModifier text }';
const COLUMN = 'id tableId kind name grade { maximum allowAboveMaximum allowNegative passThreshold modifiers }';
const VIEW = 'id name active columnOrder hiddenColumns rowSort { columnId descending }';
const EXERCISE = 'id title createdAt updatedAt variants { text options correct }';

export const operations = {
  courses: 'query Courses { courses { id name academicYear degreeProgram } }',
  tables: 'query Tables($courseId: ID!) { courseTables(courseId: $courseId) { id courseId name date location } }',
  tableData: `query TableData($tableId: ID!) { tableColumns(tableId: $tableId) { ${COLUMN} } studentRows(tableId: $tableId) { ${ROW} } tableViews(tableId: $tableId) { ${VIEW} } }`,
  createCourse: 'mutation CreateCourse($input: CreateCourseInput!) { createCourse(input: $input) { course { id name academicYear degreeProgram } errors { field code message } } }',
  createTable: 'mutation CreateTable($input: CreateTableInput!) { createTable(input: $input) { table { id courseId name date location } errors { field code message } } }',
  addStudent: `mutation AddStudent($input: AddStudentInput!) { addStudent(input: $input) { row { ${ROW} } errors { field code message } } }`,
  setCell: `mutation SetCell($input: SetCellInput!) { setCell(input: $input) { row { ${ROW} } errors { field code message } } }`,
  addColumn: `mutation AddColumn($input: AddColumnInput!) { addColumn(input: $input) { column { ${COLUMN} } errors { field code message } } }`,
  updateColumn: `mutation UpdateColumn($input: UpdateColumnInput!) { updateColumn(input: $input) { column { ${COLUMN} } errors { field code message } } }`,
  deleteColumn: `mutation DeleteColumn($columnId: ID!) { deleteColumn(columnId: $columnId) { column { id } errors { field code message } } }`,
  saveTableView: `mutation SaveTableView($input: SaveViewInput!) { saveTableView(input: $input) { view { ${VIEW} } errors { field code message } } }`,
  selectTableView: `mutation SelectTableView($viewId: ID!) { selectTableView(viewId: $viewId) { view { ${VIEW} } errors { field code message } } }`,
  deleteTableView: `mutation DeleteTableView($viewId: ID!) { deleteTableView(viewId: $viewId) { views { ${VIEW} } errors { field code message } } }`,
  exercises: 'query Exercises($search: String) { exercises(search: $search) { id title updatedAt variantCount } }',
  exercise: `query Exercise($id: ID!) { exercise(id: $id) { ${EXERCISE} } }`,
  exercisePreview: 'query ExercisePreview($variant: ExerciseVariantInput!) { exercisePreview(variant: $variant) { pages errors { field message line column } } }',
  saveExercise: `mutation SaveExercise($input: SaveExerciseInput!) { saveExercise(input: $input) { exercise { ${EXERCISE} } errors { field code message } } }`,
  deleteExercise: 'mutation DeleteExercise($id: ID!) { deleteExercise(id: $id) { errors { field code message } } }',
  previewCsv: 'mutation PreviewCsv($input: PreviewCsvInput!) { previewCsv(input: $input) { headers mapping { matricola lastName firstName email dsa dis } totalRows rows { matricola firstName lastName email dsa dis } errors { row field message } warnings { row field message } delimiter headerRow } }',
  importCsv: 'mutation ImportCsv($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id courseId name date location } importedCount errors { row field message } warnings { row field message } } }',
};

export function requireSuccess<T>(result: MutationResult<T>): T {
  if (result.errors.length) throw new Error(result.errors.map(error => error.message).join(' '));
  return result;
}
