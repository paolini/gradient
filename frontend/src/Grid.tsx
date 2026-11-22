import { useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import type { StudentRow, TableColumn } from './api';
import { belowThreshold, editText, findCell } from './columns';

type Position = { row: number; column: number };
type CellState = { status: 'saving' | 'error'; draft: string; message?: string };

const key = (rowId: string, columnId: string) => `${rowId}:${columnId}`;

function display(row: StudentRow, column: TableColumn): ReactNode {
  switch (column.kind) {
    case 'MATRICOLA': return row.matricola ?? <span className="muted">Da inserire</span>;
    case 'LAST_NAME': return row.lastName;
    case 'FIRST_NAME': return row.firstName;
    case 'EMAIL': return row.email;
    case 'DSA': return row.dsa ? 'Sì' : '';
    case 'DIS': return row.dis ? 'Sì' : '';
    default: return findCell(row, column)?.display ?? '';
  }
}

/**
 * Griglia stile foglio di calcolo: frecce per spostarsi, Invio o digitazione per modificare,
 * Invio/Tab per confermare, Esc per annullare, Canc per svuotare.
 */
export default function Grid({ columns, rows, header, footer, onCommit }: {
  columns: TableColumn[];
  rows: StudentRow[];
  header: (column: TableColumn) => ReactNode;
  footer?: (column: TableColumn) => ReactNode;
  onCommit: (row: StudentRow, column: TableColumn, value: string) => Promise<string | null>;
}) {
  const [active, setActive] = useState<Position>({ row: 0, column: 0 });
  const [editing, setEditing] = useState<{ draft: string } | null>(null);
  const [states, setStates] = useState<Record<string, CellState>>({});
  const tableRef = useRef<HTMLTableElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const editingRef = useRef(false);
  // Il focus si sposta subito dopo il rendering, prima del tasto successivo:
  // con un ritardo i caratteri digitati velocemente andrebbero persi.
  const focusPending = useRef(false);

  const rowCount = rows.length;
  const columnCount = columns.length;
  const current = { row: Math.min(active.row, Math.max(rowCount - 1, 0)), column: Math.min(active.column, Math.max(columnCount - 1, 0)) };

  function focusCell(position: Position) {
    const cell = tableRef.current?.querySelector<HTMLElement>(`[data-cell="${position.row}:${position.column}"]`);
    cell?.focus({ preventScroll: false });
  }

  const editorOpen = editing !== null;
  useLayoutEffect(() => {
    if (!editorOpen) return;
    const input = inputRef.current;
    if (!input) return;
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  }, [editorOpen]);

  useLayoutEffect(() => {
    if (!focusPending.current || editorOpen) return;
    focusPending.current = false;
    focusCell(current);
  });

  function move(rowDelta: number, columnDelta: number, focus = true) {
    const next = {
      row: Math.max(0, Math.min(rowCount - 1, current.row + rowDelta)),
      column: Math.max(0, Math.min(columnCount - 1, current.column + columnDelta)),
    };
    focusPending.current = focus;
    setActive(next);
  }

  function startEdit(initial?: string) {
    const row = rows[current.row];
    const column = columns[current.column];
    if (!row || !column) return;
    const state = states[key(row.id, column.id)];
    editingRef.current = true;
    setEditing({ draft: initial ?? (state?.status === 'error' ? state.draft : editText(row, column)) });
  }

  async function commit(position: Position, value: string) {
    const row = rows[position.row];
    const column = columns[position.column];
    if (!row || !column) return;
    const cellKey = key(row.id, column.id);
    const previous = states[cellKey];
    if (value === editText(row, column) && previous?.status !== 'error') return;
    setStates(all => ({ ...all, [cellKey]: { status: 'saving', draft: value } }));
    const error = await onCommit(row, column, value);
    setStates(all => {
      const next = { ...all };
      if (error) next[cellKey] = { status: 'error', draft: value, message: error };
      else delete next[cellKey];
      return next;
    });
  }

  function finishEdit(rowDelta: number, columnDelta: number, focus = true) {
    // Invio e perdita del focus possono arrivare entrambi: si conferma una sola volta.
    if (!editing || !editingRef.current) return;
    editingRef.current = false;
    const position = current;
    const value = editing.draft;
    setEditing(null);
    void commit(position, value);
    if (focus) move(rowDelta, columnDelta);
  }

  function onEditorKey(event: KeyboardEvent<HTMLInputElement>) {
    event.stopPropagation();
    if (event.key === 'Enter') { event.preventDefault(); finishEdit(event.shiftKey ? -1 : 1, 0); }
    else if (event.key === 'Tab') { event.preventDefault(); finishEdit(0, event.shiftKey ? -1 : 1); }
    else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') { event.preventDefault(); finishEdit(event.key === 'ArrowUp' ? -1 : 1, 0); }
    else if (event.key === 'Escape') { event.preventDefault(); editingRef.current = false; focusPending.current = true; setEditing(null); }
  }

  function onGridKey(event: KeyboardEvent<HTMLDivElement>) {
    if (editingRef.current || !rowCount || !columnCount) return;
    if ((event.target as HTMLElement).closest('thead, tfoot')) return;
    const moves: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
    if (moves[event.key]) { event.preventDefault(); move(...moves[event.key]); return; }
    if (event.key === 'Tab') {
      const atEdge = event.shiftKey ? current.column === 0 : current.column === columnCount - 1;
      if (!atEdge) { event.preventDefault(); move(0, event.shiftKey ? -1 : 1); }
      return;
    }
    if (event.key === 'Home') { event.preventDefault(); move(0, -current.column); return; }
    if (event.key === 'End') { event.preventDefault(); move(0, columnCount - 1 - current.column); return; }
    if (event.key === 'Enter' || event.key === 'F2') { event.preventDefault(); startEdit(); return; }
    if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); void commit(current, ''); return; }
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) { event.preventDefault(); startEdit(event.key); }
  }

  return <div className="table-scroll sheet" onKeyDown={onGridKey}>
    <table ref={tableRef} className="grid">
      <thead><tr>{columns.map(column => <th key={column.id} scope="col" className={`kind-${column.kind.toLowerCase()}`}>{header(column)}</th>)}</tr></thead>
      <tbody>
        {rows.map((row, rowIndex) => <tr key={row.id}>{columns.map((column, columnIndex) => {
          const isActive = rowIndex === current.row && columnIndex === current.column;
          const state = states[key(row.id, column.id)];
          const classes = [`kind-${column.kind.toLowerCase()}`];
          if (isActive) classes.push('active');
          if (state) classes.push(state.status);
          if (column.kind === 'GRADE' && belowThreshold(findCell(row, column), column)) classes.push('below');
          return <td key={column.id} data-cell={`${rowIndex}:${columnIndex}`} tabIndex={isActive ? 0 : -1} className={classes.join(' ')}
            title={state?.status === 'error' ? state.message : undefined}
            onClick={() => { if (!isActive) setActive({ row: rowIndex, column: columnIndex }); }}
            onDoubleClick={() => { setActive({ row: rowIndex, column: columnIndex }); startEdit(); }}>
            {isActive && editing
              ? <input ref={inputRef} className="cell-editor" value={editing.draft} aria-label={`${column.name}, ${row.lastName} ${row.firstName}`}
                  onChange={event => setEditing({ draft: event.target.value })} onKeyDown={onEditorKey} onBlur={() => finishEdit(0, 0, false)} />
              : state ? state.draft : display(row, column)}
          </td>;
        })}</tr>)}
      </tbody>
      {footer && <tfoot><tr className="new-row">{columns.map(column => <td key={column.id}>{footer(column)}</td>)}</tr></tfoot>}
    </table>
  </div>;
}
