# Forecast for all favourites: Requirements
Feature slug: forecast-for-all-favourites · Status: draft

## Problem / goal
`GET /api/v1/favourites/weather` (weather-for-all-favourites, PR #7) returns the **current**
weather for every saved favourite of a client in one request. For a multi-day view the UI still
has to call `GET /api/v1/weather/forecast?city=<favourite.city>&days=<n>` once per favourite, which
means up to 20 round trips. With the Open-Meteo adapter each of those calls also re-geocodes the
city unless the geocoding entry is warm. The weather-for-all-favourites requirements listed
"forecasts for all favourites" as out of scope. This feature adds it.

Goal: one read-only endpoint that returns a daily forecast for every favourite of the calling
client, in the client's stored order. It mirrors `/api/v1/favourites/weather` closely:
- it reuses the existing `Forecast` schema per item;
- it looks the forecast up by the **stored coordinates**, with no geocoding (the forecast
  counterpart of ADR 0012);
- it fans cold lookups out through the existing `concurrent.BoundedFanOut` under the existing
  concurrency limit and time budget (ADR 0013);
- it reports expected upstream failures **per item** inside a `200` (ADR 0014).

`days` and `units` behave exactly as on `/api/v1/weather/forecast`. The feature follows ADR 0002
(spec-first OpenAPI with drift and conformance tests), ADR 0003 (Problem Details, `X-Request-Id`,
additive-only v1), ADR 0007 (cache, fail open), ADR 0008 (no transaction across provider calls)
and ADR 0009 (`X-Client-Id`).

Unless an AC says otherwise:
- the `it` profile applies, which means the stub provider (London, Paris, New York, Tokyo,
  Sydney, 7 fixed days each). Favourites are created through `POST /api/v1/favourites` in the
  test, so their stored locations are the stub's locations.
- every request carries `X-Client-Id: <unique value per test>`. Tests delete the favourite rows
  they create in `@AfterEach`. The database is the compose Postgres in schema `it_<id>`, and Redis
  is the compose Redis under prefix `it:<id>`. Testcontainers is never used, and no test starts or
  stops a container (ADR 0001).
- "the provider is not called" means zero interactions on a `@MockitoSpyBean WeatherProvider`.
- "real provider" ACs use the Open-Meteo adapter pointed at the in-process `support.FakeOpenMeteo`
  (e.g. through `CachingIntegrationTestBase`), with caching enabled and the cache empty at the
  start of the test, unless stated otherwise.
- the service clock is the `Clock` bean, fixed at `2026-11-15T10:00:00Z`, so the UTC start date
  is `2026-11-15`.
- "F(city, units, days)" means the `200` body of
  `GET /api/v1/weather/forecast?city=<city>&units=<units>&days=<days>` in the same context at the
  same clock instant. An omitted `units` or `days` means the request leaves that parameter out.

Path, property, configuration and schema names below are proposals (A-11). The architect records
the final names in design.md and `openapi-v1.json`.

## User stories
- As a UI user, I want to see the multi-day forecast for all my saved places at once, so that my
  forecast view loads with one request.
- As a UI user, I want the places in the order I arranged them, with their labels, so that the
  forecast view matches my list.
- As a UI user, I want the other places to still show when the forecast for one place can't be
  fetched, so that one bad city doesn't blank the whole view.
- As a UI user, I want to choose the number of days and the units the same way as on the
  single-city forecast, so that both views behave alike.
- As an operator, I want the endpoint to cost at most one upstream call per favourite on a cold
  cache (and none on a warm one), share cache entries with `/weather/forecast`, and have bounded
  parallelism and response time, so that it doesn't burn the free-tier quota or hang requests.
- As a UI developer, I want the endpoint and its per-item error shape in the OpenAPI document,
  so that I can generate a client.

## Acceptance criteria

### Happy path: `GET /api/v1/favourites/forecast`
- **AC-1**: Given the client created London (label `Home`), Paris (no label) and Tokyo in that
  order, when `GET /api/v1/favourites/forecast` is requested with no query parameters, then the
  response is `200`, `Content-Type: application/json`, with an `X-Request-Id` header and body
  `{"items": [...]}` holding exactly 3 items in the order London, Paris, Tokyo. Each item has:
  - `id`, `city`, `location` equal to the favourite's `id`, `city` and `location` from
    `GET /api/v1/favourites`;
  - `label` `"Home"` for London, and no `label` property for Paris and Tokyo;
  - `forecast` equal to F(city, metric, 5) for that city: the same `location`, `units "metric"`,
    and exactly 5 `days`, the first with `date "2026-11-15"`, consecutive and ascending, with the
    same values and `condition` per day;
  - no `error` property.
- **AC-2 (stored order)**: Given London, Paris and Tokyo with ids `L`, `P`, `T`, and a reorder to
  `["T", "L", "P"]` through `PUT /api/v1/favourites/order`, when the endpoint is requested, then
  the items come back in the order T, L, P. With the real provider and `FakeOpenMeteo` `DAILY`
  responses delayed so that they complete in reverse order (e.g. T 600 ms, L 300 ms, P 0 ms, or
  latch-driven), the order is still T, L, P.
- **AC-3 (days)**: Given London and Paris:
  - `?days=1`, `?days=3` and `?days=7` each give items whose `forecast` equals
    F(city, metric, n), with exactly n `days` starting at `2026-11-15`;
  - no `days` parameter, and `?days=` (empty, treated as absent as on `/weather/forecast`), both
    give 5 days;
  - `?days=+3`, `?days=03` and `?days=%203%20` give 3 days, as on `/weather/forecast`.
- **AC-4 (units)**: Given London and Paris, when `?units=imperial&days=3` is requested, then each
  item's `forecast` equals F(city, imperial, 3) (`units "imperial"`, deg F). `?units=metric` and no
  `units` parameter both give metric.
- **AC-5 (validation parity)**: For each of the following query strings, the response status and
  the `errors` array (field and message of every entry, compared as a set) equal those of
  `/api/v1/weather/forecast?city=London&<same query string>`. In every case the provider is not
  called.
  - `units=IMPERIAL`, `units=kelvin` and `units=`: today `400 VALIDATION_FAILED` with field
    `units` and message `must be one of: metric, imperial`.
  - `days=0` and `days=8`: field `days`, message `must be between 1 and 7`.
  - `days=abc`, `days=3.5`, `days=0x3` and `days=99999999999`: field `days`, message
    `must be an integer between 1 and 7`.
  - `units=kelvin&days=99`: **both** a `units` and a `days` entry in one response (all
    violations reported together).

  Unknown query parameters (e.g. `?page=2` or `?city=Atlantis`) are ignored: the response is the
  same as without them.
- **AC-6 (empty list)**: Given the client has no favourites, when the endpoint is requested (with
  or without valid `days`/`units`), then the response is `200 {"items": []}` and the provider is
  not called.

### Client id (ADR 0009, unchanged rules)
- **AC-7**: When `X-Client-Id` is missing, empty, 65 characters long, or contains a character
  outside `[A-Za-z0-9._-]` (e.g. `a b`, `a/b`), then the response is `400 VALIDATION_FAILED` with
  `errors` containing `{field "X-Client-Id", message "must match ^[A-Za-z0-9._-]{1,64}$"}`, and the
  provider is not called. With an invalid client id **and** `units=kelvin&days=0`, `errors`
  contains at least the `X-Client-Id` entry. A 64-character valid id is accepted. The new handler
  method takes `ClientId` as its first parameter and has no `jakarta.validation.constraints`
  parameter annotations, which a reflection-based signature unit test checks.
- **AC-8 (isolation)**: Given client `A` has London and Paris and client `B` has Tokyo, when `B`
  requests the endpoint, then `B` gets exactly one item (Tokyo). A client with no favourites gets
  `{"items": []}` even though `A` has favourites.

### Lookup by stored coordinates, cache and quota
- **AC-9 (no geocoding, cold cache)**: Given the real provider, a client with 3 favourites, and an
  empty cache, with `FakeOpenMeteo.reset()` called after the setup, when
  `GET /api/v1/favourites/forecast?days=5` is requested, then the response is `200` with 3
  `forecast` items. The 3 favourites are London (created through the API against the fake's
  London geocoding) and 2 rows inserted directly into the `favourite` table with distinct
  coordinates (e.g. Paris 48.8566/2.3522 and Tokyo 35.6762/139.6503). The fake receives exactly 3
  `DAILY` requests, 0 `GEOCODING` requests and 0 `CURRENT` requests. Each `DAILY` request has:
  - `latitude`/`longitude` exactly equal to the stored coordinates (the same
    `BigDecimal.toPlainString()` text the adapter already sends);
  - `start_date=2026-11-15` and `end_date=2026-11-19`.

  Each item's `forecast.location` equals the item's stored `location`.
- **AC-10 (warm cache, shared with `/weather/forecast` in both directions)**:
  - Given AC-9's state right after its request (cache warm, `FakeOpenMeteo.reset()` called), when
    the endpoint is requested again with `days=5` within `WEATHER_CACHE_FORECAST_TTL` (1h), then
    the fake receives 0 requests of any kind and the body equals the first response.
  - After that, `GET /api/v1/weather/forecast?city=London&days=5` makes 0 `DAILY` requests,
    because it reuses the `forecast` entry keyed by the stored coordinates, start date and days.
  - The reverse also holds. Given a fresh client and an empty cache, a `forecast` entry written by
    `/weather/forecast?city=London&days=3` is served to `/favourites/forecast?days=3` for the
    London favourite without a `DAILY` request.
  - Given the same entries, `/favourites/forecast?days=4` makes one `DAILY` request per
    favourite, because `days` is part of the key (ADR 0007 §4).
- **AC-11 (cache keys)**: Given AC-9's request, Redis holds exactly one `CacheKeys`-built
  `forecast` entry per distinct stored coordinate pair, of the form
  `<prefix>:forecast:v1:<lat>:<lon>:2026-11-15:5`. Each has a TTL > 0 and ≤ the configured
  forecast TTL (3,600,000 ms by default). The request writes no `current` or `geocoding` key and
  no new key kind. No key contains the client id, the label or the city text.
- **AC-12 (Redis down, fail open)**: Given the real provider and a test `@Primary CacheStore` on a
  closed local port (ADR 0007 §11), when the endpoint is requested for 3 favourites, then the
  response is `200` with 3 `forecast` items fetched upstream (3 `DAILY` requests) and no `error`
  items. The existing `Cache operation failed …` WARN lines may appear. No other WARN or ERROR is
  logged for the request.
- **AC-13 (one start date per request)**: Every item in one response uses the same UTC start
  date, taken once per request from the service `Clock`. A unit test checks this with a clock
  that moves from `2026-11-15T23:59:59.900Z` to `2026-11-16T00:00:00.100Z` between provider calls:
  every item starts at `2026-11-15`, and every provider call receives `2026-11-15`.

### Partial failure (ADR 0014)
- **AC-14 (one item fails)**: Given London, Paris and Tokyo and the header
  `X-Request-Id: it-ffc-14`, and the upstream failing for Paris only, when the endpoint is
  requested, then:
  - The response is `200` with 3 items in order. London and Tokyo have `forecast` as in AC-1.
  - The Paris item has `id`, `city`, `location` (and `label` if set), no `forecast`, and
    `error {code "UPSTREAM_UNAVAILABLE", detail "The weather provider is temporarily unavailable."}`.
  - Exactly one WARN line is logged by the endpoint for the request. It contains `it-ffc-14`, the
    number of failed items `1` and the total `3`. It contains no city, label, client id or
    coordinates. Its text differs from the `/favourites/weather` WARN line, so the two endpoints
    can be told apart in the logs.
  - On the real path, the adapter's existing `Upstream call failed …` WARN line for the Paris
    `DAILY` call also contains `it-ffc-14`, because the MDC reaches the worker.

  How the failure is injected:
  - stub path: the provider spy throws `WeatherProviderUnavailableException` for Paris's
    location-based forecast lookup;
  - real path: `FakeOpenMeteo` answers `500` to the `DAILY` request whose `latitude` is Paris's.
- **AC-15 (all items fail)**: Given 3 favourites and the upstream failing for every lookup, when
  the endpoint is requested, then the response is still `200` with 3 items, each with `error`
  `UPSTREAM_UNAVAILABLE` and no `forecast`. It is not `503` (A-4). After the failure is removed
  (spy reset or fake reset), the same request gives 3 `forecast` items.
- **AC-16 (failures are not cached)**: Given AC-14's real-provider run, Redis holds no `forecast`
  key for Paris's coordinates. When the endpoint is requested again with the fake back to
  defaults, then Paris now has `forecast`, and the fake receives exactly 1 `DAILY` request (for
  Paris; London and Tokyo come from the cache).
- **AC-17 (not found per item)**: Given a favourite whose lookup throws `LocationNotFoundException`
  (the provider spy throws it for that favourite, or, with the stub, a row inserted directly at
  coordinates the stub doesn't know, e.g. 0.0/0.0), when the endpoint is requested, then that
  item has `error {code "LOCATION_NOT_FOUND", detail "No weather data is available for this location."}`
  (the same fixed text as `/favourites/weather`, with no city) and no `forecast`. The other items
  are unaffected and the status is `200`.
- **AC-18 (unexpected failure)**: Given the provider spy throws an `IllegalStateException` for one
  favourite's lookup, when the endpoint is requested, then the response is `500 INTERNAL_ERROR`
  through the existing handler (Problem Details, no stack trace or exception message in the
  body). Bugs are not masked as per-item errors. Given a database failure while loading the
  favourites, the response is also `500 INTERNAL_ERROR` and the provider is not called.

### Concurrency and time budget (ADR 0013, shared settings, A-6)
- **AC-19 (bounded parallelism)**: Given:
  - `weather.favourites.weather.max-concurrency=4` and the real provider;
  - 20 favourites with distinct coordinates (inserted directly) and an empty cache;
  - a `FakeOpenMeteo` `DAILY` responder that delays each response by 200 ms and records the
    number of in-flight `DAILY` requests,

  when `?days=7` is requested, then:
  - the response is `200` with 20 `forecast` items (7 days each) in stored order;
  - the fake receives exactly 20 `DAILY` requests;
  - the maximum number in flight at any moment is ≥ 2 and ≤ 4 (the lower bound is proven with a
    latch, not with timing);
  - the request completes in less than 20 × 200 ms (= 4 s), measured after a warm-up request.
- **AC-20 (overall deadline)**: Given `weather.favourites.weather.timeout=1s`, an effective
  provider read timeout of 5 s for this context (checked by a guard assertion, see the
  weather-for-all-favourites test report), 3 favourites, and a `FakeOpenMeteo` `DAILY` responder
  that delays the Tokyo response by 3 s and answers the others at once, when the endpoint is
  requested, then:
  - the response is `200` within 2 s of the request being sent;
  - London and Paris have `forecast`, and Tokyo has `error` `UPSTREAM_UNAVAILABLE`;
  - no upstream call is started after the deadline (e.g. with 6 favourites, 4 workers and every
    call blocked, only 4 `DAILY` requests ever reach the fake);
  - the late Tokyo result does not change the response that was already sent (it may still be
    written to the cache).
- **AC-21 (shared configuration)**: The endpoint uses the existing
  `weather.favourites.weather.max-concurrency` and `weather.favourites.weather.timeout`
  properties (A-6). AC-19 and AC-20 set only those properties. No new configuration property and
  no new environment variable is added:
  - `.env.example` and `compose.yaml` are unchanged
    (`git diff main -- .env.example compose.yaml` is empty);
  - `application.yml` changes in comments only, e.g. noting that the settings also apply to
    `/favourites/forecast`.

  The existing range checks and startup failures (AC-18 of weather-for-all-favourites) are
  unchanged.

### Contract, routing, logging, regression
- **AC-22 (OpenAPI)**: `openapi-v1.json` documents `GET /api/v1/favourites/forecast`:
  - tag `favourites`, with the `ClientId` header parameter, the existing `Units` and `Days` query
    parameters (`$ref`, unchanged) and `RequestId`;
  - a `200` response with a new list schema (e.g. `FavouriteForecastList {items: FavouriteForecast[]}`,
    `maxItems 100`). The item schema has required `id`, `city`, `location` (`$ref Location`),
    optional `label`, and exactly one of `forecast` (`$ref Forecast`, unchanged) or `error`
    (`$ref FavouriteWeatherError`, unchanged, A-3);
  - `400` and `500` responses, and no `404` or `503`.

  `OpenApiDriftIT` and `RealProviderOpenApiDriftIT` pass. A conformance IT checks bodies against
  the schemas:
  - `200` bodies against the new list schema: the AC-1 body, a `days=7` body, the AC-6 empty body,
    the AC-14 mixed body and the AC-15 all-error body, plus an explicit check that each item has
    exactly one of `forecast`/`error`;
  - the AC-5 and AC-7 `400` bodies, and the AC-18 `500` body, against `Problem`.

  The existing path entries and the `Forecast`, `ForecastDay`, `Location`, `Favourite`,
  `FavouriteWeather*` and `Problem` schemas are unchanged. The one exception is the `FavouriteId`
  parameter description, which may note that `forecast` (like `order` and `weather`) is not an id.
- **AC-23 (routing)**:
  - `GET /api/v1/favourites/forecast` is served by the new endpoint, not by
    `GET /api/v1/favourites/{id}`: it never gives `400` field `id` or `404`.
  - `POST` and `PUT /api/v1/favourites/forecast` give `405 METHOD_NOT_ALLOWED` (Problem Details,
    `Allow` header containing `GET`).
  - `PATCH` and `DELETE /api/v1/favourites/forecast` hit `{id}` and give `400` field `id`, as
    `/order` and `/weather` do today (A-1).
  - `GET /api/v1/favourites/weather`, `GET /api/v1/favourites/order` and
    `GET /api/v1/favourites/<random uuid>` behave as today (`200`, `400` field `id`, `404`).
- **AC-24 (logging and privacy)**: Given `X-Request-Id: it-ffc-24`, `X-Client-Id: cid-SECRET24`
  and a favourite with label `LBL-SECRET24`, when the endpoint is requested and all items
  succeed, then:
  - the response carries `X-Request-Id: it-ffc-24`;
  - exactly one INFO access-log line with `it-ffc-24` is written, and its path is
    `/api/v1/favourites/forecast` (no query string is logged);
  - no WARN line is written;
  - no captured log line at INFO or above contains `cid-SECRET24` or `LBL-SECRET24`.
- **AC-25 (no migration)**: No new Flyway migration is added. `src/main/resources/db/migration`
  still holds exactly `V1__baseline.sql` and `V2__create_favourite_table.sql`, both unchanged
  (`git diff main -- src/main/resources/db/migration/` is empty). `FavouritesSchemaIT` and
  `MigrationFilesTest`, which assert exactly V1 + V2, pass unmodified.
- **AC-26 (no writes, no held transaction)**: The endpoint never changes the `favourite` table.
  After AC-1, AC-14 and AC-15, every row (including `updated_at` and `position`) is identical to
  its state before the request. No database transaction is active and no Hikari connection is
  checked out while provider calls are in flight: during every provider call, 0 active
  connections and no active transaction, as asserted by
  `FavouritesWeatherIT#ac23_noConnectionHeldDuringProviderCalls` for `/favourites/weather`
  (ADR 0008, ADR 0013 §7).
- **AC-27 (regression)**: All existing unit tests and ITs pass, and no existing test file is
  modified. Additive changes to shared test support (e.g. letting `support.InFlightResponder`
  serve `DAILY`) are allowed if every existing caller behaves the same. The responses, status
  codes, headers and log lines of `/api/v1/weather/current`, `/api/v1/weather/forecast`,
  `/api/v1/favourites/weather` and every other `/api/v1/favourites*` operation are unchanged. This
  includes:
  - the upstream call counts of `/weather/forecast` (cold: 1 `GEOCODING` + 1 `DAILY`; warm: 0);
  - the `/favourites/weather` quota (1 `CURRENT` per cold favourite, 0 `DAILY`).

## Non-functional requirements
- **Upstream cost / quota**:
  - Per request: at most one `DAILY` upstream call per favourite on a cold cache, 0 `GEOCODING`
    and 0 `CURRENT` calls (AC-9), and 0 calls when every item is cached for the requested `days`
    (AC-10).
  - Worst case per request = `max-per-client` (default 20) `DAILY` calls. No retries (ADR 0006).
  - Failures are never cached (AC-16).
- **Latency**: the response is sent within `weather.favourites.weather.timeout` (default 5 s)
  plus the time to load the favourites and serialise the response. The IT bound is timeout + 1 s
  (AC-20). At most `max-concurrency` (default 4) upstream calls are in flight per request
  (AC-19). The existing per-call connect and read timeouts (2 s / 3 s) still apply to each call.
- **Resources**: the fan-out goes through the existing `concurrent.BoundedFanOut` (ADR 0013).
  There is no new executor, no `Executor` bean, and no unbounded thread creation. The request id
  (MDC) reaches the workers (AC-14).
- **Security / privacy**:
  - No new data is exposed. The endpoint returns only the caller's own favourites (AC-8), and an
    item exposes only what `GET /api/v1/favourites` and `/weather/forecast` already expose.
  - The new code never logs client ids, labels, cities or coordinates at INFO or above (AC-14,
    AC-24).
  - Per-item `error.detail` texts are fixed (no exception messages, URLs or class names).
- **Data retention**: no new persistent data and no migration (AC-25). Redis only gains entries
  of the existing `forecast` kind with the existing TTL (AC-11).
- **Dependencies**: no new dependency.
- **Test suite / Postgres connections**: the IT suite already peaks at about 86 of Postgres's 100
  connections (weather-for-all-favourites test report). To stay under the limit:
  - stub-path ITs reuse the existing `FavouritesIntegrationTestBase` context with no extra
    configuration, so they add no context;
  - every new IT class with its own Spring context configuration (a different
    `@TestPropertySource`, `@MockitoSpyBean`, `@Import` or initializer) is annotated
    `@DirtiesContext`, or reuses an existing context configuration exactly.

  Two consecutive full `scripts/test-env.sh it` runs pass with no `too many clients` failure. The
  test report records the peak `client backend` count from `pg_stat_activity` during one run.
- **Test infrastructure**: ITs use the compose Postgres/Redis and the stub or `FakeOpenMeteo`,
  never Testcontainers. Timing assertions keep at least 1 s of margin, and concurrency lower bounds
  are latch-based, as in weather-for-all-favourites.

## Out of scope
- A forecast for a single favourite by id (`GET /api/v1/favourites/{id}/forecast`). The UI can call
  `/api/v1/weather/forecast?city=<city>`.
- A combined current + forecast response, or any per-favourite data other than the daily forecast.
- Per-item `days`/`units`, a start date other than today (UTC), or hourly forecasts.
- Separate concurrency/timeout settings for this endpoint (A-6), and renaming the shared
  `weather.favourites.weather.*` properties.
- Re-geocoding or refreshing a favourite's stored location. Deduplicating upstream calls when two
  favourites have identical coordinates within one request (A-8).
- Pagination, filtering or selecting a subset of favourites (`?ids=`).
- Retries, circuit breakers, request coalescing/stampede protection, or rate limiting of this
  endpoint (global upstream limits need their own ADR).
- HTTP caching headers (`ETag`, `Cache-Control`), `207 Multi-Status`, streaming or server-sent
  partial results.
- New top-level `ErrorCode` values or new per-item error codes.
- Raising Postgres `max_connections` or resizing per-context pools (separate chore).

## Open questions / assumptions
Each item needs a human decision on the PR. If none is given, the assumption stands. Items in
**bold** most need confirming.

- **A-1 (path)**: `GET /api/v1/favourites/forecast`, next to `/favourites/weather`. Like `/order`
  and `/weather`, it shares the `/api/v1/favourites/{id}` pattern space: the literal GET mapping
  wins, but `PATCH`/`DELETE /api/v1/favourites/forecast` hit `{id}` and give `400` field `id`
  (accepted for `/order` and `/weather` already).
- **A-2 (response shape)**: a flat item `{id, city, label?, location, forecast? | error?}` inside
  `{"items": [...]}`, mirroring `FavouriteWeather`. `forecast` is the unchanged `Forecast` schema
  (`location`, `units`, `days`). `location` is repeated at item level so that failed items can
  still be labelled. `createdAt`/`updatedAt` are not included. There is no top-level `days`,
  `units` or start date: each `forecast` carries its own `units` and dates.
- **A-3 (error schema reuse)**: the per-item `error` reuses the existing `FavouriteWeatherError`
  schema and the same two codes and fixed details (`UPSTREAM_UNAVAILABLE` →
  `The weather provider is temporarily unavailable.`, `LOCATION_NOT_FOUND` →
  `No weather data is available for this location.`). The schema is referenced, not copied, and
  stays unchanged. Its description ("Why an item has no weather") reads acceptably for a
  forecast. The alternative is a new `FavouriteForecastError` schema with the same content
  (duplication), or renaming to a neutral name (not additive).
- **A-4 (partial failure; human decision)**: ADR 0014 applies unchanged. Expected upstream
  failures are reported per item with `200`, even when every item fails (AC-15). The alternative
  is `503` when all items fail, but that would also be inconsistent with `/favourites/weather`.
- **A-5 (stored coordinates; human decision)**: the forecast is looked up by each favourite's
  stored location snapshot, not by re-geocoding `city`, like ADR 0012 for current weather.
  - This needs a new port method that takes a `Location` (e.g.
    `WeatherProvider#forecastAtLocation(Location, LocalDate startDate, int days)`). Its default
    falls back to `forecast(location.name(), startDate, days)`.
  - The Open-Meteo adapter overrides it with no geocoding, reusing the existing `forecast` cache
    entry (`CacheKeys.forecast(location, startDate, days)`), and makes one `DAILY` call on a miss.
  - The stub overrides it by exact coordinate match (unknown coordinates →
    `LocationNotFoundException`) and returns the city's first `days` fixed days from `startDate`.
  - The returned forecast describes the location that was passed in.
  - The architect records this in a new ADR amending 0012, or as an amendment of ADR 0012 itself.
  - Cost: if Open-Meteo's geocoding for a city changes later, the favourite keeps its old
    coordinates (refresh is out of scope).
- **A-6 (shared fan-out settings; human decision)**: the endpoint reuses the existing
  `weather.favourites.weather.max-concurrency` (4) and `weather.favourites.weather.timeout` (5 s)
  and adds no new settings (AC-21).
  - Reasons: it is the same upstream host, the same fan-out pattern and a similar per-call
    latency. One knob limits per-request upstream pressure for both dashboard endpoints. It also
    adds no new env vars, config tests or compose entries.
  - Cost: the property name says "weather" but now also governs forecasts. The `application.yml`
    comment and the overview should say so (AC-21 allows comment-only changes).
  - Cost: one setting cannot be tuned for one endpoint without affecting the other.
  - Alternative: own `weather.favourites.forecast.{max-concurrency,timeout}` with the same
    defaults and ranges, plus env vars, `.env.example`/`compose.yaml` entries and binder tests.
  - **Confirm sharing.**
- **A-7 (quota)**: the forecast TTL is 1h, so a client polling every minute with 20 favourites
  and one `days` value costs at most 20 `DAILY` calls per hour (~480/day) if every window is
  cold. Different `days` values do not share entries (`days` is in the key), so a UI that mixes
  `days=3` and `days=7` doubles that. Each distinct start date (UTC midnight) also starts cold.
  Against a ~10,000/day free tier this is acceptable for a handful of clients. Rate limiting is
  out of scope.
- **A-8 (duplicate coordinates)**: two favourites with identical stored coordinates may each make
  a `DAILY` call on a cold cache in the same request. This is rare given the 20-item cap and is
  left out of scope (as in weather-for-all-favourites A-7).
- **A-9 (days/units parity)**: `days` and `units` use the exact constraints, messages, defaults
  and parsing of `ForecastQuery` (`@DaysParam`, the `units` `@Pattern`, empty `days` = absent,
  default 5). Ideally this is shared code, not a copy, so the two cannot drift. The architect
  decides how, without changing `/weather/forecast` behaviour. Both apply to all items.
- **A-10 (one start date per request)**: the UTC start date is computed once per request (AC-13),
  so all items cover the same dates even across midnight. `/weather/forecast` already computes it
  once per call.
- **A-11 (names)**: these are proposals:
  - the path;
  - the property names `items`, `forecast`, `error`, `code`, `detail`;
  - the schema names `FavouriteForecastList` and `FavouriteForecast`;
  - the port method name `forecastAtLocation`;
  - the WARN text (e.g. `Favourites forecast partial failure failed=<n> total=<m> timedOut=<k>`).
- **A-12 (controller placement)**: `FavouritesWeatherControllerSignatureTest` asserts exactly 1
  handler on `FavouritesWeatherController`. Because AC-27 forbids modifying existing tests, the
  new handler goes in a new controller (e.g. `FavouritesForecastController`) with its own
  signature test. If the architect prefers one controller for both aggregate endpoints, that
  test changes and AC-27 is relaxed for it explicitly in design.md.
- **A-13 (logging)**: the endpoint logs one WARN per request with at least one failed item
  (counts only), in addition to the adapter's existing per-call WARN lines, following ADR 0014 §7.
  A fully successful request logs only the access-log line.
- **A-14 (no migration)**: the existing `favourite` table holds everything needed. If the
  architect finds a migration is needed after all, `V3__*.sql` would require changing
  `FavouritesSchemaIT` and `MigrationFilesTest`, and AC-25 would be revised. This is not expected.
