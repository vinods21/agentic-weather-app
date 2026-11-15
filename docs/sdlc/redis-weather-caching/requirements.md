# Redis caching of weather provider responses: Requirements
Feature slug: redis-weather-caching · Status: draft

## Problem / goal
Since `real-weather-provider`, every `/api/v1/weather/current` and `/api/v1/weather/forecast`
request with the default provider makes 2 upstream calls to Open-Meteo: geocoding, then forecast
(ADR 0006, real-weather-provider A-11). The free tier allows about 10,000 calls/day, so the
service tops out at about 5,000 API requests/day. Under an upstream outage every request also
pays the full timeout. Redis is already wired up (`CacheKeys`, `weather.cache.key-prefix`), but no
feature uses it.

Goal: cache upstream results in Redis so that repeated requests for the same place make fewer
upstream calls or none:
- **geocoding** results (city → location), including "not found", which change rarely;
- **current conditions** per location, which change about every 15 minutes upstream;
- **daily forecasts** per location, start date and day count, which change about hourly.

Cached data is always **metric** (as the adapter returns it). Unit conversion stays in
`WeatherService`, so `units` is never part of a cache key.

**The `/api/v1` contract does not change.** That covers paths, parameters, response shapes,
status codes, the six error codes and `openapi-v1.json`. No new response headers are added.
The cache is an optimisation: when Redis is unavailable or holds unreadable data, the service
behaves as it does today without a cache (fail open).

"Upstream call" below means a request received by the fake upstream (`support.FakeOpenMeteo`),
counted per route (`GEOCODING`, `CURRENT`, `DAILY`) with `FakeOpenMeteo.requests(route)`.
Unless an AC says otherwise: the real provider (`open-meteo`) is selected and pointed at the
fake, caching is enabled, Redis is the compose Redis under the per-run prefix `it:<id>`, every
test starts with no keys under that prefix and with `FakeOpenMeteo.reset()`, and the fake's
defaults apply (London geocoding, AC-6 current fixture, generated daily fixture; see
`docs/sdlc/real-weather-provider/`). `<prefix>` is the resolved `weather.cache.key-prefix`.

## User stories
- As an operator, I want repeated requests for the same city to be served from Redis, so that
  the service stays within the upstream's daily quota.
- As a UI user, I want cached responses to look exactly like fresh ones, so that the UI needs no
  change.
- As an operator, I want to set each cache TTL (and switch caching off) through environment
  variables, so that I can trade freshness against quota without a rebuild.
- As an operator, I want a Redis outage or a bad cache entry to degrade to uncached behaviour
  with a WARN line, never to an error response, so that Redis is not a new single point of failure.
- As a developer, I want the stub provider to stay cache-free, so that offline development and
  stub-based tests don't depend on Redis state.

## Acceptance criteria

### Configuration
- **AC-1**: Given `application.yml`, then the following properties are each bound from an env-var
  placeholder with the default shown. Each variable is documented (commented out) in
  `.env.example` and passed through to the `app` service in `compose.yaml` as `${VAR:-default}`.
  The architect may rename them (A-12) and records the final names in design.md.
  | Property (proposed) | Env var | Default |
  |---|---|---|
  | `weather.cache.enabled` | `WEATHER_CACHE_ENABLED` | `true` |
  | `weather.cache.geocoding-ttl` | `WEATHER_CACHE_GEOCODING_TTL` | `7d` |
  | `weather.cache.not-found-ttl` | `WEATHER_CACHE_NOT_FOUND_TTL` | `1h` |
  | `weather.cache.current-ttl` | `WEATHER_CACHE_CURRENT_TTL` | `10m` |
  | `weather.cache.forecast-ttl` | `WEATHER_CACHE_FORECAST_TTL` | `1h` |
  | `spring.data.redis.timeout` | `REDIS_TIMEOUT` | `500ms` |
  | `spring.data.redis.connect-timeout` | `REDIS_CONNECT_TIMEOUT` | `500ms` |
- **AC-2**: Given any of the four TTL properties is set to `0s`, `-5m` or `abc`, when the context
  starts, then startup fails, and the failure message names the offending property. (Unit test,
  no Docker.)
- **AC-3**: Given `WEATHER_PROVIDER=stub` (the `it` profile default) and caching enabled, when
  `GET /api/v1/weather/current?city=London` and `GET /api/v1/weather/forecast?city=London&days=3`
  are each called twice, and `current?city=Atlantis` once, then the responses are exactly as
  today (stub values, 404 for Atlantis), and afterwards no key exists under `<prefix>:*`. The
  stub is never cached (A-7).
- **AC-4**: Given `weather.cache.enabled=false` and the real provider, when `current?city=London`
  is called twice, then both responses are 200 with the real-weather-provider AC-6 values, the
  fake receives 2 `GEOCODING` and 2 `CURRENT` calls, and no key exists under `<prefix>:*`.

### Cache hits and key normalisation
- **AC-5**: Given an empty cache, when `GET /api/v1/weather/current?city=London` is called twice,
  then both responses are 200, and the two bodies are equal (including `observedAt`, see AC-24).
  The fake receives exactly 1 `GEOCODING` and 1 `CURRENT` call in total.
- **AC-6**: Given an empty cache, when `current?city=London&units=metric` is called and then
  `current?city=London&units=imperial`, then the second response has `units "imperial"`,
  `temperature 52.2` and `windSpeed 9.2` (within 0.1, as in real-weather-provider AC-7), and the
  fake receives no further call after the first request. No key under `<prefix>:*` contains
  `metric` or `imperial`.
- **AC-7**: Given an empty cache, when current weather is requested for `London`, then `london`,
  then `LONDON`, then `%20%20London%20` (stripped by the API layer), then the fake receives
  exactly 1 `GEOCODING` call, and every response has `location.name "London"`. In a second case,
  with the fake's geocoding stubbed to return one location for any name, `New York` followed by
  `new   york` (internal whitespace runs collapse to one space for the key), and `São Paulo` in
  NFC followed by `São Paulo` in NFD (`a` + U+0303), each produce exactly 1 `GEOCODING` call per
  pair (A-3).
- **AC-8**: Given `current?city=London` has been served, when
  `forecast?city=London&days=3` is called, then the response is 200 and the fake receives 0
  further `GEOCODING` calls and exactly 1 `DAILY` call. The geocoding entry is shared by both
  endpoints.
- **AC-9**: Given the service clock fixed at `2026-11-15T10:00:00Z` and an empty cache, when
  `forecast?city=London&days=3` is called twice, then the fake receives 1 `DAILY` call. When
  `days=5` is then called, the fake receives 1 more `DAILY` call (with `end_date=2026-11-19`) and
  no `GEOCODING` call. When the clock moves to `2026-11-16T00:00:01Z` and `days=3` is called
  again, the fake receives 1 more `DAILY` call with `start_date=2026-11-16`, and the response's
  first day is `2026-11-16`. A forecast entry is keyed by location, UTC start date and day count
  (A-5).
- **AC-10**: Given the fake's geocoding stubbed to return a distinct location per `name`, when
  current weather is requested for each of `a:b`, `a_b`, `a*`, `a?`, `a[1]` and `a b`, then the
  fake receives 6 `GEOCODING` calls (no two cities share an entry), and every key under
  `<prefix>:*` matches `^<prefix>:[A-Za-z0-9:._-]+$` (raw user input never forms key structure
  or glob characters; A-4). All keys are built through `CacheKeys`.
- **AC-11**: Given a geocoding result with a non-ASCII name and negative coordinates (fake stubbed:
  `name "São Paulo"`, `country_code "BR"`, `latitude -23.5475`, `longitude -46.63611`), when
  current weather for `São Paulo` is requested twice, then the second body equals the first,
  including `location.name "São Paulo"`, the exact coordinates and `condition.description`.

### TTLs and expiry
- **AC-12**: Given the default TTLs, after `current?city=London`, `forecast?city=London&days=3`
  and `current?city=Atlantis` have been served, then every key under `<prefix>:*` has a TTL > 0
  (none is persistent): the geocoding entry for London has a TTL in (6d 23h, 7d], the
  `Atlantis` not-found entry in (59m, 1h], the current entry in (9m, 10m] and the forecast entry
  in (59m, 1h].
- **AC-13**: Given `weather.cache.current-ttl=1s`, when `current?city=London` is called, then
  again after 1.5 s, then the fake receives 2 `CURRENT` calls and 1 `GEOCODING` call (geocoding
  is still cached). The same holds for `forecast-ttl=1s` and `DAILY`.

### Not found (negative caching)
- **AC-14**: Given the fake's geocoding returns no results for `Atlantis`, when
  `current?city=Atlantis` is called, and then `current?city=ATLANTIS` and
  `forecast?city=atlantis&days=3`, then all three responses are 404 `LOCATION_NOT_FOUND`, each
  `detail` contains the city **as sent in that request** (`Atlantis`, `ATLANTIS`, `atlantis`),
  and the fake receives exactly 1 `GEOCODING` call and no `CURRENT`/`DAILY` call. The same holds
  when the first result has no `country_code` (real-weather-provider AC-14).
- **AC-15**: Given `weather.cache.not-found-ttl=1s` and a cached not-found entry for `Atlantis`,
  when the fake is then stubbed to return a valid location for `Atlantis` and the request is
  repeated after 1.5 s, then the response is 200 and the fake receives a second `GEOCODING` call.

### Upstream failures are never cached
- **AC-16**: Given the fake's geocoding answers HTTP 500 (and, in further cases, waits past the
  read timeout, or returns invalid JSON), when `current?city=London` is called, then the response
  is 503 `UPSTREAM_UNAVAILABLE` and no key exists under `<prefix>:*`. When the fake is reset to
  its defaults and the request is repeated, the response is 200 and the fake has received a
  second `GEOCODING` call.
- **AC-17**: Given geocoding succeeds but the fake's `CURRENT` route answers 500 (in a second case,
  `DAILY` answers 500 or a short `days` array as in real-weather-provider AC-19), when the
  corresponding endpoint is called, then the response is 503, the geocoding entry for London
  exists, and no current/forecast entry exists. When the fake is reset and the request repeated,
  the response is 200 with 0 further `GEOCODING` calls and exactly 1 further `CURRENT` (or
  `DAILY`) call.

### Redis unavailable (fail open)
How to simulate these without stopping containers is a design decision (A-10).
- **AC-18**: Given caching enabled and Redis unreachable (the cache's Redis connection points at a
  local port with nothing listening), when the application context starts, then startup
  succeeds and `GET /actuator/health/readiness` returns 200 `UP`.
- **AC-19**: Given Redis unreachable as in AC-18 and request header `X-Request-Id: it-rwc-19`, when
  `current?city=London`, `forecast?city=London&days=3`, `current?city=Atlantis` and (with the
  fake's geocoding answering 500) `current?city=London` are called, then the responses are 200,
  200, 404 `LOCATION_NOT_FOUND` and 503 `UPSTREAM_UNAVAILABLE` respectively, with the same bodies
  as without a cache. No response is 500. Each successful request makes the full set of upstream
  calls (1 `GEOCODING` + 1 `CURRENT`/`DAILY`), and each request completes in under 2 s.
- **AC-20**: For AC-19, the captured log contains at least one and at most 4 WARN lines per
  request that start with `Cache operation failed` and contain the request id, the operation
  (`get` or `put`), the cache (`geocoding`, `current` or `forecast`) and the exception class
  name. No line at INFO or above written by the cache contains the city, a Redis key or a cached
  value. No stack trace is logged at WARN or above for these failures.
- **AC-21**: Given Redis accepts TCP connections but never answers (e.g. an in-process
  `ServerSocket` that accepts and stays silent), with `spring.data.redis.timeout=250ms` and
  `connect-timeout=250ms`, when `current?city=London` is called, then the response is 200 with the
  real-weather-provider AC-6 values and arrives less than 3 s after the request was sent.
- **AC-22**: Given Redis is reachable but a cache read fails (in a second case, a cache write
  fails) with a Redis/data-access exception, when `current?city=London` is called, then the
  response is 200 with the AC-6 values and a `Cache operation failed` WARN line is written. A
  failed read is treated as a miss (the fake receives the upstream calls), and a failed write
  does not change the response.

### Unreadable cache entries
- **AC-23**: Given the geocoding entry for London (and, in separate cases, the current entry and
  the forecast entry) is pre-seeded under its real key with each of: `not-json`, `{}`, a JSON
  value of a different format version (e.g. the valid shape with `"v":0`, or whatever version
  marker the design uses), and a Redis hash instead of a string (`WRONGTYPE`), when the endpoint
  is called, then the response is 200 with the correct values, the fake receives the upstream
  call(s) for that entry, a WARN line `Cache operation failed ... reason=unreadable` (no value
  content) is written, and afterwards the key holds a readable entry with a TTL > 0. A
  subsequent request is served from the cache.

### observedAt for cached current weather
- **AC-24**: Given a mutable test clock at `2026-11-15T10:00:00Z` and an empty cache, when
  `current?city=London` is called, then `observedAt` is `2026-11-15T10:00:00Z`. When the clock
  moves to `2026-11-15T10:05:00Z` and `current?city=London&units=imperial` is called (cache hit),
  then its `observedAt` is still `2026-11-15T10:00:00Z`: `observedAt` is the service-clock time at
  which the conditions were fetched from the upstream, not the response time (A-8).

### Contract regression
- **AC-25**: Given caching enabled, when the existing OpenAPI drift and conformance ITs run with
  both the stub (`ContractConformanceIT`, `OpenApiDriftIT`) and the real provider
  (`RealProviderContractConformanceIT`, `RealProviderOpenApiDriftIT`), then they pass,
  `openapi-v1.json` is byte-for-byte unchanged (`git diff main -- src/main/resources/openapi/` is
  empty), and no `/api/v1/weather/*` response carries a header that it did not carry before this
  feature. All existing ITs and unit tests pass; an existing real-provider IT may only change its
  setup (e.g. clearing keys between requests) if it relied on repeated upstream calls, never its
  assertions.

## Non-functional requirements
- **Quota**: within the TTLs, a repeated request for a known city makes 0 upstream calls (AC-5), a
  first request for a city already geocoded makes 1 (AC-8), and an unknown city costs 1 call per
  not-found TTL (AC-14). With caching disabled the behaviour is exactly today's (AC-4).
- **Latency with Redis down**: each Redis command and connection attempt is bounded by
  `REDIS_TIMEOUT` / `REDIS_CONNECT_TIMEOUT` (default 500 ms each; Lettuce's 60 s default must
  not apply). A request makes at most 4 cache operations (get/put for geocoding and for
  current/forecast), so an unresponsive Redis adds at most about 4 x 500 ms with the defaults on
  top of the upstream time (AC-19, AC-21). The architect may cut this further (e.g. skip the
  remaining cache operations of a request after the first failure).
- **Availability**: Redis is optional at runtime. No 5xx and no startup failure is ever caused by
  the cache (AC-18..AC-23). Readiness does not depend on Redis (AC-18). The existing
  `/actuator/health` aggregate, which includes Spring Boot's Redis indicator, is unchanged.
- **Security / privacy**: city names appear in Redis only in encoded form inside keys (AC-10) and
  as upstream-canonical names in values. Cache log lines never contain the city, the key or a
  value (AC-20), consistent with ADR 0006's sanitised logging. Values are deserialised only into
  the app's own types (no polymorphic/default typing, no Java serialisation), so a tampered entry
  can at worst be a miss (AC-23).
- **Observability**: one `Cache operation failed` WARN line per failed cache operation (AC-20,
  AC-22, AC-23). Hits and misses are not logged at INFO or above (DEBUG is allowed).
- **Data retention**: every key carries a TTL (CLAUDE.md, AC-12). Longest-lived data is a
  geocoding entry (7 days by default). Nothing is written to PostgreSQL; no Flyway migration.
- **Dependencies**: no new production dependency; use `spring-boot-starter-data-redis` and Jackson,
  which are already on the classpath. Integration tests use the compose Redis and
  `FakeOpenMeteo`, never Testcontainers (ADR 0001).

## Out of scope
- Caching the stub provider (A-7).
- Stampede protection / request coalescing: concurrent misses for the same key may each call
  the upstream (A-11).
- Fetching 7 forecast days once and slicing per `days` value (it would change the upstream
  request verified by real-weather-provider AC-8; see A-5).
- Serving stale entries when the upstream fails ("stale-if-error"), circuit breakers, retries.
- Cache hit/miss metrics, an actuator cache endpoint, or `X-Cache`-style response headers.
- Admin endpoints or tooling to purge the cache, and cache warm-up.
- A local in-memory (L1) cache in front of Redis.
- Redis memory sizing, `maxmemory`/eviction policy, Redis auth/TLS configuration.
- Any change to `/api/v1`, `openapi-v1.json`, `WeatherProvider`'s failure contract or upstream
  request formats.

## Open questions / assumptions
Each item needs a human decision on the PR. If none is given, the assumption stands.

- **A-1 (TTL defaults)**: geocoding 7 days, not-found 1 hour, current 10 minutes, forecast 1 hour.
  Rationale: city coordinates practically never change; Open-Meteo's current values update every
  15 minutes (`interval: 900`); its forecast models update roughly hourly. **Confirm** the
  values, especially the staleness tolerance for current weather (up to 10 minutes old, plus the
  upstream's own 15-minute interval).
- **A-2 (what is cached)**: three entry kinds: geocoding (normalised city → location, or
  not-found), current conditions (per location), daily forecast (per location + start date +
  days). Values are the adapter's metric domain values after mapping/validation, not raw upstream
  JSON, so a cached entry never needs re-validation. The architect decides whether caching lives
  in the Open-Meteo adapter or a decorator, as long as geocoding is cached separately from
  forecast data (AC-8).
- **A-3 (city key normalisation)**: key city = API-stripped city, Unicode NFC, internal whitespace
  runs collapsed to one space, lower-cased with `Locale.ROOT`. The upstream geocoding call still
  receives the city as sent by the first requester (real-weather-provider AC-9 unchanged). This
  assumes Open-Meteo geocoding is insensitive to case, whitespace runs and Unicode normal form.
  If it is not, two spellings could share a result the second spelling would not have produced.
  Accents are **not** stripped (`Sao Paulo` and `São Paulo` are separate entries).
- **A-4 (key encoding)**: the city part of a key is encoded (e.g. URL-safe Base64 of UTF-8, or a
  SHA-256 hex digest) so keys use only `[A-Za-z0-9:._-]` and different cities never collide
  (AC-10). The architect picks the encoding. Location parts use the same plain-string coordinates
  the adapter sends upstream. Keys include a format-version segment or the value carries a
  version marker, so a future format change reads old entries as misses (AC-23).
- **A-5 (forecast key includes `days`)**: `days=3` and `days=5` are separate entries and separate
  upstream calls, because the upstream request (`end_date`) differs. Fetching 7 days once and
  slicing would save quota but changes the upstream request; deferred (Out of scope). The UTC
  start date in the key makes forecasts roll over at 00:00 UTC regardless of TTL.
- **A-6 (negative caching)**: geocoding "not found" (no results, or a first result without a
  usable `country_code`) is cached for `not-found-ttl` (1 h). It protects the quota from typos
  and probing; the cost is that a newly added place stays "not found" for up to 1 hour. Only
  `LocationNotFoundException` is cached negatively; `WeatherProviderUnavailableException` (any
  503 cause) is never cached (AC-16, AC-17).
- **A-7 (stub not cached)**: the stub makes no upstream calls, so caching it saves nothing and
  would make offline dev and stub-based ITs (e.g. `ProviderFailureIT`'s `@MockitoSpyBean`)
  depend on Redis state.
- **A-8 (observedAt semantics)**: for cached current weather, `observedAt` is the service-clock
  time when the conditions were fetched from the upstream (stored with the entry), not the time
  of the response. This is a semantic change within the unchanged contract (still ISO-8601 UTC
  `Z`, still from the service clock, not the upstream's `current.time`). The alternative, keep
  `observedAt` = response time, would claim data up to 10 minutes old was observed "now".
  **Confirm.** If rejected, AC-24 changes to "observedAt equals the clock at response time" and
  AC-5's "bodies equal" excludes `observedAt`.
- **A-9 (fail open)**: any exception from a cache read, write or deserialisation is caught,
  logged as one WARN and treated as a miss / skipped write. Programming errors in the
  cache code that are not Redis/serialisation failures are still bugs (500); the architect
  defines the exact catch set.
- **A-10 (how ITs simulate Redis failures; for the architect)**: tests must not stop containers.
  Options:
  (a) a separate Spring context whose cache Redis connection points at a closed local port (a
      port from `new ServerSocket(0)` that is then closed) for AC-18/AC-19, and at an in-process
      `ServerSocket` that accepts but never replies for AC-21;
  (b) `@MockitoSpyBean` on the Redis template / cache component, throwing
      `RedisConnectionFailureException` (or similar) on get or put, for AC-22 without touching
      the connection.
  Caveat for (a): `IntegrationTestBase`'s `@AfterEach` cleanup uses the context's
  `StringRedisTemplate`, which would also point at the dead port and fail. Either the cache uses
  its own connection factory/properties (so only the cache is redirected), or these tests use a
  base class without that cleanup (they create no keys). Changing `spring.data.redis.port` also
  affects `InfrastructureIT`-style checks, so it must stay confined to those test classes.
- **A-11 (no stampede protection)**: concurrent cache misses for the same key may each call the
  upstream and each write the entry (last write wins). Acceptable at current traffic.
- **A-12 (names)**: env-var and property names in AC-1 are proposals. `REDIS_TIMEOUT` /
  `REDIS_CONNECT_TIMEOUT` set Spring's global Redis client timeouts, which only the cache uses
  today. The architect may scope them to the cache instead.
- **A-13 (it profile)**: caching stays enabled in the `it` profile with the default TTLs. Because
  `build.gradle.kts` exports `.env`/`.env.example` into ITs, the new variables stay commented out
  in `.env.example`, and ITs that depend on a TTL (AC-12, AC-13, AC-15) set it explicitly (AC-12 sets the default
  values explicitly, so a locally exported variable can't change the outcome).
  Per-test cleanup of `it:<id>:*` keys by `IntegrationTestBase` keeps tests independent.
