import { useEffect, useLayoutEffect, useRef, useState } from 'react';
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

function SelectAll({ checked, partial, onChange }: { checked: boolean; partial: boolean; onChange: () => void }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { if (ref.current) ref.current.indeterminate = partial; }, [partial]);
  return <input ref={ref} type="checkbox" checked={checked} onChange={onChange} aria-label="Seleziona tutti gli studenti" />;
}

/**
 * Griglia stile foglio di calcolo: frecce per spostarsi, Invio o digitazione per modificare,
 * Invio/Tab per confermare, Esc per annullare, Canc per svuotare.
 *
 * Selezione delle righe, identificate per id così da resistere ai riordinamenti: casella o
 * Maiusc+Spazio per selezionare una riga, Maiusc+clic o Maiusc+frecce per un intervallo,
 * Ctrl+A per tutte, Esc per deselezionare.
 */
export default function Grid({ columns, rows, header, footer, onCommit, selected, onSelect }: {
  columns: TableColumn[];
  rows: StudentRow[];
  header: (column: TableColumn) => ReactNode;
  footer?: (column: TableColumn) => ReactNode;
  onCommit: (row: StudentRow, column: TableColumn, value: string) => Promise<string | null>;
  selected: ReadonlySet<string>;
  onSelect: (selected: Set<string>) => void;
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
  // Punto di partenza degli intervalli e selezione già presente quando è stato fissato.
  const anchor = useRef<{ rowId: string; base: Set<string> } | null>(null);

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

  function toggleRow(index: number) {
    const row = rows[index];
    if (!row) return;
    const next = new Set(selected);
    if (next.has(row.id)) next.delete(row.id); else next.add(row.id);
    const base = new Set(next);
    base.delete(row.id);
    anchor.current = { rowId: row.id, base };
    onSelect(next);
  }

  /** Seleziona l'intervallo dall'ancora alla riga indicata, conservando la selezione precedente. */
  function selectRange(index: number) {
    let start = anchor.current ? rows.findIndex(row => row.id === anchor.current!.rowId) : -1;
    if (start < 0) {
      start = current.row;
      anchor.current = { rowId: rows[start].id, base: new Set(selected) };
    }
    const next = new Set(anchor.current!.base);
    for (let i = Math.min(start, index); i <= Math.max(start, index); i++) next.add(rows[i].id);
    onSelect(next);
  }

  function selectAll() {
    anchor.current = null;
    onSelect(selected.size === rowCount ? new Set() : new Set(rows.map(row => row.id)));
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
    if (event.shiftKey && event.key === ' ') { event.preventDefault(); toggleRow(current.row); return; }
    if (event.shiftKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      event.preventDefault();
      const target = Math.max(0, Math.min(rowCount - 1, current.row + (event.key === 'ArrowUp' ? -1 : 1)));
      selectRange(target);
      move(target - current.row, 0);
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') { event.preventDefault(); anchor.current = null; onSelect(new Set(rows.map(row => row.id))); return; }
    if (event.key === 'Escape' && selected.size) { event.preventDefault(); anchor.current = null; onSelect(new Set()); return; }
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
      <thead><tr>
        <th scope="col" className="row-head"><SelectAll checked={rowCount > 0 && selected.size === rowCount} partial={selected.size > 0 && selected.size < rowCount} onChange={selectAll} /></th>
        {columns.map(column => <th key={column.id} scope="col" className={`kind-${column.kind.toLowerCase()}`}>{header(column)}</th>)}
      </tr></thead>
      <tbody>
        {rows.map((row, rowIndex) => <tr key={row.id} className={selected.has(row.id) ? 'selected' : undefined}>
          <td className="row-head">
            <label title="Seleziona la riga (Maiusc+clic per un intervallo)">
              <input type="checkbox" tabIndex={-1} checked={selected.has(row.id)} aria-label={`Seleziona ${row.lastName} ${row.firstName}`.trim()}
                onMouseDown={event => event.preventDefault()}
                onClick={event => { if (event.shiftKey) selectRange(rowIndex); else toggleRow(rowIndex); }}
                onChange={() => undefined} />
              <span>{rowIndex + 1}</span>
            </label>
          </td>
          {columns.map((column, columnIndex) => {
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
      {footer && <tfoot><tr className="new-row"><td className="row-head" />{columns.map(column => <td key={column.id}>{footer(column)}</td>)}</tr></tfoot>}
    </table>
  </div>;
}
