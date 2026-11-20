import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import Auth from './Auth';

const root = document.getElementById('root');

if (!root) {
  throw new Error('Elemento root mancante');
}

createRoot(root).render(
  <StrictMode>
    <Auth />
  </StrictMode>,
);
