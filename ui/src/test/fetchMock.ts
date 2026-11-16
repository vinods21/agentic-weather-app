import { vi } from 'vitest';

/** A request as seen at the fetch boundary. Header names are lower-cased. */
export interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
  credentials: RequestCredentials | undefined;
  signal: AbortSignal | undefined;
}

export type Responder = (request: RecordedRequest) => Response | Promise<Response>;

interface Route {
  method: string;
  url: string | RegExp;
  responder: Responder;
  remaining: number;
}

// ---------------------------------------------------------------------------------------------
// Network guard (ADR 0018 section 2): every fetch/XHR that no test handler matched is recorded
// and rejected. setup.ts fails the test in afterEach when the record is not empty, so a
// swallowed rejection (for example rendered as the network message) still fails the test.
// ---------------------------------------------------------------------------------------------
const unmocked: string[] = [];

/** Returns and clears the unmocked-call record. */
export function drainUnmocked(): string[] {
  return unmocked.splice(0, unmocked.length);
}

function rejectUnmocked(description: string): Promise<never> {
  unmocked.push(description);
  return Promise.reject(new TypeError(`Unmocked network request: ${description}`));
}

const guardFetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> =>
  rejectUnmocked(`${(init?.method ?? 'GET').toUpperCase()} ${describeInput(input)}`);

class GuardXMLHttpRequest {
  open(method: string, url: string | URL): void {
    unmocked.push(`XHR ${method.toUpperCase()} ${String(url)}`);
    throw new Error(`Unmocked network request: XHR ${method} ${String(url)}`);
  }
}

/** Installs the guard on globalThis (restored after each test by `unstubGlobals`). */
export function installNetworkGuard(): void {
  vi.stubGlobal('fetch', guardFetch);
  vi.stubGlobal('XMLHttpRequest', GuardXMLHttpRequest);
}

function describeInput(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

function normaliseHeaders(headers: HeadersInit | undefined): Record<string, string> {
  const result: Record<string, string> = {};
  new Headers(headers).forEach((value, key) => {
    result[key.toLowerCase()] = value;
  });
  return result;
}

function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError');
}

/**
 * Replaces fetch with a handler that answers registered routes and records every request.
 * Unmatched requests go to the network guard (recorded and rejected). Routes are matched in
 * registration order; a route registered with `times` stops matching once used up.
 */
export function mockFetch() {
  const routes: Route[] = [];
  const calls: RecordedRequest[] = [];

  const fetchImpl = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const method = (init?.method ?? 'GET').toUpperCase();
    const url = describeInput(input);
    const route = routes.find(
      (r) =>
        r.remaining > 0 &&
        r.method === method &&
        (typeof r.url === 'string' ? r.url === url : r.url.test(url)),
    );
    if (!route) return rejectUnmocked(`${method} ${url}`);
    route.remaining -= 1;

    const signal = init?.signal ?? undefined;
    const request: RecordedRequest = {
      url,
      method,
      headers: normaliseHeaders(init?.headers),
      body: typeof init?.body === 'string' ? init.body : undefined,
      credentials: init?.credentials,
      signal,
    };
    calls.push(request);

    if (signal?.aborted) return Promise.reject(abortError(signal));
    return new Promise<Response>((resolve, reject) => {
      const onAbort = () => reject(abortError(signal as AbortSignal));
      signal?.addEventListener('abort', onAbort, { once: true });
      Promise.resolve()
        .then(() => route.responder(request))
        .then(resolve, reject)
        .finally(() => signal?.removeEventListener('abort', onAbort));
    });
  };

  vi.stubGlobal('fetch', fetchImpl);

  const api = {
    calls,
    fetch: fetchImpl,
    on(method: string, url: string | RegExp, responder: Responder, options: { times?: number } = {}) {
      routes.push({
        method: method.toUpperCase(),
        url,
        responder,
        remaining: options.times ?? Number.POSITIVE_INFINITY,
      });
      return api;
    },
  };
  return api;
}

export type FetchMock = ReturnType<typeof mockFetch>;

/** A fresh JSON Response (create one per call: a Response body can be read only once). */
export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...headers,
    },
  });
}

/** A 204 No Content response. */
export function noContent(headers: Record<string, string> = {}): Response {
  return new Response(null, { status: 204, headers });
}

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

/** A promise that the test settles by hand, for in-flight states and races. */
export function deferred<T = Response>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
