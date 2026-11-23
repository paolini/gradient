import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { graphql, operations, requireSuccess } from './api';
import type { ApiToken, MutationResult } from './api';
import Dialog from './Dialog';
import { LoadError, formatDateTime, message } from './common';

const DURATIONS = [{ label: '30 giorni', days: 30 }, { label: '90 giorni', days: 90 }, { label: '1 anno', days: 365 }, { label: 'Nessuna scadenza', days: null }];

const utc = (value: string) => new Date(value.endsWith('Z') ? value : `${value}Z`);

function Expiry({ value }: { value: string | null }) {
  if (!value) return <>Mai</>;
  return utc(value) <= new Date() ? <span className="token-expired">Scaduto il {formatDateTime(value)}</span> : <>{formatDateTime(value)}</>;
}

function TokenForm({ onCreated }: { onCreated: (token: ApiToken, secret: string) => void }) {
  const [name, setName] = useState('');
  const [readOnly, setReadOnly] = useState(false);
  const [duration, setDuration] = useState('90');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true); setError('');
    try {
      const data = await graphql<{ createApiToken: MutationResult<{ token: ApiToken | null; secret: string | null }> }>(operations.createApiToken, {
        input: { name, readOnly, expiresInDays: duration ? Number(duration) : null },
      });
      const result = requireSuccess(data.createApiToken);
      if (!result.token || !result.secret) throw new Error('Token non restituito dal server.');
      onCreated(result.token, result.secret);
    } catch (failure) { setError(message(failure, 'Creazione non riuscita.')); }
    finally { setPending(false); }
  }
  return <form onSubmit={submit}>{error && <p role="alert" className="error">{error}</p>}
    <fieldset disabled={pending}>
      <label>Nome<input value={name} onChange={event => setName(event.target.value)} maxLength={100} required autoFocus placeholder="Script di esportazione" /></label>
      <label>Permessi<select value={readOnly ? 'read' : 'full'} onChange={event => setReadOnly(event.target.value === 'read')}>
        <option value="full">Accesso completo (lettura e modifica)</option>
        <option value="read">Sola lettura</option>
      </select></label>
      <label>Scadenza<select value={duration} onChange={event => setDuration(event.target.value)}>
        {DURATIONS.map(item => <option key={item.label} value={item.days ?? ''}>{item.label}</option>)}
      </select></label>
      <div className="dialog-actions"><button>Crea token</button></div>
    </fieldset>
  </form>;
}

function CreatedToken({ token, secret, onClose }: { token: ApiToken; secret: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const example = `curl ${window.location.origin}/graphql \\\n  -H 'Authorization: Bearer ${secret}' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"query": "{ courses { id name academicYear } }"}'`;
  async function copy() {
    try { await navigator.clipboard.writeText(secret); setCopied(true); } catch { setCopied(false); }
  }
  return <>
    <p>Copia ora il token «{token.name}»: per sicurezza non verrà più mostrato. Chi lo possiede accede ai tuoi dati{token.readOnly ? ' in sola lettura' : ''}.</p>
    <div className="token-secret">
      <input readOnly value={secret} aria-label="Token" onFocus={event => event.target.select()} />
      <button type="button" className="secondary" onClick={copy}>{copied ? 'Copiato' : 'Copia'}</button>
    </div>
    <p>Esempio di utilizzo:</p>
    <pre className="token-example">{example}</pre>
    <div className="dialog-actions"><button type="button" onClick={onClose}>Fatto</button></div>
  </>;
}

export default function ApiTokensPage() {
  const [state, setState] = useState<{ items: ApiToken[]; loading: boolean; error: string }>({ items: [], loading: true, error: '' });
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<{ token: ApiToken; secret: string } | null>(null);
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');

  useEffect(() => {
    let active = true;
    graphql<{ apiTokens: ApiToken[] }>(operations.apiTokens).then(
      data => { if (active) setState({ items: data.apiTokens, loading: false, error: '' }); },
      failure => { if (active) setState({ items: [], loading: false, error: message(failure, 'Caricamento non riuscito.') }); },
    );
    return () => { active = false; };
  }, [revision]);

  async function revoke(token: ApiToken) {
    if (!window.confirm(`Revocare il token «${token.name}»? Gli script che lo usano smetteranno di funzionare.`)) return;
    setBusy(token.id); setActionError('');
    try {
      const data = await graphql<{ revokeApiToken: MutationResult<object> }>(operations.revokeApiToken, { id: token.id });
      requireSuccess(data.revokeApiToken);
      setState(current => ({ ...current, items: current.items.filter(item => item.id !== token.id) }));
    } catch (failure) { setActionError(message(failure, 'Revoca non riuscita.')); }
    finally { setBusy(''); }
  }

  const closeDialog = () => { setCreating(false); setCreated(null); };
  return <>
    <div className="page-heading"><div><h1>Token API</h1>
      <p>Per accedere a GraphQL da script e programmi, con l'intestazione <code>Authorization: Bearer …</code>.</p></div>
      <button type="button" onClick={() => setCreating(true)}>+ Nuovo token</button></div>
    {state.error && <LoadError text={state.error} onRetry={() => { setState(current => ({ ...current, loading: true, error: '' })); setRevision(value => value + 1); }} />}
    {actionError && <p role="alert" className="error">{actionError}</p>}
    {state.loading ? <p role="status">Caricamento…</p> : !state.items.length && !state.error
      ? <div className="empty-state"><p>Nessun token. Creane uno per usare Gradient da uno script.</p></div>
      : state.items.length > 0 && <div className="sheet table-scroll"><table>
          <thead><tr><th>Nome</th><th>Permessi</th><th>Creato</th><th>Scadenza</th><th>Ultimo utilizzo</th><th aria-label="Azioni"></th></tr></thead>
          <tbody>{state.items.map(token => <tr key={token.id}>
            <td>{token.name}</td>
            <td>{token.readOnly ? 'Sola lettura' : 'Completo'}</td>
            <td>{formatDateTime(token.createdAt)}</td>
            <td><Expiry value={token.expiresAt} /></td>
            <td>{token.lastUsedAt ? formatDateTime(token.lastUsedAt) : <span className="muted">Mai</span>}</td>
            <td className="token-actions"><button type="button" className="secondary small" disabled={busy === token.id} onClick={() => revoke(token)}>Revoca</button></td>
          </tr>)}</tbody>
        </table></div>}
    {creating && <Dialog title={created ? 'Token creato' : 'Nuovo token API'} wide={!!created} onClose={closeDialog}>
      {created ? <CreatedToken {...created} onClose={closeDialog} />
        : <TokenForm onCreated={(token, secret) => { setCreated({ token, secret }); setState(current => ({ ...current, items: [token, ...current.items] })); }} />}
    </Dialog>}
  </>;
}
