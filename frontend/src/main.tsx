import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

const root = document.getElementById('root');

if (!root) {
  throw new Error('Elemento root mancante');
}

createRoot(root).render(
  <StrictMode>
    <main>
      <h1>Gradient</h1>
      <p>Ambiente di sviluppo inizializzato.</p>
    </main>
  </StrictMode>,
);
