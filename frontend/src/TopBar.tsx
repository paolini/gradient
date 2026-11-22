import { useEffect, useRef, useState } from 'react';

type User = { name: string; email: string; provider: string };

function initials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? '?').slice(0, 2)).toUpperCase();
}

export default function TopBar({ user, pending, onLogout }: { user: User; pending: boolean; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const firstItemRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    firstItemRef.current?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setOpen(false); buttonRef.current?.focus(); }
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onPointer); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const provider = user.provider === 'google' ? 'Google' : 'UniPi';
  return <header className="topbar">
    <div className="topbar-inner">
      <span className="topbar-brand">Gradient</span>
      <div className="user-menu" ref={containerRef}>
        <button ref={buttonRef} type="button" className="user-button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(value => !value)}>
          <span className="avatar" aria-hidden="true">{initials(user.name)}</span>
          <span className="user-name">{user.name}</span>
          <span className="caret" aria-hidden="true">▾</span>
        </button>
        {open && <div className="user-dropdown" role="menu" aria-label="Account">
          <div className="user-info"><strong>{user.name}</strong><span>{user.email}</span><span>Accesso con {provider}</span></div>
          <button ref={firstItemRef} type="button" role="menuitem" disabled={pending} onClick={() => { setOpen(false); onLogout(); }}>Esci</button>
        </div>}
      </div>
    </div>
  </header>;
}
