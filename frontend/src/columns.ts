import type { Cell, ColumnKind, RowSort, StudentRow, TableColumn } from './api';

export const KIND_LABELS: Record<ColumnKind, string> = {
  MATRICOLA: 'Matricola', LAST_NAME: 'Cognome', FIRST_NAME: 'Nome', EMAIL: 'Email', DSA: 'DSA', DIS: 'DIS',
  FLAG: 'Flag', GRADE: 'Voto', TEXT: 'Testo',
};

export const isBuiltin = (column: TableColumn) => !['FLAG', 'GRADE', 'TEXT'].includes(column.kind);

export function findCell(row: StudentRow, column: TableColumn): Cell | undefined {
  return row.cells.find(cell => cell.columnId === column.id);
}

/** Testo proposto quando si inizia a modificare una cella. */
export function editText(row: StudentRow, column: TableColumn): string {
  switch (column.kind) {
    case 'MATRICOLA': return row.matricola ?? '';
    case 'LAST_NAME': return row.lastName;
    case 'FIRST_NAME': return row.firstName;
    case 'EMAIL': return row.email ?? '';
    case 'DSA': return row.dsa ? 'sì' : '';
    case 'DIS': return row.dis ? 'sì' : '';
    default: return findCell(row, column)?.display ?? '';
  }
}

type SortKey = { missing: true } | { missing: false; text?: string; number?: number; rank?: number };

const MODIFIER_RANK: Record<string, number> = { '-': 0, '': 1, '+': 2, L: 3 };

function sortKey(row: StudentRow, column: TableColumn): SortKey {
  const textKey = (value: string | null) => value && value.trim() ? { missing: false as const, text: value } : { missing: true as const };
  switch (column.kind) {
    case 'MATRICOLA': return textKey(row.matricola);
    case 'LAST_NAME': return textKey(row.lastName);
    case 'FIRST_NAME': return textKey(row.firstName);
    case 'EMAIL': return textKey(row.email);
    case 'DSA': return { missing: false, number: row.dsa ? 1 : 0 };
    case 'DIS': return { missing: false, number: row.dis ? 1 : 0 };
  }
  const cell = findCell(row, column);
  if (!cell) return { missing: true };
  if (column.kind === 'FLAG') return { missing: false, number: cell.flag ? 1 : 0 };
  if (column.kind === 'GRADE') return { missing: false, number: Number(cell.gradeBase), rank: MODIFIER_RANK[cell.gradeModifier] ?? 1 };
  return textKey(cell.text);
}

const collator = new Intl.Collator('it', { numeric: true, sensitivity: 'base' });

function compareKeys(a: SortKey, b: SortKey): number {
  if (a.missing || b.missing) return 0;
  if (a.text !== undefined && b.text !== undefined) return collator.compare(a.text, b.text);
  return (a.number ?? 0) - (b.number ?? 0) || (a.rank ?? 0) - (b.rank ?? 0);
}

/** Ordina le righe secondo la vista; i valori mancanti restano sempre in fondo. */
export function sortRows(rows: StudentRow[], sort: RowSort[], columns: TableColumn[]): string[] {
  const byId = new Map(columns.map(column => [column.id, column]));
  const criteria = sort.map(item => ({ column: byId.get(item.columnId), descending: item.descending })).filter(item => item.column) as { column: TableColumn; descending: boolean }[];
  return [...rows].sort((a, b) => {
    for (const { column, descending } of criteria) {
      const left = sortKey(a, column);
      const right = sortKey(b, column);
      if (left.missing !== right.missing) return left.missing ? 1 : -1;
      const result = compareKeys(left, right);
      if (result) return descending ? -result : result;
    }
    return 0;
  }).map(row => row.id);
}

export function belowThreshold(cell: Cell | undefined, column: TableColumn): boolean {
  const threshold = column.grade?.passThreshold;
  if (!cell || cell.gradeBase === null || !threshold) return false;
  return Number(cell.gradeBase) < Number(threshold.replace(',', '.'));
}
