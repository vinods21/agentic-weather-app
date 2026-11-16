import { describe, expect, it } from 'vitest';
import { drainUnmocked, json, mockFetch } from './fetchMock';

describe('network guard', () => {
  it('AC-4: an unmocked fetch rejects and is recorded', async () => {
    await expect(fetch('http://localhost:8080/api/v1/weather/current?city=x')).rejects.toThrow(
      /Unmocked network request/,
    );
    expect(drainUnmocked()).toEqual(['GET http://localhost:8080/api/v1/weather/current?city=x']);
  });

  it('AC-4: XMLHttpRequest open throws and is recorded', () => {
    const xhr = new XMLHttpRequest();
    expect(() => xhr.open('GET', 'http://example.test/')).toThrow(/Unmocked network request/);
    expect(drainUnmocked()).toEqual(['XHR GET http://example.test/']);
  });

  it('AC-4: a request that matches no route of a mock is recorded as unmocked', async () => {
    mockFetch().on('GET', 'http://a.test/x', () => json(200, {}));
    await expect(fetch('http://a.test/y', { method: 'POST' })).rejects.toThrow();
    expect(drainUnmocked()).toEqual(['POST http://a.test/y']);
  });

  it('records method, url, headers, body and credentials of mocked requests', async () => {
    const mock = mockFetch().on('POST', /\/items$/, () => json(201, { ok: true }));
    const res = await fetch('http://a.test/items', {
      method: 'post',
      headers: { 'X-Client-Id': 'abc', 'Content-Type': 'application/json' },
      body: '{"a":1}',
      credentials: 'omit',
    });
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ ok: true });
    expect(mock.calls).toHaveLength(1);
    expect(mock.calls[0]).toMatchObject({
      url: 'http://a.test/items',
      method: 'POST',
      headers: { 'x-client-id': 'abc', 'content-type': 'application/json' },
      body: '{"a":1}',
      credentials: 'omit',
    });
  });

  it('a route with times stops matching once used up', async () => {
    mockFetch().on('GET', 'http://a.test/', () => json(200, {}), { times: 1 });
    await fetch('http://a.test/');
    await expect(fetch('http://a.test/')).rejects.toThrow();
    expect(drainUnmocked()).toHaveLength(1);
  });

  it('a pending mocked request rejects when its signal aborts', async () => {
    mockFetch().on('GET', 'http://a.test/', () => new Promise<Response>(() => {}));
    const controller = new AbortController();
    const pending = fetch('http://a.test/', { signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });
});
