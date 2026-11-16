import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { drainUnmocked, installNetworkGuard } from './fetchMock';

beforeEach(() => {
  installNetworkGuard();
  if (typeof window !== 'undefined') {
    window.localStorage.clear();
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname);
  }
});

afterEach(() => {
  cleanup();
  const leaked = drainUnmocked();
  if (leaked.length > 0) {
    throw new Error(`Test made unmocked network request(s):\n  ${leaked.join('\n  ')}`);
  }
});
