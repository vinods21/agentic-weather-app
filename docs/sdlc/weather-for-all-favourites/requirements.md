# Weather for all favourites: Requirements
Feature slug: weather-for-all-favourites · Status: draft

## Problem / goal
A client's saved favourites (`/api/v1/favourites`, max 20 per `X-Client-Id`) store a resolved
location snapshot (name, country, latitude, longitude). To show a "my places" dashboard today, the
UI calls `GET /api/v1/weather/current?city=<favourite.city>` once per favourite: up to 20 HTTP
round trips. With the Open-Meteo adapter each call also re-geocodes the city, unless the
geocoding cache entry happens to be warm. The saved-favourite-locations requirements listed this
as out of scope.

Goal: one read-only endpoint that returns the current weather for every favourite of the calling
client, in the client's stored order. It reuses the existing `CurrentWeather` schema per item,
looks weather up by the **stored coordinates** (no geocoding), fetches cold items in parallel
under a fixed concurrency limit and an overall time budget, and reports an upstream failure
**per item** instead of failing the whole list. It follows ADR 0002 (spec-first OpenAPI, drift
and conformance tests), ADR 0003 (Problem Details, `X-Request-Id`, additive-only v1), ADR 0007
(cache, fail open) and ADR 0009 (`X-Client-Id`).

Unless an AC says otherwise:
- the `it` profile applies, which means the stub provider (London, Paris, New York, Tokyo,
  Sydney). Favourites are created through `POST /api/v1/favourites` in the test, so their stored
  locations are the stub's locations.
- every request carries `X-Client-Id: <unique value per test>`. Tests delete the favourite rows
  they create in `@AfterEach`. The database is the compose Postgres in schema `it_<id>`, Redis is
  the compose Redis under prefix `it:<id>`. Testcontainers is never used, and no test starts or
  stops a container (ADR 0001).
- "the provider is not called" means zero interactions on a `@MockitoSpyBean WeatherProvider`.
- "real provider" ACs use the Open-Meteo adapter pointed at the in-process `support.FakeOpenMeteo`
  (e.g. through `CachingIntegrationTestBase`), with caching enabled and the cache empty at the
  start of the test, unless stated otherwise.
- the service clock is the `Clock` bean, fixed at `2026-11-15T10:00:00Z` where timestamps are
  asserted.
- "W(city, units)" means the `200` body of `GET /api/v1/weather/current?city=<city>&units=<units>`
  in the same context at the same clock instant.

Path, property, configuration and schema names below are proposals (A-10). The architect records
the final names in design.md and `openapi-v1.json`.

## User stories
- As a UI user, I want to see the current weather for all my saved places at once, so that my
  dashboard loads with one request.
- As a UI user, I want the places to appear in the order I arranged them, with their labels, so
  that the dashboard matches my list.
- As a UI user, I want the other places to still show when the weather for one place can't be
  fetched, so that one bad city doesn't blank the whole dashboard.
- As an operator, I want the endpoint to cost at most one upstream call per favourite on a cold
  cache (and none on a warm one), with bounded parallelism and a bounded response time, so that
  it doesn't burn the free-tier quota or hang requests.
- As a UI developer, I want the endpoint and its per-item error shape in the OpenAPI document,
  so that I can generate a client.

## Acceptance criteria

### Happy path: `GET /api/v1/favourites/weather`
- **AC-1**: Given the client created London (label `Home`), Paris (no label) and Tokyo in that
  order, when `GET /api/v1/favourites/weather` is requested, then the response is `200`,
  `Content-Type: application/json`, with an `X-Request-Id` header and body `{"items": [...]}`
  holding exactly 3 items in the order London, Paris, Tokyo. Each item has:
  - `id`, `city`, `location` equal to the favourite's `id`, `city` and `location` from
    `GET /api/v1/favourites`;
  - `label` `"Home"` for London; no `label` property for Paris and Tokyo;
  - `weather` equal to W(city, metric) for that city (same `location`, `observedAt`
    `"2026-11-15T10:00:00Z"`, `units "metric"`, values and `condition`);
  - no `error` property.
- **AC-2 (stored order)**: Given London, Paris and Tokyo with ids `L`, `P`, `T` and a reorder to
  `["T", "L", "P"]` through `PUT /api/v1/favourites/order`, when the endpoint is requested, then
  the items are in order T, L, P. With the real provider and `FakeOpenMeteo` `CURRENT` responses
  delayed so that they complete in the reverse order (e.g. T 600 ms, L 300 ms, P 0 ms), the order
  is still T, L, P.
- **AC-3 (units)**: Given London and Paris, when `?units=imperial` is requested, then each item's
  `weather` equals W(city, imperial) (`units "imperial"`, deg F, mph). `?units=metric` and no
  `units` parameter both give W(city, metric). For each of `units=IMPERIAL`, `units=kelvin` and
  `units=` the response status and `errors` are the same as for
  `/api/v1/weather/current?city=London&units=<same value>` (today `400 VALIDATION_FAILED`,
  `errors [{field "units", message "must be one of: metric, imperial"}]`), and the provider is
  not called. Unknown query parameters (e.g. `?page=2`) are ignored.
- **AC-4 (empty list)**: Given the client has no favourites, when the endpoint is requested, then
  the response is `200 {"items": []}` and the provider is not called.

### Client id (ADR 0009, unchanged rules)
- **AC-5**: When `X-Client-Id` is missing, empty, 65 characters long, or contains a character
  outside `[A-Za-z0-9._-]` (e.g. `a b`, `a/b`), then the response is `400 VALIDATION_FAILED` with
  `errors` containing `{field "X-Client-Id", message "must match ^[A-Za-z0-9._-]{1,64}$"}`, and the
  provider is not called. With an invalid client id **and** `units=kelvin`, `errors` contains at
  least the `X-Client-Id` entry. A 64-character valid id is accepted. The new handler method takes
  `ClientId` as its first parameter (the existing `FavouritesControllerSignatureTest`, or its
  equivalent for a new controller, covers it).
- **AC-6 (isolation)**: Given client `A` has London and Paris and client `B` has Tokyo, when `B`
  requests the endpoint, then `B` gets exactly one item (Tokyo). A client with no favourites gets
  `{"items": []}` even though `A` has favourites.

### Lookup by stored coordinates, cache and quota
- **AC-7 (no geocoding, cold cache)**: Given the real provider, a client with 3 favourites (London,
  created through the API against the fake's London geocoding, plus 2 more inserted
  directly into the `favourite` table with distinct coordinates (e.g. Paris 48.8566/2.3522 and
  Tokyo 35.6762/139.6503)) and an empty cache, and `FakeOpenMeteo.reset()` called after the
  setup, when the endpoint is requested, then the response is `200` with 3 `weather` items, and
  the fake receives exactly 3 `CURRENT` requests, 0 `GEOCODING` requests and 0 `DAILY` requests.
  The `latitude`/`longitude` parameters of the `CURRENT` requests are exactly the stored
  coordinates (the same `BigDecimal.toPlainString()` text the adapter already sends).
- **AC-8 (warm cache, shared with `/weather/current`)**: Given AC-7's state immediately after its
  request (cache warm, `FakeOpenMeteo.reset()` called), when the endpoint is requested again
  within `WEATHER_CACHE_CURRENT_TTL` (10m), then the fake receives 0 requests of any kind and each
  item's `weather.observedAt` equals the value from the first response. After that, a
  `GET /api/v1/weather/current?city=London` also makes 0 `CURRENT` requests (it reuses the same
  `current` cache entry, which is keyed by the stored coordinates), and the reverse also holds: a
  `CURRENT` entry written by `/weather/current?city=London` is served to this endpoint without a
  `CURRENT` request.
- **AC-9 (cache key reuse)**: Given AC-7's request, then Redis holds exactly one
  `CacheKeys`-built `current` entry per distinct stored coordinate pair, each with a TTL > 0 and
  ≤ the configured current TTL. No new key kind is written, and no key contains the client id,
  label or city text.
- **AC-10 (Redis down, fail open)**: Given the real provider and a test `@Primary CacheStore` on a
  closed local port (ADR 0007 §11), when the endpoint is requested for 3 favourites, then the
  response is `200` with 3 `weather` items (fetched upstream) and no `error` items. The existing
  `Cache operation failed …` WARN lines may appear; no other WARN or ERROR is logged.

### Partial failure (A-3)
- **AC-11 (one item fails)**: Given London, Paris and Tokyo and header `X-Request-Id: it-faw-11`,
  and the upstream fails for Paris only (stub path: the provider spy throws
  `WeatherProviderUnavailableException` for Paris's lookup; real path: `FakeOpenMeteo` answers
  `500` to the `CURRENT` request whose `latitude` is Paris's), when the endpoint is requested,
  then the response is `200` with 3 items in order. London and Tokyo have `weather` as in AC-1.
  The Paris item has `id`, `city`, `location` (and `label` if set), no `weather`, and
  `error {code "UPSTREAM_UNAVAILABLE", detail "The weather provider is temporarily unavailable."}`.
  Exactly one WARN line is logged by the endpoint for the request, containing `it-faw-11`, the
  number of failed items `1` and the total `3`, and no city, label, client id or coordinates.
  On the real path, the adapter's existing `Upstream call failed …` WARN line for the Paris call
  also contains `it-faw-11` (the request id reaches the worker that made the call).
- **AC-12 (all items fail)**: Given 3 favourites and the upstream failing for every lookup, when
  the endpoint is requested, then the response is still `200` with 3 items, each with `error`
  `UPSTREAM_UNAVAILABLE` and no `weather` (not `503`; A-3). After the failure is removed (spy
  reset / fake reset), the same request gives 3 `weather` items.
- **AC-13 (failures are not cached)**: Given AC-11's real-provider run, when the endpoint is
  requested again with the fake back to defaults, then Paris now has `weather`, and the fake
  receives exactly 1 `CURRENT` request (for Paris; London and Tokyo come from the cache).
- **AC-14 (not found per item)**: Given a favourite whose lookup throws `LocationNotFoundException`
  (e.g. the provider spy throws it for that favourite, or with the stub a row inserted directly
  with a location the stub does not know), when the endpoint is requested, then that item has
  `error {code "LOCATION_NOT_FOUND", detail <fixed text without the city>}` and no `weather`; the
  other items are unaffected and the status is `200`.
- **AC-15 (unexpected failure)**: Given the provider spy throws an `IllegalStateException` for one
  favourite's lookup, when the endpoint is requested, then the response is `500 INTERNAL_ERROR`
  (existing handler, Problem Details, no stack trace or message in the body); bugs are not masked
  as per-item errors. Given a database failure while loading the favourites, the response is also
  `500 INTERNAL_ERROR` and the provider is not called.

### Concurrency and time budget (A-5)
- **AC-16 (bounded parallelism)**: Given `weather.favourites.weather.max-concurrency=4`, the real
  provider, 20 favourites with distinct coordinates (inserted directly), an empty cache, and a
  `FakeOpenMeteo` `CURRENT` responder that delays each response by 200 ms and records the number
  of in-flight `CURRENT` requests, when the endpoint is requested, then the response is `200` with
  20 `weather` items in stored order, the fake receives exactly 20 `CURRENT` requests, the maximum
  number in flight at any moment is ≥ 2 and ≤ 4, and the request completes in less than 20 × 200 ms
  (= 4 s).
- **AC-17 (overall deadline)**: Given `weather.favourites.weather.timeout=1s`, a provider read
  timeout of 5 s for this context, 3 favourites, and a `FakeOpenMeteo` `CURRENT` responder that
  delays the Tokyo response by 3 s and answers the others at once, when the endpoint is requested,
  then the response is `200` within 2 s of the request being sent. London and Paris have `weather`,
  and Tokyo has `error` `UPSTREAM_UNAVAILABLE`. No upstream call is started after the deadline
  has passed. The late Tokyo result, if it arrives later, does not change the response that was
  already sent (it may still be written to the cache).
- **AC-18 (configuration)**: `weather.favourites.weather.max-concurrency` is bound from
  `${WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY:4}` (valid 1..20) and
  `weather.favourites.weather.timeout` from `${WEATHER_FAVOURITES_WEATHER_TIMEOUT:5s}` (valid
  1s..30s) in `application.yml`. Both variables are documented (commented out) in `.env.example`
  and passed to the `app` service in `compose.yaml` as `${VAR:-<default>}`. When max-concurrency
  is `0`, `21` or `abc`, or the timeout is `0s`, `31s` or `abc`, startup fails with a message
  that names the property (unit test, no Docker).

### Contract, routing, logging, regression
- **AC-19 (OpenAPI)**: `openapi-v1.json` documents `GET /api/v1/favourites/weather` (tag
  `favourites`) with the `ClientId` header parameter, the existing `Units` query parameter, a
  `200` response with a new list schema (e.g. `FavouriteWeatherList {items: FavouriteWeather[]}`,
  `maxItems 100`), whose item schema has required `id`, `city`, `location` (`$ref Location`),
  optional `label`, and exactly one of `weather` (`$ref CurrentWeather`, unchanged) or `error`
  (new schema, `code` enum `["UPSTREAM_UNAVAILABLE", "LOCATION_NOT_FOUND"]`, `detail` string),
  plus `400` and `500` responses. `OpenApiDriftIT` and `RealProviderOpenApiDriftIT` pass. A
  conformance IT checks against the schemas: the AC-1 body, the AC-4 empty body, the AC-11
  mixed body and the AC-12 all-error body (`200`), and the AC-3/AC-5 `400` bodies against
  `Problem`. The existing `/api/v1/weather/*` and `/api/v1/favourites*` path entries and the
  `CurrentWeather`, `Location`, `Favourite` and `Problem` schemas are unchanged, except that the
  `/api/v1/favourites/{id}` description may note that `weather` (like `order`) is not an id.
- **AC-20 (routing)**: `GET /api/v1/favourites/weather` is served by the new endpoint, not by
  `GET /api/v1/favourites/{id}` (it never gives `400` field `id`). `POST /api/v1/favourites/weather`
  gives `405 METHOD_NOT_ALLOWED` (Problem Details, `Allow` header containing `GET`). `GET
  /api/v1/favourites/<random uuid>` still gives `404 NOT_FOUND` as today.
- **AC-21 (logging and privacy)**: Given `X-Request-Id: it-faw-21`, `X-Client-Id: cid-SECRET21`
  and a favourite with label `LBL-SECRET21`, when the endpoint is requested (all items succeed),
  then the response carries `X-Request-Id: it-faw-21`, exactly one INFO access-log line with
  `it-faw-21` is written, no WARN line is written, and no captured log line at INFO or above
  contains `cid-SECRET21` or `LBL-SECRET21`. The access-log path is `/api/v1/favourites/weather`
  (no query string is logged).
- **AC-22 (no migration)**: No new Flyway migration is added: `src/main/resources/db/migration`
  still holds exactly `V1__baseline.sql` and `V2__create_favourite_table.sql`, both unchanged
  (`git diff main -- src/main/resources/db/migration/` is empty). `FavouritesSchemaIT` and
  `MigrationFilesTest`, which assert exactly V1 + V2, pass unmodified.
- **AC-23 (no writes)**: The endpoint never changes the `favourite` table: after AC-1, AC-11 and
  AC-12, every row (including `updated_at` and `position`) is identical to its state before the
  request. No database transaction or connection is held while provider calls are in flight
  (ADR 0008).
- **AC-24 (regression)**: All existing unit tests and ITs pass. The responses, status codes and
  headers of `/api/v1/weather/current`, `/api/v1/weather/forecast` and every existing
  `/api/v1/favourites*` operation are unchanged, including the AC-32 quota of
  saved-favourite-locations (create = 1 `GEOCODING`, 0 `CURRENT`).

## Non-functional requirements
- **Upstream cost / quota**: per request, at most one `CURRENT` upstream call per favourite on a
  cold cache, 0 `GEOCODING` and 0 `DAILY` calls (AC-7), and 0 calls when every item is cached
  (AC-8). Worst case per request = `max-per-client` (default 20) calls. No retries (ADR 0006).
  Failures are never cached (AC-13).
- **Latency**: the response is sent within `weather.favourites.weather.timeout` (default 5 s)
  plus the time to load the favourites and serialise the response; the IT bound is timeout + 1 s
  (AC-17). At most `max-concurrency` (default 4) upstream calls are in flight per request (AC-16).
  The existing per-call connect/read timeouts (2 s / 3 s) still apply to each call.
- **Resources**: the worker pool or executor used for the fan-out is bounded (no unbounded thread
  creation per request); the architect chooses the mechanism (e.g. virtual threads plus a
  semaphore) and records it in design.md (ADR if it is a new cross-cutting pattern). The request
  id (MDC) is propagated to the workers (AC-11).
- **Security / privacy**: no new data is exposed; the endpoint returns only the caller's own
  favourites (AC-6). Client ids, labels, cities and coordinates are never logged at INFO or above
  by the new code (AC-11, AC-21), and per-item `error.detail` texts are fixed (no exception
  messages, URLs or class names).
- **Data retention**: no new persistent data, no migration (AC-22). Redis only gains entries of
  the existing `current` kind with the existing TTL (AC-9).
- **Dependencies**: no new dependency.

## Out of scope
- Forecasts for all favourites, or any per-favourite data other than current weather.
- Weather for a single favourite by id (`GET /api/v1/favourites/{id}/weather`); the UI can call
  `/api/v1/weather/current?city=<city>`.
- Re-geocoding or refreshing a favourite's stored location; deduplicating upstream calls when two
  favourites have identical coordinates within one request (A-7).
- Pagination, filtering or selecting a subset of favourites (`?ids=`).
- A configurable per-item retry, circuit breaker, request coalescing/stampede protection, or rate
  limiting of this endpoint.
- HTTP caching headers (`ETag`, `Cache-Control`) and `207 Multi-Status`.
- Streaming or server-sent partial results.
- New top-level `ErrorCode` values.

## Open questions / assumptions
Each item needs a human decision on the PR; if none is given, the assumption stands. Items in
**bold** most need confirming.

- **A-1 (path)**: `GET /api/v1/favourites/weather`, under the favourites resource because it is
  client-scoped. It shares the `/api/v1/favourites/{id}` pattern space like `/order` does: the
  literal GET mapping wins, but `PATCH`/`DELETE /api/v1/favourites/weather` hit `{id}` and give
  `400` field `id` (as `/order` does today, accepted in saved-favourite-locations A-12).
  Alternative: `GET /api/v1/weather/favourites` (but then a `/weather/*` path needs
  `X-Client-Id`, which no other weather path does).
- **A-2 (response shape)**: a flat item `{id, city, label?, location, weather? | error?}` inside
  `{"items": [...]}`, mirroring `FavouriteList`. `weather` is the unchanged `CurrentWeather`
  schema; `location` is repeated at item level so that failed items can still be labelled in the
  UI. `createdAt`/`updatedAt` are not included. Alternative: nest the full `Favourite` object.
- **A-3 (partial failure; human decision)**: upstream failures are reported **per item** with
  `200`, even when every item fails (AC-12), so one bad city never fails the list and the UI has
  one code path. The alternative is `503 UPSTREAM_UNAVAILABLE` when all (or any) items fail. A
  per-item error is not a Problem Details object: it carries only `code` (subset of the existing
  `ErrorCode` names) and a fixed `detail`; the request id is the response header. Unexpected
  exceptions and database failures still give `500` for the whole request (AC-15).
- **A-4 (stored coordinates; human decision)**: weather is looked up by each favourite's stored
  location snapshot, not by re-geocoding `city`. This saves 1 geocoding call per cold favourite
  and makes the result consistent with the saved place. Cost: if Open-Meteo's geocoding for a
  city changes later, the favourite keeps the old coordinates (refresh is out of scope, as in
  saved-favourite-locations). This needs a port method that takes a `Location` (e.g.
  `WeatherProvider#currentAt(Location, Instant)`), with the Open-Meteo adapter reusing its
  existing `current` cache entry keyed by lat/lon, and the stub returning its fixed conditions for
  a known stored location. That is a port change the architect should record in a new ADR (or an
  amendment of ADR 0011). With stored coordinates, `LOCATION_NOT_FOUND` can only arise from the
  stub or a default implementation that falls back to the city (AC-14).
- **A-5 (concurrency and deadline)**: defaults of 4 parallel upstream calls per request and a 5 s
  overall deadline, both configurable (AC-18). With typical upstream latency (~100–300 ms) 20 cold
  favourites take about 5 waves ≈ 0.5–1.5 s; a hung call is cut off by the deadline instead of
  by 5 waves × 3 s read timeouts. Items still waiting or in flight at the deadline are reported as
  `UPSTREAM_UNAVAILABLE`. **Confirm 4 and 5 s**, and whether these should be configurable at all
  (fixed constants would drop AC-18).
- **A-6 (quota)**: a client polling this endpoint every minute with 20 favourites costs at most
  20 calls per `current-ttl` (10 m) window, i.e. ~2,900 calls/day for one client if every window
  is cold, against a ~10,000/day free tier. Rate limiting is out of scope; flag if that is not
  acceptable.
- **A-7 (duplicate coordinates)**: two favourites with identical stored coordinates (e.g.
  `London` and `Londres`) may each make a `CURRENT` call on a cold cache in the same request.
  This is rare given the 20-item cap and is left out of scope.
- **A-8 (units)**: same `units` parameter, validation and messages as `/api/v1/weather/current`
  (AC-3); the units apply to all items. Each item's `weather.units` echoes them; there is no
  top-level `units` property.
- **A-9 (no new ErrorCode)**: the per-item `error.code` reuses the names `UPSTREAM_UNAVAILABLE`
  and `LOCATION_NOT_FOUND`; the `Problem.code` enum and `ErrorCode` are unchanged. Adding a value
  to the item `code` enum later is treated as additive (clients must tolerate unknown codes), which
  the spec description should say.
- **A-10 (names)**: path, property names (`items`, `weather`, `error`, `code`, `detail`), schema
  names (`FavouriteWeather`, `FavouriteWeatherList`, `FavouriteWeatherError`) and configuration
  keys (`weather.favourites.weather.max-concurrency`, `.timeout`, and their env vars) are
  proposals.
- **A-11 (logging)**: the endpoint logs one WARN per request that has at least one failed item
  (counts only), in addition to the adapter's existing per-call WARN lines. A fully successful
  request logs only the access-log line.
- **A-12 (no migration)**: the existing `favourite` table holds everything needed (stored
  location and `position`). If the architect finds a migration is needed after all, `V3__*.sql`
  would require updating `FavouritesSchemaIT` and `MigrationFilesTest`, which currently assert
  exactly V1 + V2, and AC-22 would be revised.
