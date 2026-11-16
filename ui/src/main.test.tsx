// Tester: boots the real entry point (main.tsx) to check the wiring that the view tests bypass
// through renderApp: default base URL (AC-3), client id creation/reuse/repair (AC-6, AC-8) and
// the header on favourites requests (AC-7), stored units (AC-11), data retention (NFR).
import { screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch } from './test/fetchMock';
import { CLIENT_ID_KEY, CLIENT_ID_PATTERN } from './storage/clientId';

const DEFAULT_API = 'http://localhost:8080/api/v1';

let root: HTMLElement | null = null;

async function boot(hash = '') {
  if (hash) window.history.replaceState(null, '', hash);
  root = document.createElement('div');
  root.id = 'root';
  document.body.appendChild(root);
  vi.resetModules();
  await import('./main');
}

afterEach(() => {
  root?.remove();
  root = null;
});

describe('main.tsx wiring (tester)', () => {
  it('AC-6, AC-7, AC-3: first load stores a valid client id and sends it to the default base URL', async () => {
    expect(import.meta.env.VITE_API_BASE_URL ?? '').toBe('');
    const mock = mockFetch().on('GET', `${DEFAULT_API}/favourites`, () => json(200, { items: [] }));
    await boot('#/favourites');
    await screen.findByText('No favourites yet.');
    const stored = window.localStorage.getItem(CLIENT_ID_KEY);
    expect(stored).toMatch(CLIENT_ID_PATTERN);
    expect(mock.calls).toHaveLength(1);
    expect(mock.calls[0]?.headers['x-client-id']).toBe(stored);
    expect(mock.calls[0]?.credentials).toBe('omit');
    expect(mock.calls[0]?.headers['x-request-id']).toBeUndefined();
  });

  it('AC-6: a reload reuses the stored id', async () => {
    window.localStorage.setItem(CLIENT_ID_KEY, 'existing-id-123');
    const mock = mockFetch().on('GET', `${DEFAULT_API}/favourites`, () => json(200, { items: [] }));
    await boot('#/favourites');
    await screen.findByText('No favourites yet.');
    expect(window.localStorage.getItem(CLIENT_ID_KEY)).toBe('existing-id-123');
    expect(mock.calls[0]?.headers['x-client-id']).toBe('existing-id-123');
  });

  it.each([['bad id!'], ['z'.repeat(65)]])('AC-8: an invalid stored id %j is replaced before any request', async (bad) => {
    window.localStorage.setItem(CLIENT_ID_KEY, bad);
    const mock = mockFetch().on('GET', `${DEFAULT_API}/favourites`, () => json(200, { items: [] }));
    await boot('#/favourites');
    await screen.findByText('No favourites yet.');
    const stored = window.localStorage.getItem(CLIENT_ID_KEY);
    expect(stored).not.toBe(bad);
    expect(stored).toMatch(CLIENT_ID_PATTERN);
    expect(mock.calls[0]?.headers['x-client-id']).toBe(stored);
  });

  it('AC-8: when localStorage throws, the app still works with an in-memory id and shows the notice', async () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    const mock = mockFetch().on('GET', `${DEFAULT_API}/favourites`, () => json(200, { items: [] }));
    await boot('#/favourites');
    await screen.findByText('No favourites yet.');
    expect(screen.getByRole('note')).toHaveTextContent('forget your favourites when the tab closes');
    expect(mock.calls[0]?.headers['x-client-id']).toMatch(CLIENT_ID_PATTERN);
    expect(screen.getByLabelText('Units')).toHaveValue('metric');
  });

  it('AC-11: a stored imperial choice is used on load', async () => {
    window.localStorage.setItem('weather.units', 'imperial');
    await boot();
    await waitFor(() => expect(screen.getByLabelText('Units')).toHaveValue('imperial'));
  });

  it('NFR data retention: localStorage only ever holds weather.clientId and weather.units', async () => {
    mockFetch()
      .on('GET', /\/favourites\/weather\?/, () => json(200, { items: [] }))
      .on('GET', /\/favourites\/forecast\?/, () => json(200, { items: [] }));
    await boot('#/dashboard');
    await screen.findByText(/No favourites yet\./);
    const keys = Array.from({ length: window.localStorage.length }, (_, i) => window.localStorage.key(i));
    expect(keys.every((k) => k === 'weather.clientId' || k === 'weather.units')).toBe(true);
    expect(keys).toContain('weather.clientId');
  });
});
