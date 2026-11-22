import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations } from './api';
import type { CourseTable, CsvIssue, CsvMapping, CsvPreview } from './api';

const fields: [keyof CsvMapping, string][] = [
  ['matricola', 'Matricola'], ['lastName', 'Cognome'], ['firstName', 'Nome'],
  ['email', 'Email'], ['dsa', 'DSA'], ['dis', 'DIS'],
];

function Issues({ items, title }: { items: CsvIssue[]; title: string }) {
  if (!items.length) return null;
  return <div className="csv-issues"><h3>{title} ({items.length})</h3><ul>{items.slice(0, 50).map((item, index) =>
    <li key={index}>{item.row ? `Riga ${item.row}: ` : ''}{item.message}</li>,
  )}</ul>{items.length > 50 && <p>Mostrati i primi 50 messaggi.</p>}</div>;
}

export default function CsvImport({ courseId, disabled, onBusy, onCreated }: {
  courseId: string; disabled: boolean; onBusy: (value: boolean) => void;
  onCreated: (table: CourseTable, count: number) => void;
}) {
  const [content, setContent] = useState('');
  const [filename, setFilename] = useState('');
  const [preview, setPreview] = useState<CsvPreview | null>(null);
  const [error, setError] = useState('');
  const [issues, setIssues] = useState<CsvIssue[]>([]);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState('');
  const [date, setDate] = useState('');
  const [location, setLocation] = useState('');
  const version = useRef(0);

  async function inspect(fileContent: string, mapping?: CsvMapping) {
    const data = await graphql<{ previewCsv: CsvPreview }>(operations.previewCsv, { input: { contentBase64: fileContent, ...(mapping ? { mapping } : {}) } });
    return data.previewCsv;
  }

  async function upload(file: File | undefined) {
    const current = ++version.current;
    setPreview(null); setContent(''); setIssues([]); setError(''); setFilename(file?.name ?? '');
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) { setError('Il CSV deve essere al massimo di 2 MiB.'); return; }
    setBusy(true); onBusy(true);
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      let binary = '';
      for (let start = 0; start < bytes.length; start += 8192) binary += String.fromCharCode(...bytes.subarray(start, start + 8192));
      const encoded = btoa(binary);
      const result = await inspect(encoded);
      if (current === version.current) { setContent(encoded); setPreview(result); setName(file.name.replace(/\.csv$/i, '')); }
    } catch (failure) {
      if (current === version.current) setError(failure instanceof Error ? failure.message : 'Lettura del CSV non riuscita.');
    } finally {
      if (current === version.current) { setBusy(false); onBusy(false); }
    }
  }

  async function remap(field: keyof CsvMapping, index: number | null) {
    if (!preview) return;
    const mapping = { ...preview.mapping, [field]: index };
    setBusy(true); onBusy(true); setError(''); setIssues([]);
    // Invalidate the old preview immediately, so failed validation cannot leave a stale import enabled.
    setPreview({ ...preview, mapping, errors: [{ row: null, field: 'mapping', message: 'Aggiornare l’anteprima prima di importare.' }] });
    try { setPreview(await inspect(content, mapping)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Anteprima non disponibile.'); }
    finally { setBusy(false); onBusy(false); }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!preview || preview.errors.length || !preview.totalRows) return;
    setBusy(true); onBusy(true); setError(''); setIssues([]);
    try {
      const data = await graphql<{ importCsvTable: { table: CourseTable | null; importedCount: number; errors: CsvIssue[] } }>(operations.importCsv, {
        input: { table: { courseId, name, date: date || null, location: location || null }, file: { contentBase64: content, mapping: preview.mapping } },
      });
      const result = data.importCsvTable;
      if (result.errors.length) { setIssues(result.errors); return; }
      if (!result.table) throw new Error('Importazione non completata.');
      setPreview(null); setContent(''); setFilename(''); setName(''); setDate(''); setLocation('');
      onCreated(result.table, result.importedCount);
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Importazione non riuscita.'); }
    finally { setBusy(false); onBusy(false); }
  }

  return <div className="csv-import">
    <p>Carica gli iscritti scaricati dal sito di ateneo, verifica le colonne e crea una nuova tabella nel corso.</p>
    {error && <p role="alert" className="error">{error}</p>}
    <fieldset disabled={!courseId || disabled || busy}>
      <label>File CSV degli iscritti<input key={filename || 'empty'} type="file" accept=".csv,text/csv" onChange={event => { void upload(event.target.files?.[0]); }} /></label>
    </fieldset>
    {busy && <p role="status">{preview ? 'Aggiornamento…' : 'Lettura del CSV…'}</p>}
    {preview && <>
      <p>{filename} · {preview.totalRows} studenti · intestazioni alla riga {preview.headerRow}.</p>
      <p>Vengono importate solo le colonne associate qui sotto. Le altre colonne, le note e i dati introduttivi non vengono salvati. Le righe non vengono filtrate in base a «Cancellazioni».</p>
      <fieldset className="csv-mapping" disabled={busy || disabled}><legend>Associazione delle colonne</legend>
        {fields.map(([field, label]) => <label key={field}>{label}<select value={preview.mapping[field] ?? ''} onChange={event => { void remap(field, event.target.value === '' ? null : Number(event.target.value)); }}>
          <option value="">Non importare</option>
          {preview.headers.map((header, index) => <option key={index} value={index}>{index + 1}. {header || '(senza titolo)'}</option>)}
        </select></label>)}
      </fieldset>
      <p>DSA e DIS possono leggere «Documentazione studente», «Note di sistema» o colonne distinte. Sono riconosciuti vuoto/no, sì/x, 0/1 e le sigle DSA/DIS; altri valori richiedono una correzione o una diversa associazione.</p>
      <Issues items={preview.errors} title="Errori da correggere" />
      <Issues items={preview.warnings} title="Avvisi" />
      <Issues items={issues} title="Importazione non completata" />
      {preview.rows.length > 0 && <div className="table-scroll"><table><caption>Anteprima{preview.totalRows > 100 ? ' dei primi 100 studenti' : ''}</caption><thead><tr><th>Matricola</th><th>Cognome</th><th>Nome</th><th>Email</th><th>DSA</th><th>DIS</th></tr></thead><tbody>
        {preview.rows.map((row, index) => <tr key={index}><td>{row.matricola || '—'}</td><td>{row.lastName}</td><td>{row.firstName}</td><td>{row.email}</td><td>{row.dsa ? 'Sì' : ''}</td><td>{row.dis ? 'Sì' : ''}</td></tr>)}
      </tbody></table></div>}
      <form onSubmit={save}><fieldset disabled={busy || disabled || !!preview.errors.length || !preview.totalRows}><legend>Informazioni della nuova tabella</legend>
        <label>Nome della tabella da importare<input value={name} onChange={event => setName(event.target.value)} required maxLength={200} /></label>
        <label>Data della tabella importata (facoltativa)<input type="date" min="1000-01-01" max="9999-12-31" value={date} onChange={event => setDate(event.target.value)} /></label>
        <label>Luogo della tabella importata (facoltativo)<input value={location} onChange={event => setLocation(event.target.value)} maxLength={200} /></label>
        <button>Crea tabella e importa {preview.totalRows} studenti</button>
      </fieldset></form>
    </>}
  </div>;
}
