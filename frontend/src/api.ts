export type Course = { id: string; name: string; academicYear: string };
export type CourseTable = { id: string; courseId: string; name: string };
export type StudentRow = { id: string; tableId: string; matricola: string | null; firstName: string; lastName: string };
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
  courses: 'query Courses { courses { id name academicYear } }',
  tables: 'query Tables($courseId: ID!) { courseTables(courseId: $courseId) { id courseId name } }',
  rows: 'query Rows($tableId: ID!) { studentRows(tableId: $tableId) { id tableId matricola firstName lastName } }',
  createCourse: 'mutation CreateCourse($input: CreateCourseInput!) { createCourse(input: $input) { course { id name academicYear } errors { field code message } } }',
  createTable: 'mutation CreateTable($input: CreateTableInput!) { createTable(input: $input) { table { id courseId name } errors { field code message } } }',
  addStudent: 'mutation AddStudent($input: AddStudentInput!) { addStudent(input: $input) { row { id tableId matricola firstName lastName } errors { field code message } } }',
};

export function requireSuccess<T>(result: MutationResult<T>): T {
  if (result.errors.length) throw new Error(result.errors.map(error => error.message).join(' '));
  return result;
}
