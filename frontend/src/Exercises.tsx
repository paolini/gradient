import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { Exercise, ExerciseSummary, ExerciseVariant, MutationResult, TypstIssue, TypstPreview } from './api';
import { LoadError, NotFound, message } from './common';

const LETTERS = 'ABCDEFGHIJ';
const MAX_OPTIONS = 10;
// Dopo la creazione l'editor viene ricreato con il nuovo indirizzo: la conferma passa da qui.
let justCreated: string | null = null;

export const exerciseHref = (id: string) => `#/esercizi/${encodeURIComponent(id)}`;

function formatDateTime(value: string) {
  // Il server memorizza l'ora UTC senza fuso.
  return new Date(value.endsWith('Z') ? value : `${value}Z`).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' });
}

export function ExercisesPage() {
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<{ items: ExerciseSummary[]; loading: boolean; error: string }>({ items: [], loading: true, error: '' });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim()), 250);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    let active = true;
    setState(current => ({ ...current, loading: true, error: '' }));
    graphql<{ exercises: ExerciseSummary[] }>(operations.exercises, { search: query || null }).then(
      data => { if (active) setState({ items: data.exercises, loading: false, error: '' }); },
      failure => { if (active) setState({ items: [], loading: false, error: message(failure, 'Caricamento non riuscito.') }); },
    );
    return () => { active = false; };
  }, [query, revision]);

  return <>
    <div className="page-heading"><div><h1>Esercizi</h1><p>Archivio personale degli esercizi a risposta multipla, utilizzabile in tutti i tuoi corsi.</p></div>
      <a className="button" href="#/esercizi/nuovo">+ Nuovo esercizio</a></div>
    <label className="search">Cerca<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Titolo, testo o risposte" /></label>
    {state.error && <LoadError text={state.error} onRetry={() => setRevision(value => value + 1)} />}
    {state.loading && !state.items.length ? <p role="status">Caricamento…</p>
      : !state.items.length && !state.error
        ? <div className="empty-state"><p>{query ? 'Nessun esercizio corrisponde alla ricerca.' : 'L\'archivio è vuoto.'}</p>{!query && <a className="button" href="#/esercizi/nuovo">Scrivi il primo esercizio</a>}</div>
        : <section><ul className="link-list">{state.items.map(item => <li key={item.id}><a href={exerciseHref(item.id)}>
            <strong>{item.title}</strong>
            <span>{item.variantCount === 1 ? '1 variante' : `${item.variantCount} varianti`} · {formatDateTime(item.updatedAt)}</span>
            <span aria-hidden="true">›</span></a></li>)}</ul></section>}
  </>;
}

const emptyVariant = (): ExerciseVariant => ({ text: '', options: ['', '', '', ''], correct: 0 });

function fieldLabel(field: string) {
  if (field === 'text') return 'Testo';
  const option = /^options\.(\d+)$/.exec(field);
  return option ? `Risposta ${LETTERS[Number(option[1])]}` : field;
}

/** Anteprima Typst di una variante, aggiornata poco dopo l'ultima modifica. */
function Preview({ variant, onIssues }: { variant: ExerciseVariant; onIssues: (issues: TypstIssue[]) => void }) {
  const [preview, setPreview] = useState<TypstPreview | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState('');
  const sequence = useRef(0);
  const key = JSON.stringify(variant);
  const report = useRef(onIssues);
  report.current = onIssues;

  useEffect(() => {
    if (!variant.text.trim()) { setPreview(null); report.current([]); return; }
    const current = ++sequence.current;
    setPending(true);
    const timer = setTimeout(() => {
      graphql<{ exercisePreview: TypstPreview }>(operations.exercisePreview, { variant }).then(
        data => { if (current === sequence.current) { setPreview(data.exercisePreview); setFailure(''); report.current(data.exercisePreview.errors); } },
        error => { if (current === sequence.current) setFailure(message(error, 'Anteprima non disponibile.')); },
      ).finally(() => { if (current === sequence.current) setPending(false); });
    }, 500);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (!variant.text.trim()) return <div className="preview empty"><p>Scrivi il testo per vedere l'anteprima.</p></div>;
  return <div className={pending ? 'preview pending' : 'preview'} aria-live="polite">
    {failure && <p role="alert" className="error">{failure}</p>}
    {preview?.errors.length ? <ul className="typst-errors">{preview.errors.map((issue, index) =>
      <li key={index}><strong>{fieldLabel(issue.field)}{issue.line ? `, riga ${issue.line}` : ''}:</strong> {issue.message}</li>)}</ul> : null}
    {/* SVG mostrato come immagine: il contenuto non entra nel DOM della pagina. */}
    {preview?.pages.map((page, index) => <img key={index} alt={`Anteprima, pagina ${index + 1}`} src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(page)}`} />)}
  </div>;
}

function VariantEditor({ variant, index, count, errors, onChange, onDuplicate, onRemove }: {
  variant: ExerciseVariant; index: number; count: number; errors: Record<string, string>;
  onChange: (variant: ExerciseVariant) => void; onDuplicate: () => void; onRemove: () => void;
}) {
  const [issues, setIssues] = useState<TypstIssue[]>([]);
  const prefix = `variants.${index}`;
  const invalid = (field: string) => Boolean(errors[`${prefix}.${field}`]) || issues.some(issue => issue.field === field);
  const setOption = (position: number, value: string) => onChange({ ...variant, options: variant.options.map((item, current) => current === position ? value : item) });
  const removeOption = (position: number) => onChange({
    ...variant, options: variant.options.filter((_, current) => current !== position),
    correct: variant.correct === position ? 0 : variant.correct > position ? variant.correct - 1 : variant.correct,
  });
  return <section className="variant">
    <div className="variant-heading"><h2>{count > 1 ? `Variante ${index + 1}` : 'Testo e risposte'}</h2>
      <div className="actions">
        <button type="button" className="secondary small" onClick={onDuplicate}>Duplica variante</button>
        {count > 1 && <button type="button" className="secondary small danger" onClick={onRemove}>Elimina variante</button>}
      </div></div>
    <div className="variant-body">
      <div className="variant-fields">
        <label>Testo<textarea className={invalid('text') ? 'typst invalid' : 'typst'} rows={6} value={variant.text} onChange={event => onChange({ ...variant, text: event.target.value })}
          placeholder="Calcolare $lim_(x -> 0) sin(x) / x$." spellCheck={false} /></label>
        {errors[`${prefix}.text`] && <p className="field-error">{errors[`${prefix}.text`]}</p>}
        <fieldset className="options"><legend>Risposte <small>(seleziona quella corretta)</small></legend>
          {variant.options.map((option, position) => <div key={position} className={position === variant.correct ? 'option correct' : 'option'}>
            <label className="option-letter" title="Risposta corretta">
              <input type="radio" name={`correct-${index}`} checked={position === variant.correct} onChange={() => onChange({ ...variant, correct: position })} />
              {LETTERS[position]}
            </label>
            <textarea className={invalid(`options.${position}`) ? 'typst invalid' : 'typst'} rows={1} value={option} aria-label={`Risposta ${LETTERS[position]}`}
              onChange={event => setOption(position, event.target.value)} spellCheck={false} />
            <button type="button" className="icon-button" aria-label={`Elimina risposta ${LETTERS[position]}`} disabled={variant.options.length <= 2} onClick={() => removeOption(position)}>✕</button>
          </div>)}
          {errors[`${prefix}.options`] && <p className="field-error">{errors[`${prefix}.options`]}</p>}
          {variant.options.map((_, position) => errors[`${prefix}.options.${position}`] && <p key={position} className="field-error">{errors[`${prefix}.options.${position}`]}</p>)}
          {variant.options.length < MAX_OPTIONS && <button type="button" className="secondary small" onClick={() => onChange({ ...variant, options: [...variant.options, ''] })}>+ Risposta</button>}
        </fieldset>
      </div>
      <Preview variant={variant} onIssues={setIssues} />
    </div>
  </section>;
}

export function ExerciseEditor({ exerciseId }: { exerciseId: string | null }) {
  const [loaded, setLoaded] = useState<Exercise | null>(null);
  const [loadError, setLoadError] = useState('');
  const [notFound, setNotFound] = useState(false);
  const [title, setTitle] = useState('');
  const [variants, setVariants] = useState<ExerciseVariant[]>(exerciseId ? [] : [emptyVariant()]);
  const [dirty, setDirty] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState(() => {
    if (exerciseId && justCreated === exerciseId) { justCreated = null; return 'Esercizio creato.'; }
    return '';
  });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!exerciseId) return;
    let active = true;
    setLoadError(''); setNotFound(false);
    graphql<{ exercise: Exercise }>(operations.exercise, { id: exerciseId }).then(
      data => {
        if (!active) return;
        setLoaded(data.exercise); setTitle(data.exercise.title);
        setVariants(data.exercise.variants.map(({ text, options, correct }) => ({ text, options, correct })));
        setDirty(false);
      },
      failure => { if (active) { const text = message(failure, 'Caricamento non riuscito.'); if (/non trovato/i.test(text)) setNotFound(true); else setLoadError(text); } },
    );
    return () => { active = false; };
  }, [exerciseId, revision]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  function change(next: ExerciseVariant[]) { setVariants(next); setDirty(true); setNotice(''); }
  const leave = (event: React.MouseEvent) => { if (dirty && !window.confirm('Ci sono modifiche non salvate. Uscire comunque?')) event.preventDefault(); };

  async function save(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError(''); setErrors({}); setNotice('');
    try {
      const data = await graphql<{ saveExercise: MutationResult<{ exercise: Exercise | null }> }>(operations.saveExercise, { input: { id: exerciseId, title, variants } });
      const result = data.saveExercise;
      if (result.errors.length) {
        setErrors(Object.fromEntries(result.errors.map(item => [item.field, item.message])));
        setError(result.errors.map(item => item.message).join(' '));
        return;
      }
      if (!result.exercise) throw new Error('Esercizio non restituito dal server.');
      setDirty(false);
      setNotice('Esercizio salvato.');
      if (!exerciseId) { justCreated = result.exercise.id; window.location.replace(exerciseHref(result.exercise.id)); }
      else { setLoaded(result.exercise); setTitle(result.exercise.title); }
    } catch (failure) { setError(message(failure, 'Salvataggio non riuscito.')); }
    finally { setPending(false); }
  }

  async function remove() {
    if (!exerciseId || !window.confirm(`Eliminare l'esercizio «${loaded?.title ?? title}»? I compiti già preparati non vengono modificati.`)) return;
    setPending(true); setError('');
    try {
      const data = await graphql<{ deleteExercise: MutationResult<object> }>(operations.deleteExercise, { id: exerciseId });
      requireSuccess(data.deleteExercise);
      setDirty(false);
      window.location.hash = '#/esercizi';
    } catch (failure) { setError(message(failure, 'Eliminazione non riuscita.')); setPending(false); }
  }

  if (notFound) return <NotFound text="Esercizio non trovato." />;
  if (loadError) return <><a className="back" href="#/esercizi">‹ Esercizi</a><LoadError text={loadError} onRetry={() => setRevision(value => value + 1)} /></>;
  if (exerciseId && !loaded) return <p role="status">Caricamento…</p>;

  return <form onSubmit={save} className="exercise-editor">
    <a className="back" href="#/esercizi" onClick={leave}>‹ Esercizi</a>
    <div className="page-heading"><div><h1>{exerciseId ? title || 'Esercizio' : 'Nuovo esercizio'}</h1>
      <p>Testi in <a href="https://typst.app/docs/reference/math/" target="_blank" rel="noreferrer">Typst</a>: formule tra <code>$…$</code>, per esempio <code>$sqrt(2)$</code>, <code>$integral_0^1 f(x) dif x$</code>. Pacchetti disponibili: <code>@preview/cetz:0.5.3</code>, <code>@preview/cetz-plot:0.1.4</code>.</p></div></div>
    <label>Titolo<input value={title} onChange={event => { setTitle(event.target.value); setDirty(true); setNotice(''); }} required maxLength={200} autoFocus={!exerciseId} placeholder="Limite notevole" className={errors.title ? 'invalid' : undefined} /></label>
    {variants.map((variant, index) => <VariantEditor key={index} variant={variant} index={index} count={variants.length} errors={errors}
      onChange={next => change(variants.map((item, current) => current === index ? next : item))}
      onDuplicate={() => change([...variants.slice(0, index + 1), { ...variant, options: [...variant.options] }, ...variants.slice(index + 1)])}
      onRemove={() => { if (window.confirm(`Eliminare la variante ${index + 1}?`)) change(variants.filter((_, current) => current !== index)); }} />)}
    <button type="button" className="secondary" onClick={() => change([...variants, emptyVariant()])}>+ Variante</button>
    <div className="save-bar">
      {error && <p role="alert" className="error">{error}</p>}
      <span role="status" className="notice">{pending ? 'Salvataggio…' : notice || (dirty ? 'Modifiche non salvate' : '')}</span>
      <span className="spacer" />
      {exerciseId && <button type="button" className="secondary danger" disabled={pending} onClick={() => void remove()}>Elimina esercizio</button>}
      <button disabled={pending}>Salva esercizio</button>
    </div>
  </form>;
}
