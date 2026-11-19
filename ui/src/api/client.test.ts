import { afterEach, describe, expect, it, vi } from 'vitest';
import { json, mockFetch, noContent } from '../test/fetchMock';
import { API, BASE_URL, CLIENT_ID, FAV_LONDON, currentWeather, forecast, problem } from '../test/fixtures';
import { ApiError, createApiClient, isAbortError } from './client';

const ID = FAV_LONDON.id;

function client() {
  return createApiClient({ baseUrl: BASE_URL, clientId: CLIENT_ID });
}

async function failure(promise: Promise<unknown>) {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    return (error as ApiError).failure;
  }
  throw new Error('expected the call to fail');
}

describe('api client: requests', () => {
  it('AC-3, AC-9, AC-10: builds weather URLs from the base URL with encoded query values', async () => {
    const mock = mockFetch()
      .on('GET', /\/weather\/current\?/, () => json(200, currentWeather()))
      .on('GET', /\/weather\/forecast\?/, () => json(200, forecast()));
    await client().getCurrentWeather('São Paulo', 'metric');
    await client().getForecast('London', 'imperial', 3);
    expect(mock.calls.map((c) => c.url)).toEqual([
      `${API}/weather/current?city=S%C3%A3o%20Paulo&units=metric`,
      `${API}/weather/forecast?city=London&units=imperial&days=3`,
    ]);
  });

  it('AC-3: a custom base URL gives no double slash', async () => {
    const mock = mockFetch().on('GET', /.*/, () => json(200, { items: [] }));
    await createApiClient({ baseUrl: 'https://api.example.test', clientId: CLIENT_ID }).listFavourites();
    expect(mock.calls[0]?.url).toBe('https://api.example.test/api/v1/favourites');
  });

  it('AC-7: weather requests carry no X-Client-Id and no custom headers', async () => {
    const mock = mockFetch()
      .on('GET', /\/weather\/current\?/, () => json(200, currentWeather()))
      .on('GET', /\/weather\/forecast\?/, () => json(200, forecast()));
    await client().getCurrentWeather('London', 'metric');
    await client().getForecast('London', 'metric', 5);
    for (const call of mock.calls) {
      expect(call.headers).toEqual({});
      expect(call.credentials).toBe('omit');
    }
  });

  it('AC-7: every favourites request carries X-Client-Id, credentials omit and no X-Request-Id', async () => {
    const mock = mockFetch()
      .on('GET', `${API}/favourites`, () => json(200, { items: [FAV_LONDON] }))
      .on('POST', `${API}/favourites`, () => json(201, FAV_LONDON))
      .on('PATCH', `${API}/favourites/${ID}`, () => json(200, FAV_LONDON))
      .on('PUT', `${API}/favourites/order`, () => json(200, { items: [FAV_LONDON] }))
      .on('DELETE', `${API}/favourites/${ID}`, () => noContent())
      .on('GET', `${API}/favourites/weather?units=metric`, () => json(200, { items: [] }))
      .on('GET', `${API}/favourites/forecast?units=metric&days=3`, () => json(200, { items: [] }));
    const api = client();
    await api.listFavourites();
    await api.createFavourite({ city: 'London', label: 'Home' });
    await api.updateFavouriteLabel(ID, null);
    await api.reorderFavourites([ID]);
    await expect(api.deleteFavourite(ID)).resolves.toBeUndefined();
    await api.listFavouritesWeather('metric');
    await api.listFavouritesForecast('metric', 3);

    expect(mock.calls).toHaveLength(7);
    for (const call of mock.calls) {
      expect(call.headers['x-client-id']).toBe(CLIENT_ID);
      expect(call.headers['x-request-id']).toBeUndefined();
      expect(call.credentials).toBe('omit');
    }
  });

  it('AC-22, AC-25, AC-26: JSON bodies are sent with Content-Type application/json', async () => {
    const mock = mockFetch()
      .on('POST', `${API}/favourites`, () => json(201, FAV_LONDON))
      .on('PATCH', `${API}/favourites/${ID}`, () => json(200, FAV_LONDON))
      .on('PUT', `${API}/favourites/order`, () => json(200, { items: [] }));
    const api = client();
    await api.createFavourite({ city: 'London', label: 'Home' });
    await api.createFavourite({ city: 'London' });
    await api.updateFavouriteLabel(ID, 'Work');
    await api.updateFavouriteLabel(ID, null);
    await api.reorderFavourites(['b', 'a']);
    expect(mock.calls.map((c) => c.body)).toEqual([
      '{"city":"London","label":"Home"}',
      '{"city":"London"}',
      '{"label":"Work"}',
      '{"label":null}',
      '{"ids":["b","a"]}',
    ]);
    for (const call of mock.calls) expect(call.headers['content-type']).toBe('application/json');
  });

  it('encodes path ids', async () => {
    const mock = mockFetch().on('DELETE', /.*/, () => noContent());
    await client().deleteFavourite('a/b c');
    expect(mock.calls[0]?.url).toBe(`${API}/favourites/a%2Fb%20c`);
  });
});

describe('api client: failures', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('AC-18: a Problem body gives an http failure with the problem and its requestId', async () => {
    const body = problem(404, 'LOCATION_NOT_FOUND', { requestId: 'body-id' });
    mockFetch().on('GET', /.*/, () => json(404, body, { 'X-Request-Id': 'header-id' }));
    expect(await failure(client().getCurrentWeather('Nowhere', 'metric'))).toEqual({
      kind: 'http',
      status: 404,
      problem: body,
      requestId: 'body-id',
    });
  });

  it('AC-18: an HTML 502 gives an http failure without a problem, requestId from the header', async () => {
    mockFetch().on(
      'GET',
      /.*/,
      () => new Response('<html><body>Bad gateway</body></html>', { status: 502, headers: { 'X-Request-Id': 'hdr-1' } }),
    );
    expect(await failure(client().getCurrentWeather('London', 'metric'))).toEqual({
      kind: 'http',
      status: 502,
      problem: undefined,
      requestId: 'hdr-1',
    });
  });

  it('AC-18: a JSON 415 without code is not treated as a problem', async () => {
    mockFetch().on('POST', /.*/, () =>
      json(415, { timestamp: '2026-11-16T10:00:00Z', status: 415, error: 'Unsupported Media Type' }),
    );
    const result = await failure(client().createFavourite({ city: 'London' }));
    expect(result).toMatchObject({ kind: 'http', status: 415, problem: undefined, requestId: undefined });
  });

  it('a 2xx body that is not JSON gives an http failure', async () => {
    mockFetch().on('GET', /.*/, () => new Response('not json', { status: 200 }));
    expect(await failure(client().listFavourites())).toMatchObject({ kind: 'http', status: 200 });
  });

  it('AC-19: a rejected fetch gives a network failure carrying the base URL', async () => {
    mockFetch().on('GET', /.*/, () => Promise.reject(new TypeError('Failed to fetch')));
    expect(await failure(client().getCurrentWeather('London', 'metric'))).toEqual({
      kind: 'network',
      baseUrl: BASE_URL,
    });
  });

  it('AC-19: a request that exceeds the timeout gives a network failure', async () => {
    vi.useFakeTimers();
    mockFetch().on('GET', /.*/, () => new Promise<Response>(() => {}));
    const pending = failure(createApiClient({ baseUrl: BASE_URL, clientId: CLIENT_ID, timeoutMs: 15_000 }).listFavourites());
    await vi.advanceTimersByTimeAsync(15_000);
    expect(await pending).toEqual({ kind: 'network', baseUrl: BASE_URL });
  });

  it("a caller's abort is rethrown as an AbortError, not a network failure", async () => {
    mockFetch().on('GET', /.*/, () => new Promise<Response>(() => {}));
    const controller = new AbortController();
    const pending = client().getCurrentWeather('London', 'metric', controller.signal);
    controller.abort();
    const error = await pending.catch((e: unknown) => e);
    expect(error).not.toBeInstanceOf(ApiError);
    expect(isAbortError(error)).toBe(true);
  });
});
