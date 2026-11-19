import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { drainUnmocked, installNetworkGuard } from './fetchMock';

// findBy*/waitFor default to 1 s. With all component test files running in parallel, the first
// render in a worker can take longer than that on a loaded machine (seen on Windows), which made
// the first test of a file fail intermittently. 5 s keeps failures fast but not flaky.
configure({ asyncUtilTimeout: 5000 });

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
