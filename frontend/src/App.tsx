import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { Course, CourseTable, MutationResult, StudentRow } from './api';
import './style.css';

function useList<T>(query: string, field: string, parent: string | null, revision: number) {
  const key = JSON.stringify([query, parent, revision]);
  const [state, setState] = useState<{ key: string; items: T[]; loading: boolean; error: string }>({ key, items: [], loading: true, error: '' });
  useEffect(() => {
    let active = true;
    if (parent === '') {
      setState({ key, items: [], loading: false, error: '' });
      return;
    }
    setState({ key, items: [], loading: true, error: '' });
    const variables = field === 'courseTables' ? { courseId: parent } : field === 'studentRows' ? { tableId: parent } : {};
    graphql<Record<string, T[]>>(query, variables).then(
      data => { if (active) setState({ key, items: data[field], loading: false, error: '' }); },
      error => { if (active) setState({ key, items: [], loading: false, error: String(error.message) }); },
    );
    return () => { active = false; };
  }, [query, field, parent, key]);
  return state.key === key ? state : { items: [], loading: parent !== '', error: '' };
}

function defaultYear() {
  const now = new Date();
  const year = now.getFullYear() - (now.getMonth() < 8 ? 1 : 0);
  return `${year}/${year + 1}`;
}

export default function App() {
  const [revision, setRevision] = useState(0);
  const [courseId, setCourseId] = useState('');
  const [tableId, setTableId] = useState('');
  const courses = useList<Course>(operations.courses, 'courses', null, revision);
  const tables = useList<CourseTable>(operations.tables, 'courseTables', courseId, revision);
  const rows = useList<StudentRow>(operations.rows, 'studentRows', tableId, revision);
  const [courseName, setCourseName] = useState('');
  const [year, setYear] = useState(defaultYear);
  const [tableName, setTableName] = useState('');
  const [matricola, setMatricola] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (!courseId && courses.items.length) setCourseId(courses.items[0].id);
  }, [courseId, courses.items]);
  useEffect(() => {
    if (!tableId && tables.items.length) setTableId(tables.items[0].id);
  }, [tableId, tables.items]);

  async function save(event: FormEvent, action: () => Promise<void>) {
    event.preventDefault();
    setPending(true);
    setError('');
    setNotice('');
    try {
      await action();
      setRevision(value => value + 1);
      setNotice('Dati salvati.');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Salvataggio non riuscito.');
    } finally {
      setPending(false);
    }
  }

  const selectedCourse = courses.items.find(course => course.id === courseId);
  const selectedTable = tables.items.find(table => table.id === tableId);
  const loadError = courses.error || tables.error || rows.error;

  return <div className="app">
    <header><div><p className="eyebrow">VALUTAZIONI UNIVERSITARIE</p><h1>Gradient</h1></div><span className="badge">Sviluppo locale</span></header>
    <main>
      {(error || loadError) && <div role="alert" className="error">{error || loadError} <button type="button" onClick={() => setRevision(value => value + 1)}>Riprova</button></div>}
      <p role="status" className="notice">{pending ? 'Salvataggio…' : notice}</p>
      <div className="setup">
        <section>
          <h2>Corso</h2><p>Ogni corso rappresenta un anno accademico.</p>
          <label>Corso attivo<select value={courseId} disabled={pending || courses.loading} onChange={event => { setCourseId(event.target.value); setTableId(''); setNotice(''); }}>
            <option value="">{courses.loading ? 'Caricamento…' : 'Seleziona un corso'}</option>
            {courses.items.map(course => <option key={course.id} value={course.id}>{course.name} · {course.academicYear}</option>)}
          </select></label>
          <form onSubmit={event => save(event, async () => {
            const data = await graphql<{ createCourse: MutationResult<{ course: Course | null }> }>(operations.createCourse, { input: { name: courseName, academicYear: year } });
            const result = requireSuccess(data.createCourse);
            if (!result.course) throw new Error('Corso non restituito dal server.');
            setCourseId(result.course.id); setTableId(''); setCourseName('');
          })}>
            <fieldset disabled={pending}><legend>Nuovo corso</legend>
              <label>Nome del corso<input value={courseName} onChange={event => setCourseName(event.target.value)} maxLength={200} required placeholder="Analisi 1" /></label>
              <label>Anno accademico<input value={year} onChange={event => setYear(event.target.value)} required placeholder="2026/2027" /></label>
              <button>Crea corso</button>
            </fieldset>
          </form>
        </section>
        <section>
          <h2>Tabella</h2><p>Una prova o un elenco di studenti del corso.</p>
          <label>Tabella attiva<select value={tableId} disabled={pending || !courseId || tables.loading} onChange={event => { setTableId(event.target.value); setNotice(''); }}>
            <option value="">{tables.loading ? 'Caricamento…' : 'Seleziona una tabella'}</option>
            {tables.items.map(table => <option key={table.id} value={table.id}>{table.name}</option>)}
          </select></label>
          <form onSubmit={event => save(event, async () => {
            const data = await graphql<{ createTable: MutationResult<{ table: CourseTable | null }> }>(operations.createTable, { input: { courseId, name: tableName } });
            const result = requireSuccess(data.createTable);
            if (!result.table) throw new Error('Tabella non restituita dal server.');
            setTableId(result.table.id); setTableName('');
          })}>
            <fieldset disabled={pending || !courseId}><legend>Nuova tabella</legend>
              <label>Nome della tabella<input value={tableName} onChange={event => setTableName(event.target.value)} required maxLength={200} placeholder="Primo compitino" /></label>
              <button>Crea tabella</button>
            </fieldset>
          </form>
        </section>
      </div>
      <section className="students">
        <div className="section-heading"><div><h2>{selectedTable?.name || 'Studenti'}</h2><p>{selectedCourse ? `${selectedCourse.name} · ${selectedCourse.academicYear}` : 'Crea o seleziona un corso e una tabella.'}</p></div><span>{rows.items.length} studenti</span></div>
        <form onSubmit={event => save(event, async () => {
          const data = await graphql<{ addStudent: MutationResult<{ row: StudentRow | null }> }>(operations.addStudent, { input: { tableId, matricola: matricola || null, firstName, lastName } });
          requireSuccess(data.addStudent);
          setMatricola(''); setFirstName(''); setLastName('');
        })}>
          <fieldset className="student-form" disabled={pending || !tableId || rows.loading}><legend>Aggiungi studente</legend>
            <label>Matricola <small>facoltativa</small><input value={matricola} onChange={event => setMatricola(event.target.value)} maxLength={64} autoComplete="off" /></label>
            <label>Cognome<input value={lastName} onChange={event => setLastName(event.target.value)} maxLength={200} autoComplete="off" /></label>
            <label>Nome<input value={firstName} onChange={event => setFirstName(event.target.value)} maxLength={200} autoComplete="off" /></label>
            <button>Aggiungi studente</button>
          </fieldset>
        </form>
        <div className="table-scroll"><table><thead><tr><th>Matricola</th><th>Cognome</th><th>Nome</th></tr></thead><tbody>
          {rows.items.map(row => <tr key={row.id}><td>{row.matricola ?? <span className="muted">Da inserire</span>}</td><td>{row.lastName}</td><td>{row.firstName}</td></tr>)}
          {!rows.items.length && <tr><td colSpan={3} className="empty">{rows.loading ? 'Caricamento…' : rows.error ? 'Impossibile caricare gli studenti.' : tableId ? 'La tabella è vuota. Aggiungi il primo studente.' : 'Seleziona una tabella per visualizzare gli studenti.'}</td></tr>}
        </tbody></table></div>
      </section>
    </main>
  </div>;
}
