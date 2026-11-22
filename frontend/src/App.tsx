import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { Course, CourseTable, MutationResult } from './api';
import './style.css';
import CsvImport from './CsvImport';
import Dialog from './Dialog';
import { LoadError, NotFound, courseDetails, courseHref, message, tableDetails, tableHref, useList } from './common';
import TablePage from './TablePage';

type Route = { page: 'home' } | { page: 'course'; courseId: string } | { page: 'table'; courseId: string; tableId: string };

function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] === 'corsi' && parts[1] && parts[2] === 'tabelle' && parts[3]) return { page: 'table', courseId: parts[1], tableId: parts[3] };
  if (parts[0] === 'corsi' && parts[1]) return { page: 'course', courseId: parts[1] };
  return { page: 'home' };
}

function useRoute() {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const update = () => { setRoute(parseRoute(window.location.hash)); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return route;
}

function defaultYear() {
  const now = new Date();
  const year = now.getFullYear() - (now.getMonth() < 8 ? 1 : 0);
  return `${year}/${year + 1}`;
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

export default function App() {
  const route = useRoute();
  return <main className="app">
    {route.page === 'home' && <HomePage />}
    {route.page === 'course' && <CoursePage key={route.courseId} courseId={route.courseId} />}
    {route.page === 'table' && <TablePage key={route.tableId} courseId={route.courseId} tableId={route.tableId} />}
  </main>;
}
