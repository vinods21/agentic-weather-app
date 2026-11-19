// @vitest-environment node
import { describe, expect, it } from 'vitest';
import config from '../vite.config';

describe('vite config', () => {
  it('AC-2: dev server is fixed to localhost:5173 and fails if the port is taken', () => {
    expect(config.server).toMatchObject({ host: 'localhost', port: 5173, strictPort: true });
  });

  it('AC-2: preview server uses the same fixed origin', () => {
    expect(config.preview).toMatchObject({ host: 'localhost', port: 5173, strictPort: true });
  });

  it('AC-1: builds with a relative base so any static server can host it', () => {
    expect(config.base).toBe('./');
  });
});
