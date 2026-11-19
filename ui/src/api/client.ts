// The only module that calls fetch (ADR 0017 section 2, design "api/client.ts").
import type {
  CreateFavouriteRequest,
  CurrentWeather,
  Favourite,
  FavouriteForecastList,
  FavouriteList,
  FavouriteWeatherList,
  Forecast,
  Problem,
  Units,
} from './types';

export const DEFAULT_TIMEOUT_MS = 15_000;

export type ApiFailure =
  | { kind: 'network'; baseUrl: string }
  | { kind: 'http'; status: number; problem?: Problem; requestId?: string };

export class ApiError extends Error {
  readonly failure: ApiFailure;

  constructor(failure: ApiFailure) {
    super(failure.kind === 'network' ? `Network error (${failure.baseUrl})` : `HTTP ${failure.status}`);
    this.name = 'ApiError';
    this.failure = failure;
  }
}

export interface ApiClient {
  getCurrentWeather(city: string, units: Units, signal?: AbortSignal): Promise<CurrentWeather>;
  getForecast(city: string, units: Units, days: number, signal?: AbortSignal): Promise<Forecast>;
  listFavourites(signal?: AbortSignal): Promise<FavouriteList>;
  createFavourite(request: CreateFavouriteRequest): Promise<Favourite>;
  updateFavouriteLabel(id: string, label: string | null): Promise<Favourite>;
  reorderFavourites(ids: string[]): Promise<FavouriteList>;
  deleteFavourite(id: string): Promise<void>;
  listFavouritesWeather(units: Units, signal?: AbortSignal): Promise<FavouriteWeatherList>;
  listFavouritesForecast(units: Units, days: number, signal?: AbortSignal): Promise<FavouriteForecastList>;
}

export interface ApiClientOptions {
  /** Resolved base URL without a trailing slash (see config.ts). */
  baseUrl: string;
  clientId: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

/** A Problem Details body: an object with a string `code`. */
function asProblem(body: unknown): Problem | undefined {
  if (typeof body === 'object' && body !== null && typeof (body as { code?: unknown }).code === 'string') {
    return body as Problem;
  }
  return undefined;
}

function enc(value: string | number): string {
  return encodeURIComponent(String(value));
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const { baseUrl, clientId } = options;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  // Look fetch up per call, so tests that stub globalThis.fetch after creation are honoured.
  const doFetch: typeof fetch = (input, init) => (options.fetchImpl ?? globalThis.fetch)(input, init);

  const url = (path: string) => `${baseUrl}/api/v1${path}`;

  async function request<T>(
    method: string,
    path: string,
    { body, withClientId, signal }: { body?: unknown; withClientId: boolean; signal?: AbortSignal },
  ): Promise<T> {
    const headers: Record<string, string> = {};
    if (withClientId) headers['X-Client-Id'] = clientId;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    const controller = new AbortController();
    const onCallerAbort = () => controller.abort(signal?.reason);
    if (signal?.aborted) controller.abort(signal.reason);
    else signal?.addEventListener('abort', onCallerAbort, { once: true });
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      let res: Response;
      try {
        res = await doFetch(url(path), {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          credentials: 'omit',
          signal: controller.signal,
        });
      } catch (error) {
        if (signal?.aborted) throw error;
        throw new ApiError({ kind: 'network', baseUrl });
      }

      const headerRequestId = res.headers.get('X-Request-Id') ?? undefined;
      if (res.ok) {
        if (res.status === 204) return undefined as T;
        try {
          return (await res.json()) as T;
        } catch (error) {
          if (signal?.aborted) throw error;
          throw new ApiError({ kind: 'http', status: res.status, requestId: headerRequestId });
        }
      }

      let text = '';
      try {
        text = await res.text();
      } catch (error) {
        if (signal?.aborted) throw error;
      }
      let problem: Problem | undefined;
      try {
        problem = asProblem(JSON.parse(text));
      } catch {
        problem = undefined;
      }
      const requestId = typeof problem?.requestId === 'string' ? problem.requestId : headerRequestId;
      throw new ApiError({ kind: 'http', status: res.status, problem, requestId });
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onCallerAbort);
    }
  }

  return {
    getCurrentWeather: (city, units, signal) =>
      request('GET', `/weather/current?city=${enc(city)}&units=${enc(units)}`, { withClientId: false, signal }),
    getForecast: (city, units, days, signal) =>
      request('GET', `/weather/forecast?city=${enc(city)}&units=${enc(units)}&days=${enc(days)}`, {
        withClientId: false,
        signal,
      }),
    listFavourites: (signal) => request('GET', '/favourites', { withClientId: true, signal }),
    createFavourite: (body) => request('POST', '/favourites', { withClientId: true, body }),
    updateFavouriteLabel: (id, label) =>
      request('PATCH', `/favourites/${enc(id)}`, { withClientId: true, body: { label } }),
    reorderFavourites: (ids) => request('PUT', '/favourites/order', { withClientId: true, body: { ids } }),
    deleteFavourite: (id) => request('DELETE', `/favourites/${enc(id)}`, { withClientId: true }),
    listFavouritesWeather: (units, signal) =>
      request('GET', `/favourites/weather?units=${enc(units)}`, { withClientId: true, signal }),
    listFavouritesForecast: (units, days, signal) =>
      request('GET', `/favourites/forecast?units=${enc(units)}&days=${enc(days)}`, { withClientId: true, signal }),
  };
}

/** True for the rejection a caller's own abort produces (the request slot drops it). */
export function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError';
}
