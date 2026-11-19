export type Course = { id: string; name: string; academicYear: string; degreeProgram: string | null };
export type CourseTable = { id: string; courseId: string; name: string; date: string | null; location: string | null };
export type StudentRow = { id: string; tableId: string; matricola: string | null; firstName: string; lastName: string; email: string | null; dsa: boolean; dis: boolean };
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
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
  } catch {
    throw new Error('Backend non raggiungibile. Verifica che sia avviato.');
  }
  if (!response.ok) throw new Error(`Il backend ha risposto con errore HTTP ${response.status}.`);
  const body = await response.json();
  if (body.errors?.length) throw new Error(body.errors.map((error: { message: string }) => error.message).join(' '));
  if (!body.data) throw new Error('Risposta del backend non valida.');
  return body.data as T;
}

export const operations = {
  courses: 'query Courses { courses { id name academicYear degreeProgram } }',
  tables: 'query Tables($courseId: ID!) { courseTables(courseId: $courseId) { id courseId name date location } }',
  rows: 'query Rows($tableId: ID!) { studentRows(tableId: $tableId) { id tableId matricola firstName lastName email dsa dis } }',
  createCourse: 'mutation CreateCourse($input: CreateCourseInput!) { createCourse(input: $input) { course { id name academicYear degreeProgram } errors { field code message } } }',
  createTable: 'mutation CreateTable($input: CreateTableInput!) { createTable(input: $input) { table { id courseId name date location } errors { field code message } } }',
  addStudent: 'mutation AddStudent($input: AddStudentInput!) { addStudent(input: $input) { row { id tableId matricola firstName lastName email dsa dis } errors { field code message } } }',
  previewCsv: 'mutation PreviewCsv($input: PreviewCsvInput!) { previewCsv(input: $input) { headers mapping { matricola lastName firstName email dsa dis } totalRows rows { matricola firstName lastName email dsa dis } errors { row field message } warnings { row field message } delimiter headerRow } }',
  importCsv: 'mutation ImportCsv($input: ImportCsvInput!) { importCsvTable(input: $input) { table { id courseId name date location } importedCount errors { row field message } warnings { row field message } } }',
};

export function requireSuccess<T>(result: MutationResult<T>): T {
  if (result.errors.length) throw new Error(result.errors.map(error => error.message).join(' '));
  return result;
}
