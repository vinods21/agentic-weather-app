# Weather for all favourites: Design
Requirements: ./requirements.md · ADRs: 0001, 0002, 0003, 0005, 0006, 0007, 0008, 0009, 0010, 0011, 0012 (new, amends 0011), 0013 (new), 0014 (new, amends 0003)

## Summary
A new read-only endpoint, `GET /api/v1/favourites/weather`, returns the current weather for every
favourite of the calling `X-Client-Id`, in stored order, as `{"items": [...]}`. Each item has the
favourite's `id`, `city`, optional `label` and `location`, plus exactly one of `weather` (the
unchanged `CurrentWeather` schema) or `error {code, detail}`.

The flow is:
1. `FavouritesWeatherController` (a new controller, `ClientId` first) validates `units` with the
   same rule and message as `/weather/current`.
2. `FavouriteWeatherService` loads the favourites with one plain read (no transaction is held
   afterwards) and takes one service-clock instant for the request.
3. It fans the lookups out through `concurrent.BoundedFanOut`: at most `max-concurrency`
   virtual-thread workers per request, an overall deadline on the monotonic clock, MDC copied to
   the workers, and cancellation by interrupt at the deadline (ADR 0013).
4. Each lookup goes through `WeatherService#currentAt(Location, Units, Instant)`, which calls the
   new port method `WeatherProvider#currentAtLocation(Location, Instant)` (ADR 0012). The
   Open-Meteo adapter implements it with the existing `current` cache entry, keyed by the stored
   coordinates, and one `CURRENT` call on a miss. There is no geocoding. The stub matches the
   stored coordinates exactly.
5. Expected provider failures become per-item errors inside a `200`, even when every item fails.
   A bug or a database failure is still a `500` (ADR 0014).

There is no migration, no new dependency, no new Redis key kind and no new top-level `ErrorCode`.
The OpenAPI document stays hand-written (ADR 0002): one new path and three new schemas.

Final names (A-10):

| Item | Name |
|---|---|
| Path | `GET /api/v1/favourites/weather`, operationId `listFavouritesWeather`, tag `favourites` |
| Body properties | `items`; per item `id`, `city`, `label`, `location`, `weather`, `error`; error `code`, `detail` |
| Schemas | `FavouriteWeatherList`, `FavouriteWeather`, `FavouriteWeatherError` |
| Per-item codes | `UPSTREAM_UNAVAILABLE`, `LOCATION_NOT_FOUND` |
| Config | `weather.favourites.weather.max-concurrency` / `WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY` (4, 1..20); `weather.favourites.weather.timeout` / `WEATHER_FAVOURITES_WEATHER_TIMEOUT` (5s, 1s..30s) |
| Port method | `WeatherProvider#currentAtLocation(Location, Instant)` |

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `FavouritesWeatherController#weather`, `FavouriteWeatherService`, `FavouriteWeatherResponse`, `StubWeatherProvider#currentAtLocation` | `produces = application/json`. `X-Request-Id` comes from the existing filter. Items come from `FavouriteService#list` (stored order), mapped 1:1 with `id`, `city`, `location` from the row and `label` `@JsonInclude(NON_NULL)`. `weather` is `CurrentWeatherResponse.from(...)`, the same DTO and conversion as `/weather/current`. `observedAt` is the request's one `Instant.now(clock).truncatedTo(SECONDS)`. The stub returns the same conditions for the stored coordinates as for the city, so `weather` equals W(city, metric). `error` is null, so it is omitted. |
| AC-2 | `BoundedFanOut` (results in input order), `JdbcFavouriteRepository#findAll` (`ORDER BY position, created_at, id`) | Results are written to the slot of their input index and read back by index, so completion order never matters. |
| AC-3 | `FavouritesWeatherQuery` (`@Pattern(regexp = "metric\|imperial", message = "must be one of: metric, imperial")`, default `metric`), `WeatherService` conversion | The same constraint and message as `CurrentWeatherQuery.units`, and the same `BindException` handler, so status and `errors` are identical. Validation runs during argument resolution, before the handler, so the provider is not called. Unknown parameters don't bind to the record and are ignored. |
| AC-4 | `FavouriteWeatherService` | An empty list returns `List.of()` before any clock read, fan-out or provider call. |
| AC-5 | `ClientIdArgumentResolver` (existing), `FavouritesWeatherController` (`ClientId` first) | The resolver throws `InvalidClientIdException` before `@ModelAttribute` binding, so the `X-Client-Id` error is reported and no data is read. New `FavouritesWeatherControllerSignatureTest`. The existing `FavouritesControllerSignatureTest` (`hasSize(6)`) stays unmodified because the handler is in a new controller. |
| AC-6 | `FavouriteService#list` → `findAll(clientId)` (`WHERE client_id = :clientId`) | Existing client scoping. Nothing is shared across clients. |
| AC-7 | `OpenMeteoWeatherProvider#currentAtLocation` | No `geocode` step. On a miss, one `CURRENT` call with `coordinates(location)`, the same `BigDecimal.valueOf(double).toPlainString()` text as today. No `DAILY` call. |
| AC-8 | same, `RedisWeatherCache` `current` entry | `session.current(location)` / `putCurrent(location, …, now)` use `CacheKeys.current(location)`, the same key `/weather/current` uses after geocoding to the same coordinates. A hit returns the stored `fetchedAt` as `observedAt`. |
| AC-9 | same | Only `current` entries are written, with `current-ttl` (`SET … PX`). Keys contain only `current`, `v1`, lat and lon. |
| AC-10 | `RedisWeatherCache` fail-open (unchanged), one session per item | Each item's lookup opens its own session (sessions aren't shared across threads). A Redis failure is a miss plus the existing WARN line, and the item is fetched upstream. The endpoint logs nothing because no item failed. |
| AC-11 | `FavouriteWeatherService#lookup` (catches `WeatherProviderUnavailableException`), `FavouriteWeatherErrorDto`, `BoundedFanOut` MDC propagation | The failed item gets `error {UPSTREAM_UNAVAILABLE, "The weather provider is temporarily unavailable."}` (the same constant as the Problem detail), and the others are unaffected. One WARN on the request thread: `Favourites weather partial failure failed=1 total=3 timedOut=0`. The request id comes from the log pattern's `%X{requestId}`, and the line holds no city, label, client id or coordinates. Workers run with the caller's MDC, so the adapter's `Upstream call failed` line carries `[requestId=it-faw-11]`. |
| AC-12 | same | Every item fails → `200` with 3 error items (ADR 0014 §3). Nothing is cached, so the next request succeeds. |
| AC-13 | `OpenMeteoWeatherProvider` (failures never cached, ADR 0007 §6) | Paris has no `current` entry after the failure, London and Tokyo do. The second request makes exactly 1 `CURRENT` call. |
| AC-14 | `FavouriteWeatherService#lookup` (catches `LocationNotFoundException`), `StubWeatherProvider#currentAtLocation` (unknown coordinates → not found) | `error {LOCATION_NOT_FOUND, "No weather data is available for this location."}` (fixed text, no city). |
| AC-15 | `BoundedFanOut` fail-fast rethrow, `ApiExceptionHandler#handleUnexpected` (existing) | Any other `RuntimeException` escapes the task. The fan-out stops, cancels the other workers and rethrows the original exception on the request thread → `500 INTERNAL_ERROR`. A database failure in `findAll` is thrown before the fan-out → `500`, and the provider is not called. |
| AC-16 | `BoundedFanOut` (k = min(max-concurrency, n) workers), `FavouritesWeatherProperties` | In-flight calls ≤ k by construction. 20 × 200 ms in waves of 4 ≈ 1 s, well under 4 s. |
| AC-17 | `BoundedFanOut` deadline (`System.nanoTime`), `FavouritesWeatherProperties.timeout` | The request thread returns at the deadline. Unfinished slots → `UPSTREAM_UNAVAILABLE` (counted as `timedOut`). Workers check `stopped`, the deadline and their interrupt flag before taking the next item, then are interrupted. The response is built from a snapshot taken before cancelling. |
| AC-18 | `favourites.FavouritesWeatherProperties`, `application.yml`, `.env.example`, `compose.yaml` | A record with range checks in the compact constructor. The message names the property and the env var. Unparseable values fail in the binder, which names the property. |
| AC-19 | `openapi-v1.json` | See [API](#api). The existing paths and the `CurrentWeather`, `Location`, `Favourite` and `Problem` schemas are untouched. Only the `FavouriteId` parameter description gains "weather". |
| AC-20 | `FavouritesWeatherController` `@GetMapping("/weather")` | Spring prefers the literal pattern `/api/v1/favourites/weather` over `/{id}` across controllers. `POST` matches no handler for that path → the existing 405 handler with `Allow` (GET, PATCH, DELETE from the matching patterns). Random UUID → existing `404`. |
| AC-21 | `RequestIdFilter` (unchanged), `FavouriteWeatherService` (no INFO+ logging on success) | One access line, path only. The new code never logs client ids, labels, cities or coordinates at INFO or above (`Favourite#toString` already redacts client id and label, and it is never logged anyway). |
| AC-22 | none | No migration. `FavouritesSchemaIT` and `MigrationFilesTest` are unchanged. |
| AC-23 | `FavouriteWeatherService` | Only `findAll` (a single auto-commit `SELECT`, connection returned right after) runs before the fan-out. No transaction template, no writes, no DB access on workers (ADR 0013 §7). |
| AC-24 | all | Existing behaviour is unchanged. `currentAt(city, now)` is refactored to share the private step with `currentAtLocation` but keeps its calls, keys and session use. |

## Components & packages
New:

| Class | Responsibility |
|---|---|
| `concurrent.BoundedFanOut` (new package `com.example.weather.concurrent`) | `public <T, R> List<Optional<R>> map(List<T> inputs, Function<? super T, ? extends R> task, int maxConcurrency, Duration timeout)`. It implements ADR 0013 §1–6: k workers on the owned executor, `AtomicInteger` next index, `AtomicReferenceArray` results, `AtomicBoolean stopped`, and a `LinkedBlockingQueue` of worker signals (done / failed(Throwable)). The request thread waits with `poll(remaining, NANOSECONDS)`. On exit it sets stopped, takes the snapshot, then calls `cancel(true)` on every worker. A worker failure is rethrown as is (`RuntimeException` or `Error`). A late failure is logged once at ERROR as `Fan-out task failed after its deadline exception=<class>`. The MDC is captured, installed and cleared per worker. Constructors: `BoundedFanOut(ExecutorService)` (tests) and the static `virtualThreads(String namePrefix)`. It implements `AutoCloseable`: `close()` calls `shutdownNow()`. It validates `maxConcurrency ≥ 1` and a positive timeout (`IllegalArgumentException`). |
| `favourites.FavouritesWeatherProperties` | `@ConfigurationProperties("weather.favourites.weather") record FavouritesWeatherProperties(Integer maxConcurrency, Duration timeout)`, found by the existing `@ConfigurationPropertiesScan`. Defaults are 4 and 5s. Ranges are 1..20 and 1s..30s inclusive. Messages: `weather.favourites.weather.max-concurrency must be between 1 and 20 (env WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY)` and `weather.favourites.weather.timeout must be between 1s and 30s (env WEATHER_FAVOURITES_WEATHER_TIMEOUT)`. The value is not echoed. `static defaults()`. It is a separate record so that `FavouritesProperties` and its tests stay unchanged. |
| `favourites.FavouriteWeatherConfiguration` | `@Configuration(proxyBeanMethods = false)` with `@Bean(destroyMethod = "close") BoundedFanOut favouriteWeatherFanOut()` → `BoundedFanOut.virtualThreads("fav-weather-")`. No `ExecutorService` bean (ADR 0013 §3). |
| `favourites.FavouriteWeatherFailure` | `enum { UPSTREAM_UNAVAILABLE, LOCATION_NOT_FOUND }`. The constant names equal the `ErrorCode` names (a unit test checks this). |
| `favourites.FavouriteWeather` | `record FavouriteWeather(Favourite favourite, CurrentWeather weather, FavouriteWeatherFailure failure)`. The compact constructor requires exactly one of `weather`/`failure`. Factories `ok(...)` and `failed(...)`. `toString` doesn't print the client id or label (it delegates to `Favourite#toString`). |
| `favourites.FavouriteWeatherService` | `@Service`. Dependencies: `FavouriteService`, `WeatherService`, `BoundedFanOut`, `FavouritesWeatherProperties`, `Clock`. `List<FavouriteWeather> currentForAll(String clientId, Units units)`: (1) `favourites.list(clientId)`; (2) empty → `List.of()`; (3) `now = Instant.now(clock).truncatedTo(SECONDS)`; (4) `fanOut.map(list, f -> lookup(f, units, now), maxConcurrency, timeout)`; (5) an empty slot → `failed(f, UPSTREAM_UNAVAILABLE)` and `timedOut++`; (6) if failed > 0, one WARN `Favourites weather partial failure failed={} total={} timedOut={}`. `lookup` catches only `WeatherProviderUnavailableException` → `UPSTREAM_UNAVAILABLE` and `LocationNotFoundException` → `LOCATION_NOT_FOUND`. Everything else propagates. It has no `@Transactional` and no `TransactionOperations`. |
| `api.v1.FavouritesWeatherController` | `@RestController @RequestMapping(value = "/api/v1/favourites", produces = APPLICATION_JSON_VALUE)`, one handler `@GetMapping("/weather") FavouriteWeatherListResponse weather(ClientId clientId, @Valid @ModelAttribute FavouritesWeatherQuery query)`. No constraint annotations on parameters (ADR 0010 §2). |
| `api.v1.FavouritesWeatherQuery` | `record FavouritesWeatherQuery(@Pattern(regexp = "metric\|imperial", message = "must be one of: metric, imperial") String units)`. Null → `"metric"`, an empty string is kept (and so invalid), like `CurrentWeatherQuery`. `resolvedUnits()`. |
| `api.v1.dto.FavouriteWeatherListResponse` | `record (List<FavouriteWeatherResponse> items)` with `from(List<FavouriteWeather>)`. |
| `api.v1.dto.FavouriteWeatherResponse` | `record (String id, String city, @JsonInclude(NON_NULL) String label, LocationDto location, @JsonInclude(NON_NULL) CurrentWeatherResponse weather, @JsonInclude(NON_NULL) FavouriteWeatherErrorDto error)` with `from(FavouriteWeather)`. |
| `api.v1.dto.FavouriteWeatherErrorDto` | `record (String code, String detail)`. `from(FavouriteWeatherFailure)`: `code = ErrorCode.valueOf(failure.name()).name()` and a fixed `detail`: `UPSTREAM_UNAVAILABLE` → `ApiExceptionHandler.UPSTREAM_DETAIL`, `LOCATION_NOT_FOUND` → `"No weather data is available for this location."` (constant `LOCATION_NOT_FOUND_DETAIL`). |

Changed:

| Class | Change |
|---|---|
| `provider.WeatherProvider` | + `default ObservedConditions currentAtLocation(Location location, Instant now) { return currentAt(location.name(), now); }` with Javadoc per ADR 0012. |
| `provider.openmeteo.OpenMeteoWeatherProvider` | + override `currentAtLocation`: `currentAt(cache.session(), location, now)`. The new private `currentAt(Session, Location, Instant)` holds today's cache-read / fetch / put body. `currentAt(String, Instant)` becomes `session = cache.session(); return currentAt(session, geocode(session, city), now);` (same behaviour). |
| `provider.stub.StubWeatherProvider` | + override `currentAtLocation`: find the `CityData` whose `current().location()` has `latitude == location.latitude() && longitude == location.longitude()`. Return `new ObservedConditions(data.current(), now)`, otherwise `throw new LocationNotFoundException(location.name())`. |
| `weather.WeatherService` | + `public CurrentWeather currentAt(Location location, Units units, Instant now)` → `provider.currentAtLocation(location, now)` and the same conversion. Extract `private static CurrentWeather convert(ObservedConditions, Units)` and use it from `current(city, units)` too. The behaviour of `current` is unchanged. |
| `api.error.ApiExceptionHandler` | `UPSTREAM_DETAIL` becomes `public static final` (only the visibility changes), so the per-item detail reuses the exact text. |
| `application.yml` | under `weather.favourites`: `weather: { max-concurrency: ${WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY:4}, timeout: ${WEATHER_FAVOURITES_WEATHER_TIMEOUT:5s} }`, with a comment referencing ADR 0013. |
| `.env.example` | `# WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY=4` and `# WEATHER_FAVOURITES_WEATHER_TIMEOUT=5s`, commented out, next to `WEATHER_FAVOURITES_MAX_PER_CLIENT`. |
| `compose.yaml` | `app.environment`: `WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY: ${WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY:-4}` and `WEATHER_FAVOURITES_WEATHER_TIMEOUT: ${WEATHER_FAVOURITES_WEATHER_TIMEOUT:-5s}`. |
| `openapi-v1.json` | see [API](#api). |

Dependency direction: `api.v1` → `favourites` → (`weather`, `concurrent`, `provider` exceptions).
`concurrent` depends only on the JDK and SLF4J. `weather` and `provider` don't depend on
`favourites`.

## API
`GET /api/v1/favourites/weather`

| Input | Rule |
|---|---|
| `X-Client-Id` (header, required) | `^[A-Za-z0-9._-]{1,64}$` (ADR 0009) |
| `units` (query, optional) | `metric` (default) \| `imperial`, case-sensitive, an empty value is invalid |
| `X-Request-Id` (header, optional) | ADR 0003 |
| anything else | ignored |

`200` `application/json`, header `X-Request-Id`:
```json
{
  "items": [
    {
      "id": "0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f",
      "city": "London",
      "label": "Home",
      "location": {"name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278},
      "weather": {
        "location": {"name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278},
        "observedAt": "2026-11-15T10:00:00Z", "units": "metric",
        "temperature": 11.2, "feelsLike": 9.8, "humidity": 81, "windSpeed": 4.1, "windDirection": 230,
        "condition": {"code": "CLOUDY", "description": "Overcast clouds"}
      }
    },
    {
      "id": "6a1e…", "city": "Paris",
      "location": {"name": "Paris", "country": "FR", "latitude": 48.8566, "longitude": 2.3522},
      "error": {"code": "UPSTREAM_UNAVAILABLE", "detail": "The weather provider is temporarily unavailable."}
    }
  ]
}
```
- `items` holds every favourite of the client in stored order (≤ `max-per-client`). It is `[]`
  if the client has none.
- Each item has exactly one of `weather` / `error`. `label` is omitted when not set.
- `error.code` is `UPSTREAM_UNAVAILABLE` (provider failed, or the item didn't finish within the
  request's time budget) or `LOCATION_NOT_FOUND` (detail
  `No weather data is available for this location.`).

Errors (Problem Details, existing handlers):
- `400 VALIDATION_FAILED`: invalid `X-Client-Id` (field `X-Client-Id`) and/or invalid `units`
  (field `units`).
- `405 METHOD_NOT_ALLOWED` for `POST`/`PUT` (`Allow` includes `GET`). `PATCH`/`DELETE` hit `{id}` →
  `400` field `id` (A-1, documented).
- `500 INTERNAL_ERROR`: a database failure or an unexpected exception.
- No `404` and no `503` from this endpoint.

OpenAPI changes (hand-written, ADR 0002):
- New path `/api/v1/favourites/weather` → `get`:
  - `operationId listFavouritesWeather`, `tags ["favourites"]`;
  - summary "Current weather for every favourite of the client";
  - description: stored order, lookup by stored coordinates, per-item errors with `200` even if
    all fail, time budget, at most one upstream call per favourite, unknown query parameters
    ignored, clients must tolerate unknown `error.code` values;
  - parameters `ClientId`, `Units`, `RequestId` (all `$ref`);
  - responses `200` (header `X-Request-Id`, `application/json` `$ref FavouriteWeatherList`),
    `400` `$ref BadRequest`, `500` `$ref InternalError`.
- `components.schemas.FavouriteWeatherList`: `type object`, `required [items]`, `items` array,
  `maxItems 100`, `items $ref FavouriteWeather`.
- `components.schemas.FavouriteWeather`:
  - `type object`, `required [id, city, location]`;
  - `id`, `city`, `label`: the same property definitions as in `Favourite` (copied, including
    the uuid pattern and the length limits);
  - `location`: `$ref Location`; `weather`: `$ref CurrentWeather`; `error`:
    `$ref FavouriteWeatherError`;
  - description "Exactly one of weather or error is present."
- `components.schemas.FavouriteWeatherError`: `type object`, `required [code, detail]`, `code`
  `type string`, `enum ["UPSTREAM_UNAVAILABLE", "LOCATION_NOT_FOUND"]` with a description saying
  the enum may grow and clients must tolerate unknown values, `detail` `type string`.
- `components.parameters.FavouriteId.description`: "note that \"order\" and \"weather\" are not
  ids, so PATCH and DELETE on /api/v1/favourites/order and /api/v1/favourites/weather, and GET on
  /api/v1/favourites/order, are 400."
- Nothing else changes: the info description, the other paths, and the `CurrentWeather`,
  `Location`, `Favourite` and `Problem` schemas stay as they are.

## Data model
- **PostgreSQL**: no change and no migration (AC-22). The endpoint reads `favourite` through the
  existing `findAll(clientId)` (index `favourite_client_position_idx`). It never writes.
- **Redis**: no new key kind. It reads and writes only the existing
  `CacheKeys.current(location)` = `<prefix>:current:v1:<lat>:<lon>` with
  `weather.cache.current-ttl` (default 10m, `SET … PX`). Keys never contain the client id,
  label or city text. Values are the existing versioned `CurrentEntry` JSON.
- **Configuration**: see the final names table.

## External integrations
- Open-Meteo forecast API, `CURRENT` request only (`/v1/forecast?latitude=…&longitude=…&current=…`),
  through the existing adapter call path. The existing per-call connect timeout (2 s) and read
  timeout (3 s), no retries, no redirects, 1 MiB cap and sanitised WARN line all apply unchanged
  (ADR 0006).
- Per request: at most `n` `CURRENT` calls (n = number of favourites), 0 on a warm cache. At most
  `max-concurrency` in flight. The overall deadline is `timeout`.
- Failure behaviour per item:
  - non-2xx, I/O, timeout or malformed body → `WeatherProviderUnavailableException` → item
    `UPSTREAM_UNAVAILABLE`, nothing cached;
  - deadline reached first → item `UPSTREAM_UNAVAILABLE`, and the worker is interrupted (its
    adapter WARN may follow);
  - Redis failure → cache miss with the existing WARN, and the item is fetched upstream.
- Geocoding API: never called by this endpoint.

## Error handling
| Situation | Result |
|---|---|
| Invalid `X-Client-Id` (any units) | `400 VALIDATION_FAILED` with an `X-Client-Id` error (the resolver runs first) |
| Invalid `units` | `400 VALIDATION_FAILED` with a `units` error, as `/weather/current` |
| DB failure loading favourites | `500 INTERNAL_ERROR` (existing handler, ERROR log), the provider is not called |
| `WeatherProviderUnavailableException` for an item | item `error UPSTREAM_UNAVAILABLE`, `200` |
| `LocationNotFoundException` for an item | item `error LOCATION_NOT_FOUND` with a fixed detail, `200` |
| Item not done at the deadline | item `error UPSTREAM_UNAVAILABLE`, `200`, counted in `timedOut` |
| Any other exception in a lookup | fan-out cancels the others and rethrows → `500 INTERNAL_ERROR` (generic body, ERROR log with stack trace as today) |
| Request thread interrupted while waiting | cancel the workers, restore the interrupt flag, `IllegalStateException` → `500` |
| Executor shut down (context closing) | `RejectedExecutionException` → `500` |

Logging:
- When ≥ 1 item failed, exactly one WARN from `FavouriteWeatherService`:
  `Favourites weather partial failure failed=<n> total=<m> timedOut=<k>`. The request id comes
  from the pattern.
- Successful requests write nothing new at INFO or above.
- The adapter and cache WARN lines are unchanged and carry the request id on workers.

## Security considerations
- **Client scoping**: the endpoint returns only rows with `client_id = X-Client-Id` (ADR 0009).
  A client with no rows gets `[]`, so it can't tell whether other clients exist.
- **No new data**: items expose only what `GET /api/v1/favourites` and `/weather/current` already
  expose. `createdAt`/`updatedAt` are not included.
- **Privacy in logs**: the new code logs counts only. The client id, labels, cities and
  coordinates are never logged at INFO or above. `Favourite#toString` and `ClientId#toString`
  are already redacted. Workers copy only the MDC, which holds just `requestId`.
- **Error texts**: per-item `detail` values are fixed constants. Exception messages (which for
  `LocationNotFoundException` contain the city) are never used.
- **Redis keys**: only coordinates (from the database, originally from upstream) go into keys,
  through `CacheKeys` validation.
- **Resource exhaustion**: per request, threads are bounded by `max-concurrency` (≤ 20), upstream
  calls by `max-per-client`, and time by `timeout` (≤ 30 s). Global rate limiting is out of
  scope (A-6), see Risks.

## Test strategy
Unit tests (`src/test/java`, `*Test`) use no Spring context and no Docker. ITs
(`src/integrationTest/java`, `*IT`) use profile `it` against the compose Postgres/Redis, never
Testcontainers, and never start or stop containers (ADR 0001). Every test has
`@DisplayName("AC-n: …")`. ITs delete the favourite rows of their client ids in `@AfterEach`
(`FavouritesIntegrationTestBase` does this already; real-provider classes track their own ids, like
`FavouritesRealProviderIT`). Per-run Redis keys are removed by `IntegrationTestBase` and
`CachingIntegrationTestBase`.

Unit tests:
| Test | ACs | What |
|---|---|---|
| `concurrent.BoundedFanOutTest` | 2, 15, 16, 17, NFR resources, MDC | Driven with latches and barriers only, no `sleep`-based assertions. (a) Order: tasks finish in reverse order (each waits for the next index's latch) → results in input order. (b) Limit: 10 inputs, limit 3; each task increments an in-flight counter, records the max, then waits on a gate. Assert in-flight reaches exactly 3 (await with a 5 s cap), release, assert max == 3, and assert exactly 3 distinct worker threads were used. (c) Deadline: 1 task blocks forever on a latch, timeout 200 ms → returns within 2 s, that slot is empty, the blocked task saw an interrupt. (d) No start after the deadline: limit 1, 3 inputs, the first task blocks until interrupted → only the first task ever started. (e) Fail fast: one task throws `IllegalStateException` while another blocks → the same exception instance is rethrown quickly and the blocked task is interrupted. (f) MDC: the caller sets `requestId=x`, the task records `MDC.get("requestId")` → `x`, and after the call a fresh task on the same executor sees null (cleared). (g) An empty input returns `[]` and submits nothing (counting executor). (h) Invalid arguments are rejected. The executor is `BoundedFanOut.virtualThreads(...)`, closed in `@AfterEach`. |
| `favourites.FavouriteWeatherServiceTest` | 1, 4, 11, 12, 14, 15, 23 | Fake `FavouriteRepository` (in-memory) and a hand-written `WeatherProvider` fake. A real `WeatherService` with a fixed `Clock`. A real `BoundedFanOut`. Order and mapping, empty → no provider call, per-item failure mapping, `IllegalStateException` propagates, repository failure propagates and the provider is untouched, the WARN line with counts only (Logback `ListAppender` on the class logger: one event, the formatted message contains `failed=1 total=3`, and contains no city or label), and no WARN when everything succeeds. |
| `favourites.FavouritesWeatherPropertiesTest` | 18 | Plain `Binder` (like `FavouritesPropertiesTest`). Defaults 4/5s. Accepts 1, 20, `1s`, `30s`. Rejects `0`, `21`, `abc`, `0s`, `31s`, `abc` with a `BindException` whose message chain names the property. The range messages name the env var. |
| `favourites.FavouritesWeatherConfigFilesTest` | 18 | Like `FavouritesConfigFilesTest`: `application.yml` placeholders `${WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY:4}` and `${WEATHER_FAVOURITES_WEATHER_TIMEOUT:5s}`, the commented `.env.example` lines, and the `compose.yaml` `${VAR:-4}` / `${VAR:-5s}`. |
| `provider.stub.StubWeatherProviderLocationTest` | 1, 14 | Each of the 5 fixed locations → its conditions with `observedAt = now`. Known name with other coordinates and unknown coordinates → `LocationNotFoundException`. |
| `provider.WeatherProviderDefaultsTest` (or added to an existing provider unit test file as a new class) | 14 | The default `currentAtLocation` delegates to `currentAt(location.name(), now)`. |
| `provider.openmeteo.OpenMeteoWeatherProviderLocationTest` | 7, 8, 9, 13 | Same harness as `OpenMeteoWeatherProviderCachingTest`: no geocoding request, the coordinate texts sent, a hit returns the stored `fetchedAt` with no request, a miss writes `CacheKeys.current(location)`, a 500 writes nothing. |
| `weather.WeatherServiceTest` (extend with new methods) | 1, 3 | `currentAt(Location, Units, now)` converts like `current(city, units)`. |
| `api.v1.FavouritesWeatherControllerSignatureTest` | 5 | Reflection: exactly 1 handler, `ClientId` first, no `jakarta.validation.constraints` parameter annotations. |
| `api.v1.FavouritesWeatherQueryTest` | 3 | Bean Validation (plain `Validator`) gives the same violations as `CurrentWeatherQuery.units` for `IMPERIAL`, `kelvin`, `""`. Null → metric. |
| `api.v1.dto.FavouriteWeatherResponseJsonTest` | 1, 11, 19 | Plain `ObjectMapper` with `JavaTimeModule` as Boot configures it: `label`, `weather`, `error` are omitted when null, and the property names are as specified. The error code/detail mapping and the UPSTREAM detail equal `ApiExceptionHandler.UPSTREAM_DETAIL`. |
| `api.v1.FavouritesWeatherOpenApiDocumentTest` | 19 | Parses the spec: the operation exists with tag, parameters and responses as specified, the `FavouriteWeatherError.code` enum equals `FavouriteWeatherFailure` names and is a subset of `ErrorCode`, the required sets, `maxItems 100`, `weather` → `CurrentWeather`. `OpenApiDocumentTest` is unchanged. |

Integration tests (all new, under `src/integrationTest/java/com/example/weather/favourites/`
unless noted):
| IT | Base / context | ACs |
|---|---|---|
| `FavouritesWeatherIT` | `FavouritesIntegrationTestBase` (stub + `@MockitoSpyBean WeatherProvider`, shared context) | 1, 2 (stub reorder), 3, 4, 5, 6, 11 (stub path: `doThrow(new WeatherProviderUnavailableException(…)).when(provider).currentAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any())`), 12, 14 (spy `LocationNotFoundException`, plus a row inserted directly at `0.0/0.0`), 15 (spy `IllegalStateException`), 20, 23 (snapshot `rows(clientId)` before and after AC-1/11/12 and compare every column). W(city, units) is fetched in the same test from `/weather/current`. "Provider not called" = `verifyNoInteractions(provider)` after `clearInvocations` following setup. |
| `FavouritesWeatherDbFailureIT` | `FavouritesIntegrationTestBase` + `@MockitoSpyBean FavouriteRepository` (own context) | 15 (`doThrow(new DataAccessResourceFailureException("x")).when(repository).findAll(anyString())` → `500 INTERNAL_ERROR`, provider not called) |
| `FavouritesWeatherLoggingIT` | `FavouritesIntegrationTestBase` + `OutputCaptureExtension` | 11 (one endpoint WARN with `it-faw-11`, `failed=1`, `total=3`, no city/label/client id/coordinate text), 21. Reuse the polling approach of `FavouritesLoggingIT#logged` for the access line (the filter logs after the body is flushed). |
| `FavouritesWeatherContractConformanceIT` | `FavouritesIntegrationTestBase` | 19: AC-1, AC-4, AC-11 mixed and AC-12 all-error bodies against `FavouriteWeatherList` (`OpenApiSchemaAssert`), plus an explicit check that each item has exactly one of `weather`/`error`. The AC-3/AC-5 `400` bodies against `Problem`. |
| `FavouritesWeatherRealProviderIT` | `CachingIntegrationTestBase` + `@TestPropertySource("weather.favourites.weather.max-concurrency=4", "weather.favourites.weather.timeout=5s", "weather.favourites.max-per-client=20")` | 2 (reverse delays via a `CURRENT` responder keyed on `latitude`), 7, 8 (both directions), 9 (key set diff: keys after minus keys before the request = exactly the `current` keys of the distinct coordinates; PTTL in (0, 600000]; no key contains client id/label/city), 11 real path (500 for `latitude=48.8566`; adapter WARN line contains `[requestId=it-faw-11]`), 12 real path, 13, 16 |
| `FavouritesWeatherDeadlineIT` | `CachingIntegrationTestBase` + timeout 1s + read timeout 5s (see note) | 17 |
| `FavouritesWeatherRedisDownIT` | `CachingIntegrationTestBase` + `@Import` a `@Primary IsolatedCacheStore` on a closed port (as `RedisUnreachableIT`) + `OutputCaptureExtension` | 10: 3 items with `weather`, 3 `CURRENT` calls; every WARN/ERROR line in the request's captured slice matches `Cache operation failed …` |
| existing `OpenApiDriftIT`, `RealProviderOpenApiDriftIT` | unchanged | 19 (the new mapping must be in the spec) |
| existing `FavouritesSchemaIT`, `MigrationFilesTest` | unchanged | 22 |
| entire existing suite | unchanged | 24 |

Test support (new, test code only):
- `support.FavouriteRows`: a static `insert(JdbcTemplate, clientId, city, name, country, lat, lon,
  position, Instant)` helper for direct inserts (it computes `city_key` with
  `CityNames.normalise`). It is used by AC-7, AC-14 and AC-16.
- `support.InFlightResponder`: a thread-safe `Function<RecordedRequest, Response>` for
  `fake.stub(Route.CURRENT, …)`. It increments an in-flight counter, records the max, waits
  according to a per-request delay function (e.g. by `latitude`), decrements, and returns
  `CURRENT_AC6` with zero extra delay. It also exposes a `completed()` counter and an
  `awaitIdle(Duration)` helper. Keying on the request's `latitude` makes per-item behaviour
  independent of scheduling.

Determinism notes (Windows):
- **AC-16**: the max-in-flight lower bound is made deterministic. The responder's first two
  arrivals wait on a shared `CountDownLatch(2)` (5 s cap) before their 200 ms delay. A correct
  implementation reaches 2 at once. A serial one would time out on the latch and record max 1,
  so the test fails for the right reason. The upper bound (≤ 4) is structural: the counter is
  decremented before the response is written, so a worker can only start its next call after
  the decrement. The `< 4 s` bound has ≈ 3 s of headroom over the ≈ 1 s ideal. Before timing,
  send one warm-up request (an empty-list client) so that lazy initialisation (DispatcherServlet,
  `HttpClient`, Lettuce connection) is not measured.
- **AC-17**: same warm-up. Tokyo is delayed 3 s against a 1 s deadline and a 2 s assertion
  (1 s margin on each side). After asserting the response, call `responder.awaitIdle(5s)` so that
  the late Tokyo handler has finished. Then assert the fake received exactly 3 `CURRENT`
  requests (nothing started after the deadline) and that the response body is unchanged
  (already captured). This also stops the late handler leaking into the next test's
  `fake.reset()`.
- **Read-timeout override for AC-17**: `RealProviderInitializer` writes
  `weather.provider.read-timeout=1s` into the inlined-properties source, which can override a
  class-level `@TestPropertySource` with the same key. Set `read-timeout=5s` through
  `@DynamicPropertySource` (or an extra initializer ordered after `RealProviderInitializer`), and
  add a guard assertion that `environment.getProperty("weather.provider.read-timeout")` is `5s`.
  Without the guard, a 1 s read timeout would make the test pass without proving the deadline.
- Logging assertions use the polling helper (the access line is written after the response) and
  compare only the output slice captured after a mark taken before the request.
- No test relies on thread scheduling order. Per-item behaviour is keyed on coordinates, and
  results are compared by item id.

## Implementation plan
1. `feat(provider): add WeatherProvider#currentAtLocation` with the default method, the stub
   override and the Open-Meteo override (refactor `currentAt` to share the private step), plus
   unit tests (`StubWeatherProviderLocationTest`, `OpenMeteoWeatherProviderLocationTest`,
   defaults test). `./gradlew build` passes, and the existing caching tests are unchanged.
2. `feat(weather): WeatherService#currentAt(Location, Units, Instant)`, extract the conversion,
   extend `WeatherServiceTest`.
3. `feat(concurrent): BoundedFanOut` with `BoundedFanOutTest`.
4. `feat(favourites): FavouritesWeatherProperties` plus `application.yml`, `.env.example` and
   `compose.yaml` entries, with `FavouritesWeatherPropertiesTest` and
   `FavouritesWeatherConfigFilesTest`.
5. `feat(favourites): FavouriteWeatherService`, `FavouriteWeather`, `FavouriteWeatherFailure`,
   `FavouriteWeatherConfiguration`, with `FavouriteWeatherServiceTest`.
6. `feat(api): GET /api/v1/favourites/weather`: `FavouritesWeatherController`, query record,
   DTOs, `UPSTREAM_DETAIL` made public, plus the signature, query and JSON unit tests.
7. `docs(api): document /api/v1/favourites/weather in openapi-v1.json` with
   `FavouritesWeatherOpenApiDocumentTest`. Run `OpenApiDriftIT`/`RealProviderOpenApiDriftIT`.
8. `test: stub-path ITs`: `FavouritesWeatherIT`, `FavouritesWeatherDbFailureIT`,
   `FavouritesWeatherLoggingIT`, `FavouritesWeatherContractConformanceIT`, `support.FavouriteRows`.
9. `test: real-provider ITs`: `support.InFlightResponder`, `FavouritesWeatherRealProviderIT`,
   `FavouritesWeatherDeadlineIT`, `FavouritesWeatherRedisDownIT`.
10. Run `scripts/test-env.sh all`, confirm `git diff main -- src/main/resources/db/migration/` is
    empty and that no existing test file changed except `WeatherServiceTest` (extended only).

## Risks / open questions
- **A-3 (human decision)**: `200` with all items failed vs `503`. The design follows A-3 (ADR 0014).
  Switching later would be a behaviour change within v1.
- **A-4 (human decision)**: stored coordinates vs re-geocoding. The design follows A-4 (ADR 0012).
- **A-5 defaults 4 / 5 s**: they are configurable as per AC-18. A 5 s budget is above the
  per-call 2 s + 3 s worst case of a single call, so one hung call costs at most 5 s instead of
  5 waves × 5 s.
- **Global upstream load (A-6)**: concurrency is bounded per request, not globally, and there is
  no rate limiting. Many clients polling at once can exceed the free tier. This is out of scope
  and needs its own ADR if required.
- **Lingering workers after the deadline**: an interrupted call may log `Upstream call failed`
  (and an interrupted Lettuce call `Cache operation failed … reason=unavailable`) *after* the
  response. That is expected on the timeout path only. AC-10/AC-21 are success paths and don't
  hit it.
- **Virtual-thread pinning (Java 21)**: possible in `synchronized` sections of libraries. It is
  a throughput risk, not a correctness risk. The executor can be swapped behind `BoundedFanOut`
  (ADR 0013 Consequences).
- **Interrupt handling in Spring's `JdkClientHttpRequest`**: an interrupted `send` surfaces as an
  `IOException`, which becomes `ResourceAccessException` and then `WeatherProviderUnavailableException`.
  If a Spring version swallowed the interrupt, the call would still end within its read timeout.
  The response is unaffected either way, because it is built from the snapshot at the deadline.
- **Directly inserted non-finite coordinates** (`NaN`) would make `BigDecimal.valueOf` throw →
  `500`. This is only reachable by manual DB writes. It is accepted and noted in ADR 0012.
- **`weather.location` vs item `location`**: both are the stored location for Open-Meteo (the
  adapter maps onto the passed location) and for API-created stub rows. For a directly inserted
  stub row with stub coordinates but another name, `weather.location` is the stub's fixed
  location. That case only arises in tests.
- **Routing side effect (A-1)**: `PATCH`/`DELETE /api/v1/favourites/weather` → `400` field `id`,
  like `/order`. It is documented in the `FavouriteId` description.
