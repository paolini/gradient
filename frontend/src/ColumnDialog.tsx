import { useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { ColumnKind, GradeScale, MutationResult, TableColumn } from './api';
import { message } from './common';
import { isBuiltin } from './columns';
import Dialog from './Dialog';

const MODIFIERS: [string, string][] = [['+', '+ (più)'], ['-', '− (meno)'], ['L', 'L (lode)']];

/** Crea una colonna (column assente) oppure ne modifica nome e scala. */
export default function ColumnDialog({ tableId, column, onSaved, onClose }: {
  tableId: string; column?: TableColumn; onSaved: (column: TableColumn) => void; onClose: () => void;
}) {
  const [name, setName] = useState(column?.name ?? '');
  const [kind, setKind] = useState<ColumnKind>(column?.kind ?? 'GRADE');
  const scale: GradeScale = column?.grade ?? { maximum: null, allowAboveMaximum: false, allowNegative: false, passThreshold: null, modifiers: [] };
  const [maximum, setMaximum] = useState(scale.maximum ?? '');
  const [threshold, setThreshold] = useState(scale.passThreshold ?? '');
  const [aboveMaximum, setAboveMaximum] = useState(scale.allowAboveMaximum);
  const [negative, setNegative] = useState(scale.allowNegative);
  const [modifiers, setModifiers] = useState<string[]>(scale.modifiers);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    const grade = kind === 'GRADE' ? {
      maximum: maximum.trim() || null, passThreshold: threshold.trim() || null,
      allowAboveMaximum: aboveMaximum, allowNegative: negative, modifiers,
    } : null;
    try {
      let saved: TableColumn | null;
      if (column) {
        const data = await graphql<{ updateColumn: MutationResult<{ column: TableColumn | null }> }>(operations.updateColumn, {
          input: { columnId: column.id, name, ...(grade ? { grade } : {}) },
        });
        saved = requireSuccess(data.updateColumn).column;
      } else {
        const data = await graphql<{ addColumn: MutationResult<{ column: TableColumn | null }> }>(operations.addColumn, {
          input: { tableId, kind, name, grade },
        });
        saved = requireSuccess(data.addColumn).column;
      }
      if (!saved) throw new Error('Colonna non restituita dal server.');
      onSaved(saved);
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); }
  }

  const toggle = (modifier: string) => setModifiers(current => current.includes(modifier) ? current.filter(item => item !== modifier) : [...current, modifier]);
  return <Dialog title={column ? `Colonna «${column.name}»` : 'Nuova colonna'} onClose={onClose}>
    <form onSubmit={submit}>{error && <p role="alert" className="error">{error}</p>}
      <fieldset disabled={pending}>
        <label>Nome<input value={name} onChange={event => setName(event.target.value)} required maxLength={100} autoFocus placeholder={kind === 'GRADE' ? 'Es1' : 'Presente'} /></label>
        {!column && <label>Tipo<select value={kind} onChange={event => setKind(event.target.value as ColumnKind)}>
          <option value="GRADE">Voto (anche punteggio di un esercizio)</option>
          <option value="FLAG">Flag: sì, no o vuoto (presente, consegna…)</option>
          <option value="TEXT">Testo</option>
        </select></label>}
        {kind === 'GRADE' && (!column || !isBuiltin(column)) && <>
          <div className="field-row">
            <label>Massimo (facoltativo)<input value={maximum} onChange={event => setMaximum(event.target.value)} inputMode="decimal" placeholder="30" /></label>
            <label>Sufficienza (facoltativa)<input value={threshold} onChange={event => setThreshold(event.target.value)} inputMode="decimal" placeholder="18" /></label>
          </div>
          <div className="checks">
            <label className="check"><input type="checkbox" checked={aboveMaximum} onChange={event => setAboveMaximum(event.target.checked)} /> Ammetti valori sopra il massimo</label>
            <label className="check"><input type="checkbox" checked={negative} onChange={event => setNegative(event.target.checked)} /> Ammetti valori negativi</label>
          </div>
          <fieldset className="checks"><legend>Modificatori ammessi</legend>
            {MODIFIERS.map(([value, label]) => <label key={value} className="check"><input type="checkbox" checked={modifiers.includes(value)} onChange={() => toggle(value)} /> {label}</label>)}
          </fieldset>
        </>}
        <div className="dialog-actions"><button type="button" className="secondary" onClick={onClose}>Annulla</button><button>{column ? 'Salva' : 'Aggiungi colonna'}</button></div>
      </fieldset>
    </form>
  </Dialog>;
}
