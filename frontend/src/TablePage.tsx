import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { Course, CourseTable, MutationResult, RowSort, StudentRow, TableColumn, TableView } from './api';
import ColumnDialog from './ColumnDialog';
import { KIND_LABELS, isBuiltin, sortRows } from './columns';
import { LoadError, NotFound, courseHref, message, tableDetails, useList } from './common';
import Dialog from './Dialog';
import Grid from './Grid';

type TableData = { tableColumns: TableColumn[]; studentRows: StudentRow[]; tableViews: TableView[] };
type ViewSettings = Pick<TableView, 'columnOrder' | 'hiddenColumns' | 'rowSort'>;

function Menu({ label, title, children }: { label: ReactNode; title: string; children: (close: () => void) => ReactNode }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => { if (!ref.current?.contains(event.target as Node)) setOpen(false); };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onPointer); document.removeEventListener('keydown', onKey); };
  }, [open]);
  return <div className="menu" ref={ref}>
    <button type="button" className="menu-button" aria-haspopup="menu" aria-expanded={open} aria-label={title} title={title} onClick={() => setOpen(value => !value)}>{label}</button>
    {open && <div className="menu-list" role="menu">{children(() => setOpen(false))}</div>}
  </div>;
}

function NameDialog({ title, initial, action, onSubmit, onClose }: {
  title: string; initial: string; action: string; onSubmit: (name: string) => Promise<void>; onClose: () => void;
}) {
  const [name, setName] = useState(initial);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    try { await onSubmit(name); onClose(); }
    catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); }
  }
  return <Dialog title={title} onClose={onClose}>
    <form onSubmit={submit}>{error && <p role="alert" className="error">{error}</p>}
      <fieldset disabled={pending}>
        <label>Nome<input value={name} onChange={event => setName(event.target.value)} required maxLength={100} autoFocus /></label>
        <div className="dialog-actions"><button type="button" className="secondary" onClick={onClose}>Annulla</button><button>{action}</button></div>
      </fieldset>
    </form>
  </Dialog>;
}

function ColumnsDialog({ columns, view, onChange, onClose }: {
  columns: TableColumn[]; view: TableView; onChange: (settings: Partial<ViewSettings>) => void; onClose: () => void;
}) {
  const byId = new Map(columns.map(column => [column.id, column]));
  const order = view.columnOrder.filter(id => byId.has(id));
  const swap = (index: number, delta: number) => {
    const next = [...order];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    onChange({ columnOrder: next });
  };
  const toggle = (id: string) => onChange({ hiddenColumns: view.hiddenColumns.includes(id) ? view.hiddenColumns.filter(item => item !== id) : [...view.hiddenColumns, id] });
  return <Dialog title="Colonne della vista" onClose={onClose}>
    <p>Visibilità e ordine valgono solo per la vista «{view.name}».</p>
    <ul className="column-list">{order.map((id, index) => {
      const column = byId.get(id)!;
      return <li key={id}>
        <label className="check"><input type="checkbox" checked={!view.hiddenColumns.includes(id)} onChange={() => toggle(id)} /> {column.name} <small>{KIND_LABELS[column.kind]}</small></label>
        <span className="order-buttons">
          <button type="button" className="icon-button" aria-label={`Sposta ${column.name} su`} disabled={index === 0} onClick={() => swap(index, -1)}>↑</button>
          <button type="button" className="icon-button" aria-label={`Sposta ${column.name} giù`} disabled={index === order.length - 1} onClick={() => swap(index, 1)}>↓</button>
        </span>
      </li>;
    })}</ul>
    <div className="dialog-actions"><button type="button" onClick={onClose}>Fatto</button></div>
  </Dialog>;
}

export default function TablePage({ courseId, tableId }: { courseId: string; tableId: string }) {
  const [revision, setRevision] = useState(0);
  const courses = useList<Course>(operations.courses, 'courses', null, revision);
  const tables = useList<CourseTable>(operations.tables, 'courseTables', courseId, revision);
  const [data, setData] = useState<TableData | null>(null);
  const [loadError, setLoadError] = useState('');
  const [rowOrder, setRowOrder] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [dialog, setDialog] = useState<null | { type: 'add-column' } | { type: 'edit-column'; column: TableColumn } | { type: 'columns' } | { type: 'new-view' } | { type: 'rename-view' }>(null);
  const saveQueue = useRef<Promise<unknown>>(Promise.resolve());
  const viewIdRef = useRef<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  // Selezione temporanea delle righe: non viene salvata e non fa parte della vista.
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    let active = true;
    setData(null); setLoadError(''); setSelected(new Set());
    graphql<TableData>(operations.tableData, { tableId }).then(
      result => { if (!active) return; setData(result); },
      failure => { if (active) setLoadError(message(failure, 'Caricamento non riuscito.')); },
    );
    return () => { active = false; };
  }, [tableId, revision]);

  const view = data?.tableViews.find(item => item.active) ?? data?.tableViews[0];
  viewIdRef.current = view?.id ?? null;
  const sortKey = JSON.stringify(view?.rowSort ?? []);

  // L'ordine delle righe si ricalcola al caricamento e quando cambia l'ordinamento,
  // non a ogni modifica: la riga che si sta compilando non deve spostarsi.
  useEffect(() => {
    if (data && view) setRowOrder(sortRows(data.studentRows, view.rowSort, data.tableColumns));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data === null, tableId, sortKey, view?.id]);

  const visibleColumns = useMemo(() => {
    if (!data || !view) return [];
    const byId = new Map(data.tableColumns.map(column => [column.id, column]));
    return view.columnOrder.filter(id => !view.hiddenColumns.includes(id)).map(id => byId.get(id)).filter(Boolean) as TableColumn[];
  }, [data, view]);
  const orderedRows = useMemo(() => {
    if (!data) return [];
    const byId = new Map(data.studentRows.map(row => [row.id, row]));
    const ordered = rowOrder.map(id => byId.get(id)).filter(Boolean) as StudentRow[];
    const missing = data.studentRows.filter(row => !rowOrder.includes(row.id));
    return [...ordered, ...missing];
  }, [data, rowOrder]);

  function replaceViews(views: TableView[]) {
    setData(current => current && { ...current, tableViews: views });
  }

  function setActiveView(saved: TableView) {
    setData(current => {
      if (!current) return current;
      const others = current.tableViews.filter(item => item.id !== saved.id && item.id !== null).map(item => ({ ...item, active: false }));
      return { ...current, tableViews: [...others, saved].sort((a, b) => a.name.localeCompare(b.name, 'it')) };
    });
  }

  /** Le modifiche alla vista si salvano subito, una richiesta alla volta. */
  function updateView(settings: Partial<ViewSettings>) {
    if (!view) return;
    const next = { ...view, ...settings };
    setData(current => current && { ...current, tableViews: current.tableViews.map(item => item === view ? next : item) });
    saveQueue.current = saveQueue.current.then(async () => {
      try {
        const result = await graphql<{ saveTableView: MutationResult<{ view: TableView | null }> }>(operations.saveTableView, { input: {
          tableId, viewId: viewIdRef.current, name: next.name,
          columnOrder: next.columnOrder, hiddenColumns: next.hiddenColumns, rowSort: next.rowSort,
        } });
        const saved = requireSuccess(result.saveTableView).view;
        if (saved) { viewIdRef.current = saved.id; setActiveView(saved); }
      } catch (failure) { setError(message(failure, 'Impossibile salvare la vista.')); }
    });
  }

  async function createView(name: string) {
    if (!view) return;
    const result = await graphql<{ saveTableView: MutationResult<{ view: TableView | null }> }>(operations.saveTableView, { input: {
      tableId, viewId: null, name, columnOrder: view.columnOrder, hiddenColumns: view.hiddenColumns, rowSort: view.rowSort,
    } });
    const saved = requireSuccess(result.saveTableView).view;
    if (saved) setActiveView(saved);
  }

  async function renameView(name: string) {
    if (!view) return;
    const result = await graphql<{ saveTableView: MutationResult<{ view: TableView | null }> }>(operations.saveTableView, { input: {
      tableId, viewId: view.id, name, columnOrder: view.columnOrder, hiddenColumns: view.hiddenColumns, rowSort: view.rowSort,
    } });
    const saved = requireSuccess(result.saveTableView).view;
    if (saved) {
      setData(current => current && { ...current, tableViews: [...current.tableViews.filter(item => item !== view && item.id !== saved.id).map(item => ({ ...item, active: false })), saved].sort((a, b) => a.name.localeCompare(b.name, 'it')) });
    }
  }

  async function selectView(id: string) {
    setError('');
    try {
      const result = await graphql<{ selectTableView: MutationResult<{ view: TableView | null }> }>(operations.selectTableView, { viewId: id });
      const saved = requireSuccess(result.selectTableView).view;
      if (saved) setActiveView(saved);
    } catch (failure) { setError(message(failure, 'Impossibile aprire la vista.')); }
  }

  async function deleteView() {
    if (!view?.id || !window.confirm(`Eliminare la vista «${view.name}»? I dati della tabella non vengono modificati.`)) return;
    setError('');
    try {
      const result = await graphql<{ deleteTableView: MutationResult<{ views: TableView[] | null }> }>(operations.deleteTableView, { viewId: view.id });
      const views = requireSuccess(result.deleteTableView).views;
      if (views) replaceViews(views);
    } catch (failure) { setError(message(failure, 'Impossibile eliminare la vista.')); }
  }

  function sortBy(column: TableColumn) {
    const first = view?.rowSort[0];
    const descending = first?.columnId === column.id && !first.descending;
    const sort: RowSort[] = [{ columnId: column.id, descending }];
    updateView({ rowSort: sort });
  }

  function moveColumn(column: TableColumn, delta: number) {
    if (!view) return;
    // Lo spostamento avviene tra le colonne visibili, lasciando al loro posto quelle nascoste.
    const visibleIds = visibleColumns.map(item => item.id);
    const index = visibleIds.indexOf(column.id);
    const target = visibleIds[index + delta];
    if (!target) return;
    const order = [...view.columnOrder];
    const from = order.indexOf(column.id);
    const to = order.indexOf(target);
    [order[from], order[to]] = [order[to], order[from]];
    updateView({ columnOrder: order });
  }

  async function deleteColumn(column: TableColumn) {
    if (!window.confirm(`Eliminare la colonna «${column.name}» e tutti i suoi valori? L'operazione non può essere annullata.`)) return;
    setError('');
    try {
      const result = await graphql<{ deleteColumn: MutationResult<{ column: { id: string } | null }> }>(operations.deleteColumn, { columnId: column.id });
      requireSuccess(result.deleteColumn);
      setData(current => current && {
        ...current,
        tableColumns: current.tableColumns.filter(item => item.id !== column.id),
        studentRows: current.studentRows.map(row => ({ ...row, cells: row.cells.filter(cell => cell.columnId !== column.id) })),
        tableViews: current.tableViews.map(item => ({ ...item, columnOrder: item.columnOrder.filter(id => id !== column.id), hiddenColumns: item.hiddenColumns.filter(id => id !== column.id), rowSort: item.rowSort.filter(sort => sort.columnId !== column.id) })),
      });
    } catch (failure) { setError(message(failure, 'Impossibile eliminare la colonna.')); }
  }

  async function deleteSelected() {
    if (!data) return;
    const rows = data.studentRows.filter(row => selected.has(row.id));
    if (!rows.length) return;
    const names = rows.slice(0, 5).map(row => `• ${[row.lastName, row.firstName].filter(Boolean).join(' ') || 'senza nome'}${row.matricola ? ` (${row.matricola})` : ''}`);
    if (rows.length > 5) names.push(`… e altri ${rows.length - 5}`);
    const subject = rows.length === 1 ? 'lo studente selezionato' : `i ${rows.length} studenti selezionati`;
    if (!window.confirm(`Rimuovere da questa tabella ${subject}, con tutti i loro valori?\n\n${names.join('\n')}\n\nLe altre tabelle del corso non vengono modificate. L'operazione non può essere annullata.`)) return;
    setError(''); setDeleting(true);
    try {
      const result = await graphql<{ deleteStudents: MutationResult<{ deletedIds: string[] | null }> }>(operations.deleteStudents, { input: { tableId, rowIds: rows.map(row => row.id) } });
      const removed = new Set(requireSuccess(result.deleteStudents).deletedIds ?? []);
      setData(current => current && { ...current, studentRows: current.studentRows.filter(row => !removed.has(row.id)) });
      setRowOrder(order => order.filter(id => !removed.has(id)));
      setSelected(current => new Set([...current].filter(id => !removed.has(id))));
    } catch (failure) { setError(message(failure, 'Impossibile rimuovere gli studenti.')); }
    finally { setDeleting(false); }
  }

  function columnSaved(column: TableColumn) {
    setData(current => {
      if (!current) return current;
      const exists = current.tableColumns.some(item => item.id === column.id);
      return {
        ...current,
        tableColumns: exists ? current.tableColumns.map(item => item.id === column.id ? column : item) : [...current.tableColumns, column],
        // Le nuove colonne compaiono in fondo a tutte le viste, come sul server.
        tableViews: exists ? current.tableViews : current.tableViews.map(item => ({ ...item, columnOrder: [...item.columnOrder, column.id] })),
      };
    });
    setDialog(null);
  }

  async function commitCell(row: StudentRow, column: TableColumn, value: string): Promise<string | null> {
    try {
      const result = await graphql<{ setCell: MutationResult<{ row: StudentRow | null }> }>(operations.setCell, { input: { rowId: row.id, columnId: column.id, value } });
      const updated = requireSuccess(result.setCell).row;
      if (updated) setData(current => current && { ...current, studentRows: current.studentRows.map(item => item.id === updated.id ? updated : item) });
      return null;
    } catch (failure) { return message(failure, 'Salvataggio non riuscito.'); }
  }

  if (loadError || courses.error || tables.error) return <><a className="back" href={courseHref(courseId)}>‹ Corso</a><LoadError text={loadError || courses.error || tables.error} onRetry={() => setRevision(value => value + 1)} /></>;
  if (courses.loading || tables.loading) return <p role="status">Caricamento…</p>;
  const course = courses.items.find(item => item.id === courseId);
  const table = tables.items.find(item => item.id === tableId);
  if (!course) return <NotFound text="Corso non trovato." />;
  if (!table) return <NotFound text="Tabella non trovata." />;
  const details = tableDetails(table);
  const sort = view?.rowSort[0];

  return <>
    <a className="back" href={courseHref(courseId)}>‹ {course.name} · {course.academicYear}</a>
    <div className="page-heading"><div><h1>{table.name}</h1>{details && <p>{details}</p>}</div>
      <span className="count">{data ? `${data.studentRows.length} studenti` : ''}</span></div>
    {error && <div role="alert" className="error">{error} <button type="button" onClick={() => setError('')}>Chiudi</button></div>}
    {!data || !view ? <p role="status">Caricamento…</p> : <>
      <div className="toolbar">
        <label className="inline">Vista
          <select value={view.id ?? ''} onChange={event => { if (event.target.value) void selectView(event.target.value); }}>
            {data.tableViews.map(item => <option key={item.id ?? 'default'} value={item.id ?? ''}>{item.name}</option>)}
          </select>
        </label>
        <Menu label="Vista ▾" title="Gestisci viste">{close => <>
          <button type="button" role="menuitem" onClick={() => { close(); setDialog({ type: 'new-view' }); }}>Nuova vista (copia di questa)</button>
          <button type="button" role="menuitem" onClick={() => { close(); setDialog({ type: 'rename-view' }); }}>Rinomina vista</button>
          <button type="button" role="menuitem" disabled={!view.id} onClick={() => { close(); void deleteView(); }}>Elimina vista</button>
        </>}</Menu>
        <button type="button" className="secondary" onClick={() => setDialog({ type: 'columns' })}>Colonne{view.hiddenColumns.length ? ` (${view.hiddenColumns.length} nascoste)` : ''}</button>
        <span className="spacer" />
        <button type="button" onClick={() => setDialog({ type: 'add-column' })}>+ Colonna</button>
      </div>
      {selected.size > 0 && <div className="selection-bar" role="region" aria-label="Righe selezionate">
        <strong>{selected.size === 1 ? '1 studente selezionato' : `${selected.size} studenti selezionati`}</strong>
        <button type="button" className="secondary small" onClick={() => setSelected(new Set())}>Deseleziona</button>
        <span className="spacer" />
        <button type="button" className="secondary small danger" disabled={deleting} onClick={() => void deleteSelected()}>{deleting ? 'Rimozione…' : 'Rimuovi dalla tabella…'}</button>
      </div>}
      {visibleColumns.length
        ? <Grid columns={visibleColumns} rows={orderedRows} onCommit={commitCell} selected={selected} onSelect={setSelected}
            header={column => <div className="column-header">
              <button type="button" className="sort-button" onClick={() => sortBy(column)} title="Ordina per questa colonna">
                {column.name}{sort?.columnId === column.id ? (sort.descending ? ' ▼' : ' ▲') : ''}
              </button>
              <Menu label="▾" title={`Opzioni della colonna ${column.name}`}>{close => <>
                <button type="button" role="menuitem" onClick={() => { close(); setDialog({ type: 'edit-column', column }); }}>{column.kind === 'GRADE' ? 'Rinomina e configura…' : 'Rinomina…'}</button>
                <button type="button" role="menuitem" onClick={() => { close(); moveColumn(column, -1); }}>Sposta a sinistra</button>
                <button type="button" role="menuitem" onClick={() => { close(); moveColumn(column, 1); }}>Sposta a destra</button>
                <button type="button" role="menuitem" onClick={() => { close(); updateView({ hiddenColumns: [...view.hiddenColumns, column.id] }); }}>Nascondi</button>
                {!isBuiltin(column) && <button type="button" role="menuitem" className="danger" onClick={() => { close(); void deleteColumn(column); }}>Elimina colonna…</button>}
              </>}</Menu>
            </div>}
            footer={column => <NewStudentField column={column} draft={draft} onChange={setDraft} />} />
        : <div className="empty-state"><p>Tutte le colonne sono nascoste in questa vista.</p><button type="button" onClick={() => setDialog({ type: 'columns' })}>Mostra colonne</button></div>}
      <NewStudent tableId={tableId} draft={draft} onDraft={setDraft} onAdded={row => {
        setData(current => current && { ...current, studentRows: [...current.studentRows, row] });
        setRowOrder(order => [...order, row.id]);
      }} hasNameColumns={visibleColumns.some(column => ['MATRICOLA', 'LAST_NAME', 'FIRST_NAME'].includes(column.kind))} />
      <p className="hint">Frecce per spostarsi · Invio o digitazione per modificare · Invio/Tab per confermare · Esc per annullare · Canc per svuotare · Maiusc+Spazio o casella per selezionare la riga, Maiusc+frecce o Maiusc+clic per un intervallo, Ctrl+A per tutte</p>
    </>}
    {dialog?.type === 'add-column' && <ColumnDialog tableId={tableId} onSaved={columnSaved} onClose={() => setDialog(null)} />}
    {dialog?.type === 'edit-column' && <ColumnDialog tableId={tableId} column={dialog.column} onSaved={columnSaved} onClose={() => setDialog(null)} />}
    {dialog?.type === 'columns' && data && view && <ColumnsDialog columns={data.tableColumns} view={view} onChange={updateView} onClose={() => setDialog(null)} />}
    {dialog?.type === 'new-view' && view && <NameDialog title="Nuova vista" initial={`${view.name} (copia)`} action="Crea vista" onSubmit={createView} onClose={() => setDialog(null)} />}
    {dialog?.type === 'rename-view' && view && <NameDialog title="Rinomina vista" initial={view.name} action="Rinomina" onSubmit={renameView} onClose={() => setDialog(null)} />}
  </>;
}

/* Riga per aggiungere studenti: i campi compaiono sotto le colonne anagrafiche visibili. */

type Draft = { matricola: string; lastName: string; firstName: string };
const EMPTY_DRAFT: Draft = { matricola: '', lastName: '', firstName: '' };
const FIELDS: Partial<Record<TableColumn['kind'], [keyof Draft, string]>> = {
  MATRICOLA: ['matricola', '+ Matricola'], LAST_NAME: ['lastName', 'Cognome'], FIRST_NAME: ['firstName', 'Nome'],
};

function NewStudentField({ column, draft, onChange }: { column: TableColumn; draft: Draft; onChange: (draft: Draft) => void }) {
  const field = FIELDS[column.kind];
  if (!field) return null;
  const [name, placeholder] = field;
  return <input form="new-student" data-new-student={name} aria-label={`${column.name} del nuovo studente`} placeholder={placeholder}
    value={draft[name]} onChange={event => onChange({ ...draft, [name]: event.target.value })} maxLength={name === 'matricola' ? 64 : 200} autoComplete="off" />;
}

function NewStudent({ tableId, draft, onDraft, onAdded, hasNameColumns }: {
  tableId: string; draft: Draft; onDraft: (draft: Draft) => void; onAdded: (row: StudentRow) => void; hasNameColumns: boolean;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!draft.matricola.trim() && !draft.lastName.trim() && !draft.firstName.trim()) return;
    setPending(true); setError('');
    try {
      const result = await graphql<{ addStudent: MutationResult<{ row: StudentRow | null }> }>(operations.addStudent, { input: { tableId, matricola: draft.matricola || null, lastName: draft.lastName, firstName: draft.firstName } });
      const row = requireSuccess(result.addStudent).row;
      if (row) onAdded(row);
      onDraft(EMPTY_DRAFT);
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally {
      setPending(false);
      setTimeout(() => document.querySelector<HTMLInputElement>('[data-new-student]')?.focus());
    }
  }
  return <form id="new-student" className="new-student" onSubmit={submit}>
    {error && <p role="alert" className="error">{error}</p>}
    {hasNameColumns
      ? <button className="secondary small" disabled={pending}>Aggiungi studente</button>
      : <p className="hint">Mostra matricola, cognome o nome per aggiungere studenti.</p>}
  </form>;
}
