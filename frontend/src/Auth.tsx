import { useEffect, useState } from 'react';
import App from './App';

type Session = { user: { id: string; name: string; email: string; provider: string } | null; providers: string[] };

export default function Auth() {
  const [session, setSession] = useState<Session | null>(null);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  useEffect(() => {
    let active = true;
    const load = () => {
      setSession(null);
      fetch('/auth/session').then(async response => {
        if (!response.ok) throw new Error('Impossibile verificare la sessione.');
        const data: Session = await response.json();
        if (active) setSession(data);
      }).catch(() => { if (active) setError('Impossibile verificare la sessione. Controlla il backend e ricarica la pagina.'); });
    };
    load();
    window.addEventListener('gradient-session-expired', load);
    return () => { active = false; window.removeEventListener('gradient-session-expired', load); };
  }, []);
  async function logout() {
    setPending(true);
    setError('');
    try {
      const response = await fetch('/auth/logout', { method: 'POST', headers: { 'X-Gradient-Request': '1' } });
      if (!response.ok) throw new Error();
      setSession(current => current ? { ...current, user: null } : null);
    } catch { setError('Uscita non riuscita. Riprova.'); }
    finally { setPending(false); }
  }
  if (!session) return <main><h1>Gradient</h1><p role="status">{error || 'Verifica della sessione…'}</p></main>;
  if (!session.user) return <main><h1>Gradient</h1><p>Accedi per gestire i tuoi corsi e le valutazioni.</p>
    {new URLSearchParams(window.location.search).has('auth_error') && <p role="alert">Accesso non riuscito o annullato. Riprova.</p>}
    {session.providers.map(provider => <p key={provider}><a href={`/auth/login/${provider}`}>Accedi con {provider === 'google' ? 'Google' : 'UniPi'}</a></p>)}
    {!session.providers.length && <p>Nessun metodo di accesso configurato. Contatta chi gestisce il servizio.</p>}
  </main>;
  return <><header className="account"><span>{session.user.name} · {session.user.email} ({session.user.provider === 'google' ? 'Google' : 'UniPi'})</span><button disabled={pending} onClick={logout}>Esci</button></header>
    {error && <p role="alert">{error}</p>}<App key={session.user.id} /></>;
}
