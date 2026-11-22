import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { Course, CourseTable, MutationResult, StudentRow } from './api';
import './style.css';
import CsvImport from './CsvImport';
import Dialog from './Dialog';

type Route = { page: 'home' } | { page: 'course'; courseId: string } | { page: 'table'; courseId: string; tableId: string };

function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] === 'corsi' && parts[1] && parts[2] === 'tabelle' && parts[3]) return { page: 'table', courseId: parts[1], tableId: parts[3] };
  if (parts[0] === 'corsi' && parts[1]) return { page: 'course', courseId: parts[1] };
  return { page: 'home' };
}

const courseHref = (courseId: string) => `#/corsi/${encodeURIComponent(courseId)}`;
const tableHref = (courseId: string, tableId: string) => `${courseHref(courseId)}/tabelle/${encodeURIComponent(tableId)}`;

function useRoute() {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const update = () => { setRoute(parseRoute(window.location.hash)); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return route;
}

function useList<T>(query: string, field: string, parent: string | null, revision: number) {
  const key = JSON.stringify([query, parent, revision]);
  const [state, setState] = useState<{ key: string; items: T[]; loading: boolean; error: string }>({ key, items: [], loading: true, error: '' });
  useEffect(() => {
    let active = true;
    setState({ key, items: [], loading: true, error: '' });
    const variables = field === 'courseTables' ? { courseId: parent } : field === 'studentRows' ? { tableId: parent } : {};
    graphql<Record<string, T[]>>(query, variables).then(
      data => { if (active) setState({ key, items: data[field], loading: false, error: '' }); },
      error => { if (active) setState({ key, items: [], loading: false, error: String(error.message) }); },
    );
    return () => { active = false; };
  }, [query, field, parent, key]);
  return state.key === key ? state : { items: [], loading: true, error: '' };
}

function defaultYear() {
  const now = new Date();
  const year = now.getFullYear() - (now.getMonth() < 8 ? 1 : 0);
  return `${year}/${year + 1}`;
}

function formatDate(value: string) {
  const [year, month, day] = value.split('-');
  return `${day}/${month}/${year}`;
}

const courseDetails = (course: Course) => [course.degreeProgram, course.academicYear].filter(Boolean).join(' · ');
const tableDetails = (table: CourseTable) => [table.date && formatDate(table.date), table.location].filter(Boolean).join(' · ');
const message = (failure: unknown, fallback: string) => failure instanceof Error ? failure.message : fallback;

function LoadError({ text, onRetry }: { text: string; onRetry: () => void }) {
  return <div role="alert" className="error">{text} <button type="button" onClick={onRetry}>Riprova</button></div>;
}

function NotFound({ text }: { text: string }) {
  return <div className="empty-state"><p>{text}</p><a href="#/">Torna ai corsi</a></div>;
}

function CourseForm({ onCreated }: { onCreated: (course: Course) => void }) {
  const [name, setName] = useState('');
  const [degreeProgram, setDegreeProgram] = useState('');
  const [year, setYear] = useState(defaultYear);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    try {
      const data = await graphql<{ createCourse: MutationResult<{ course: Course | null }> }>(operations.createCourse, { input: { name, academicYear: year, degreeProgram: degreeProgram || null } });
      const result = requireSuccess(data.createCourse);
      if (!result.course) throw new Error('Corso non restituito dal server.');
      onCreated(result.course);
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); }
  }
  return <form onSubmit={submit}>{error && <p role="alert" className="error">{error}</p>}
    <fieldset disabled={pending}>
      <label>Nome del corso<input value={name} onChange={event => setName(event.target.value)} maxLength={200} required autoFocus placeholder="Analisi 1" /></label>
      <label>Corso di laurea (facoltativo)<input value={degreeProgram} onChange={event => setDegreeProgram(event.target.value)} maxLength={200} placeholder="Matematica" /></label>
      <label>Anno accademico<input value={year} onChange={event => setYear(event.target.value)} required placeholder="2026/2027" /></label>
      <div className="dialog-actions"><button>Crea corso</button></div>
    </fieldset>
  </form>;
}

function TableForm({ courseId, onCreated }: { courseId: string; onCreated: (table: CourseTable) => void }) {
  const [name, setName] = useState('');
  const [date, setDate] = useState('');
  const [location, setLocation] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    try {
      const data = await graphql<{ createTable: MutationResult<{ table: CourseTable | null }> }>(operations.createTable, { input: { courseId, name, date: date || null, location: location || null } });
      const result = requireSuccess(data.createTable);
      if (!result.table) throw new Error('Tabella non restituita dal server.');
      onCreated(result.table);
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); }
  }
  return <form onSubmit={submit}>{error && <p role="alert" className="error">{error}</p>}
    <fieldset disabled={pending}>
      <label>Nome della tabella<input value={name} onChange={event => setName(event.target.value)} required maxLength={200} autoFocus placeholder="Primo compitino" /></label>
      <label>Data (facoltativa)<input type="date" value={date} onChange={event => setDate(event.target.value)} min="1000-01-01" max="9999-12-31" /></label>
      <label>Luogo (facoltativo)<input value={location} onChange={event => setLocation(event.target.value)} maxLength={200} placeholder="Aula A" /></label>
      <div className="dialog-actions"><button>Crea tabella</button></div>
    </fieldset>
  </form>;
}

function HomePage() {
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);
  const courses = useList<Course>(operations.courses, 'courses', null, revision);
  return <>
    <div className="page-heading"><div><h1>I tuoi corsi</h1><p>Ogni corso è una specifica edizione dell'insegnamento.</p></div>
      <button type="button" onClick={() => setCreating(true)}>+ Nuovo corso</button></div>
    {courses.error && <LoadError text={courses.error} onRetry={() => setRevision(value => value + 1)} />}
    {courses.loading ? <p role="status">Caricamento…</p> : !courses.error && !courses.items.length
      ? <div className="empty-state"><p>Non hai ancora creato corsi.</p><button type="button" onClick={() => setCreating(true)}>Crea il primo corso</button></div>
      : <ul className="card-grid">{courses.items.map(course => <li key={course.id}><a className="card" href={courseHref(course.id)}>
          <strong>{course.name}</strong><span>{courseDetails(course)}</span></a></li>)}</ul>}
    {creating && <Dialog title="Nuovo corso" onClose={() => setCreating(false)}>
      <CourseForm onCreated={course => { window.location.hash = courseHref(course.id); }} />
    </Dialog>}
  </>;
}

function CoursePage({ courseId }: { courseId: string }) {
  const [revision, setRevision] = useState(0);
  const [dialog, setDialog] = useState<'' | 'table' | 'csv'>('');
  const [busy, setBusy] = useState(false);
  const courses = useList<Course>(operations.courses, 'courses', null, revision);
  const tables = useList<CourseTable>(operations.tables, 'courseTables', courseId, revision);
  const course = courses.items.find(item => item.id === courseId);
  const loadError = courses.error || tables.error;
  if (loadError) return <><a className="back" href="#/">‹ Corsi</a><LoadError text={loadError} onRetry={() => setRevision(value => value + 1)} /></>;
  if (courses.loading) return <p role="status">Caricamento…</p>;
  if (!course) return <NotFound text="Corso non trovato." />;
  return <>
    <a className="back" href="#/">‹ Corsi</a>
    <div className="page-heading"><div><h1>{course.name}</h1><p>{courseDetails(course)}</p></div>
      <div className="actions"><button type="button" className="secondary" onClick={() => setDialog('csv')}>Importa da CSV</button>
        <button type="button" onClick={() => setDialog('table')}>+ Nuova tabella</button></div></div>
    <section>
      <h2>Tabelle</h2>
      {tables.loading ? <p role="status">Caricamento…</p> : !tables.items.length
        ? <p className="muted-text">Nessuna tabella. Creane una vuota o importa gli iscritti da un CSV.</p>
        : <ul className="link-list">{tables.items.map(table => <li key={table.id}><a href={tableHref(courseId, table.id)}>
            <strong>{table.name}</strong><span>{tableDetails(table)}</span><span aria-hidden="true">›</span></a></li>)}</ul>}
    </section>
    {dialog === 'table' && <Dialog title="Nuova tabella" onClose={() => setDialog('')}>
      <TableForm courseId={courseId} onCreated={table => { window.location.hash = tableHref(courseId, table.id); }} />
    </Dialog>}
    {dialog === 'csv' && <Dialog title="Nuova tabella da CSV" wide onClose={() => { if (!busy) setDialog(''); }}>
      <CsvImport courseId={courseId} disabled={false} onBusy={setBusy} onCreated={table => { window.location.hash = tableHref(courseId, table.id); }} />
    </Dialog>}
  </>;
}

function TablePage({ courseId, tableId }: { courseId: string; tableId: string }) {
  const [revision, setRevision] = useState(0);
  const courses = useList<Course>(operations.courses, 'courses', null, revision);
  const tables = useList<CourseTable>(operations.tables, 'courseTables', courseId, revision);
  const rows = useList<StudentRow>(operations.rows, 'studentRows', tableId, revision);
  const [added, setAdded] = useState<StudentRow[]>([]);
  const [matricola, setMatricola] = useState('');
  const [lastName, setLastName] = useState('');
  const [firstName, setFirstName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const firstInput = useRef<HTMLInputElement>(null);
  const course = courses.items.find(item => item.id === courseId);
  const table = tables.items.find(item => item.id === tableId);

  async function addStudent(event: FormEvent) {
    event.preventDefault();
    if (!matricola.trim() && !lastName.trim() && !firstName.trim()) return;
    setPending(true); setError('');
    try {
      const data = await graphql<{ addStudent: MutationResult<{ row: StudentRow | null }> }>(operations.addStudent, { input: { tableId, matricola: matricola || null, firstName, lastName } });
      const result = requireSuccess(data.addStudent);
      const row = result.row;
      if (row) setAdded(current => [...current, row]);
      setMatricola(''); setLastName(''); setFirstName('');
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); setTimeout(() => firstInput.current?.focus()); }
  }

  const loadError = courses.error || tables.error || rows.error;
  if (loadError && !rows.items.length) return <><a className="back" href={courseHref(courseId)}>‹ Corso</a><LoadError text={loadError} onRetry={() => { setAdded([]); setRevision(value => value + 1); }} /></>;
  if (courses.loading || tables.loading) return <p role="status">Caricamento…</p>;
  if (!course) return <NotFound text="Corso non trovato." />;
  if (!table) return <NotFound text="Tabella non trovata." />;
  const details = tableDetails(table);
  const allRows = rows.loading ? [] : [...rows.items, ...added];
  return <>
    <a className="back" href={courseHref(courseId)}>‹ {course.name} · {course.academicYear}</a>
    <div className="page-heading"><div><h1>{table.name}</h1>{details && <p>{details}</p>}</div>
      <span className="count">{rows.loading ? '' : `${allRows.length} studenti`}</span></div>
    {error && <p role="alert" className="error">{error}</p>}
    <form id="new-student" onSubmit={addStudent} />
    <div className="table-scroll sheet"><table><thead><tr><th>Matricola</th><th>Cognome</th><th>Nome</th><th>Email</th><th>DSA</th><th>DIS</th></tr></thead>
      <tbody>
        {allRows.map(row => <tr key={row.id}><td>{row.matricola ?? <span className="muted">Da inserire</span>}</td><td>{row.lastName}</td><td>{row.firstName}</td><td>{row.email}</td><td>{row.dsa ? 'Sì' : ''}</td><td>{row.dis ? 'Sì' : ''}</td></tr>)}
        {rows.loading && <tr><td colSpan={6} className="empty">Caricamento…</td></tr>}
      </tbody>
      <tfoot><tr className="new-row">
        <td><input ref={firstInput} form="new-student" aria-label="Matricola del nuovo studente" placeholder="+ Matricola" value={matricola} onChange={event => setMatricola(event.target.value)} maxLength={64} autoComplete="off" disabled={pending} /></td>
        <td><input form="new-student" aria-label="Cognome del nuovo studente" placeholder="Cognome" value={lastName} onChange={event => setLastName(event.target.value)} maxLength={200} autoComplete="off" disabled={pending} /></td>
        <td><input form="new-student" aria-label="Nome del nuovo studente" placeholder="Nome" value={firstName} onChange={event => setFirstName(event.target.value)} maxLength={200} autoComplete="off" disabled={pending} /></td>
        <td colSpan={3} className="new-row-hint"><button form="new-student" className="secondary small" disabled={pending}>Aggiungi</button> <span>oppure Invio</span></td>
      </tr></tfoot>
    </table></div>
  </>;
}

export default function App() {
  const route = useRoute();
  return <main className="app">
    {route.page === 'home' && <HomePage />}
    {route.page === 'course' && <CoursePage key={route.courseId} courseId={route.courseId} />}
    {route.page === 'table' && <TablePage key={route.tableId} courseId={route.courseId} tableId={route.tableId} />}
  </main>;
}
