import { describe, expect, it } from 'vitest';
import { fakeStorage } from '../test/fakeStorage';
import { CLIENT_ID_KEY, CLIENT_ID_PATTERN, loadClientId, randomUuid } from './clientId';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('loadClientId', () => {
  it('AC-6: generates a valid id on empty storage and stores it under weather.clientId', () => {
    const storage = fakeStorage();
    const result = loadClientId(() => storage);
    expect(result.persistent).toBe(true);
    expect(result.id).toMatch(CLIENT_ID_PATTERN);
    expect(result.id).toMatch(UUID_V4);
    expect(storage.getItem(CLIENT_ID_KEY)).toBe(result.id);
  });

  it('AC-6: reuses the stored id on the next load', () => {
    const storage = fakeStorage();
    const first = loadClientId(() => storage);
    const second = loadClientId(() => storage);
    expect(second).toEqual(first);
  });

  it('AC-6: keeps any stored value that matches the pattern', () => {
    const storage = fakeStorage({ [CLIENT_ID_KEY]: 'abc.DEF_1-2' });
    expect(loadClientId(() => storage)).toEqual({ id: 'abc.DEF_1-2', persistent: true });
  });

  it.each([['bad id!'], ['a'.repeat(65)], ['']])('AC-8: replaces the invalid stored value %j', (bad) => {
    const storage = fakeStorage({ [CLIENT_ID_KEY]: bad });
    const result = loadClientId(() => storage);
    expect(result.id).not.toBe(bad);
    expect(result.id).toMatch(CLIENT_ID_PATTERN);
    expect(storage.getItem(CLIENT_ID_KEY)).toBe(result.id);
  });

  it('AC-8: accepts a 64-character stored value', () => {
    const id = 'a'.repeat(64);
    expect(loadClientId(() => fakeStorage({ [CLIENT_ID_KEY]: id })).id).toBe(id);
  });

  it('AC-8: uses an in-memory id when getting the storage throws', () => {
    const result = loadClientId(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(result.persistent).toBe(false);
    expect(result.id).toMatch(CLIENT_ID_PATTERN);
  });

  it('AC-8: uses an in-memory id when getItem throws', () => {
    const result = loadClientId(() => fakeStorage({}, ['getItem']));
    expect(result.persistent).toBe(false);
    expect(result.id).toMatch(CLIENT_ID_PATTERN);
  });

  it('AC-8: uses an in-memory id when setItem throws', () => {
    const result = loadClientId(() => fakeStorage({}, ['setItem']));
    expect(result.persistent).toBe(false);
    expect(result.id).toMatch(CLIENT_ID_PATTERN);
  });
});

describe('randomUuid', () => {
  it('AC-6: uses crypto.randomUUID when available', () => {
    expect(randomUuid()).toMatch(UUID_V4);
  });

  it('AC-6: falls back to getRandomValues and yields a v4 UUID', () => {
    const fallbackOnly = { getRandomValues: crypto.getRandomValues.bind(crypto) } as Pick<
      Crypto,
      'getRandomValues'
    >;
    for (let i = 0; i < 20; i += 1) {
      const id = randomUuid(fallbackOnly);
      expect(id).toMatch(UUID_V4);
      expect(id).toMatch(CLIENT_ID_PATTERN);
    }
  });
});
