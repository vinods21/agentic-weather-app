# Forecast for all favourites: Design
Requirements: ./requirements.md · ADRs: 0001, 0002, 0003, 0005, 0006, 0007, 0008, 0009, 0010, 0012, 0013, 0014, 0015 (new, amends 0012), 0016 (new, refines 0013, applies 0014)

## Summary
A new read-only endpoint, `GET /api/v1/favourites/forecast`, returns a daily forecast for every
favourite of the calling `X-Client-Id`, in stored order, as `{"items": [...]}`. Each item has the
favourite's `id`, `city`, optional `label` and `location`, plus exactly one of `forecast` (the
unchanged `Forecast` schema) or `error` (the unchanged `FavouriteWeatherError` schema).

The design mirrors `/favourites/weather` (weather-for-all-favourites design) step by step:
1. `FavouritesForecastController` (a new controller, `ClientId` first) validates `units` and
   `days` with the same rules and messages as `/weather/forecast`. `@DaysParam`,
   `DaysParamValidator` and `ForecastQuery.DEFAULT_DAYS` are reused, not copied.
2. `FavouriteForecastService` loads the favourites with one plain read (no transaction is held
   afterwards) and computes **one** UTC start date per request from the service `Clock`.
3. It fans the lookups out through the **existing** `BoundedFanOut` bean with the **existing**
   `weather.favourites.weather.{max-concurrency,timeout}` settings (ADR 0013, 0016).
4. Each lookup goes through the new `WeatherService#forecastAt(Location, Units, LocalDate, int)`,
   which calls the new port method `WeatherProvider#forecastAtLocation(Location, LocalDate, int)`
   (ADR 0015). The Open-Meteo adapter implements it with the existing `forecast` cache entry,
   keyed by the stored coordinates, start date and days, and makes one `DAILY` call on a miss. There
   is no geocoding. The stub matches the stored coordinates exactly.
5. Expected provider failures become per-item errors inside a `200`, even when every item fails,
   with the existing `FavouriteWeatherFailure` enum and `FavouriteWeatherErrorDto` (ADR 0014,
   0016). A bug or a database failure is still a `500`.

No migration, no new dependency, no new configuration property or env var, no new Redis key kind,
no new `ErrorCode`, no new executor. `FavouriteWeatherService`, `FavouritesWeatherController` and
every existing test file stay unchanged. The OpenAPI document stays hand-written (ADR 0002): one
new path, two new schemas, and one description change (`FavouriteId`).

Final names (A-11):

| Item | Name |
|---|---|
| Path | `GET /api/v1/favourites/forecast`, operationId `listFavouritesForecast`, tag `favourites` |
| Body properties | `items`; per item `id`, `city`, `label`, `location`, `forecast`, `error`; error `code`, `detail` |
| Schemas | new `FavouriteForecastList`, `FavouriteForecast`; reused `Forecast`, `Location`, `FavouriteWeatherError` |
| Per-item codes | `UPSTREAM_UNAVAILABLE`, `LOCATION_NOT_FOUND` (unchanged) |
| Config | none new; `weather.favourites.weather.max-concurrency` (4) and `weather.favourites.weather.timeout` (5s) are shared (A-6, ADR 0016) |
| Port method | `WeatherProvider#forecastAtLocation(Location, LocalDate, int)` |
| WARN text | `Favourites forecast partial failure failed=<n> total=<m> timedOut=<k>` |

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `FavouritesForecastController#forecast`, `FavouriteForecastService`, `FavouriteForecastResponse`, `StubWeatherProvider#forecastAtLocation` | `produces = application/json`. `X-Request-Id` comes from the existing filter. Items come from `FavouriteService#list` (stored order), mapped 1:1 with `id`, `city`, `location` from the row. `label`, `forecast` and `error` are `@JsonInclude(NON_NULL)`. `forecast` is `ForecastResponse.from(...)`, the same DTO and conversion as `/weather/forecast`. The stub returns the city's fixed days from the start date, with the passed-in location, which for API-created rows equals the stub's location. So `forecast` equals F(city, metric, 5). |
| AC-2 | `BoundedFanOut` (results in input order), `JdbcFavouriteRepository#findAll` (`ORDER BY position, created_at, id`) | Results go into the slot of their input index, so completion order never matters. Unchanged code. |
| AC-3 | `FavouritesForecastQuery` (`@DaysParam String days`, empty → absent, default `ForecastQuery.DEFAULT_DAYS`, parsing with `DaysParamValidator.parse`) | The same constraint, the same normalisation and the same parser as `ForecastQuery`, so `+3`, `03` and ` 3 ` give 3 and an empty value gives 5. A parity unit test compares both records over the AC-3/AC-5 table. |
| AC-4 | `FavouritesForecastQuery.units` (`@Pattern(regexp = "metric\|imperial", message = "must be one of: metric, imperial")`, null → metric), `WeatherService` conversion | The same rule as `ForecastQuery.units`, and the same `convert` as `/weather/forecast`. |
| AC-5 | `FavouritesForecastQuery`, existing `BindException` handler | Validation runs during argument resolution, before the handler, so the provider is not called. All violations are reported together (`units=kelvin&days=99` → 2 entries). Unknown parameters (`page`, `city`) don't bind to the record and are ignored. The parity unit test compares the violation sets. The IT compares status and `errors` with `/weather/forecast?city=London&<qs>`. |
| AC-6 | `FavouriteForecastService` | An empty list returns `List.of()` before any clock read, fan-out or provider call. |
| AC-7 | `ClientIdArgumentResolver` (existing), `FavouritesForecastController` (`ClientId` first) | The resolver throws `InvalidClientIdException` before `@ModelAttribute` binding, so the `X-Client-Id` error is reported and nothing is read. New `FavouritesForecastControllerSignatureTest`: exactly 1 handler, `ClientId` first, no `jakarta.validation.constraints` parameter annotations. The handler is in a new controller, so `FavouritesWeatherControllerSignatureTest` (exactly 1 handler) stays unmodified (A-12). |
| AC-8 | `FavouriteService#list` → `findAll(clientId)` | Existing client scoping. |
| AC-9 | `OpenMeteoWeatherProvider#forecastAtLocation` | No `geocode` step. On a miss, one `DAILY` call with `coordinates(location)` (the same `BigDecimal.valueOf(double).toPlainString()` text), `start_date = start`, `end_date = start + days - 1`. No `CURRENT` call. The result carries the passed-in (stored) location (ADR 0015 §3). |
| AC-10 | same, `RedisWeatherCache` `forecast` entry | `session.forecast(location, start, days)` / `putForecast(...)` use `CacheKeys.forecast(location, start, days)`, the same key `/weather/forecast` uses after geocoding to the same coordinates. So entries are shared in both directions. `days` is in the key, so `days=4` misses. |
| AC-11 | same | Only `forecast` entries are written, with `forecast-ttl` (`SET … PX`). The key parts are `forecast`, `v1`, lat, lon, date and days only. |
| AC-12 | `RedisWeatherCache` fail-open (unchanged), one session per item | Each item's lookup opens its own session. A Redis failure is a miss plus the existing `Cache operation failed` WARN, and the item is fetched upstream. No item fails, so the endpoint logs nothing. |
| AC-13 | `FavouriteForecastService` | `start = LocalDate.now(clock.withZone(UTC))` is read **once**, after the empty check and before the fan-out, and passed to every task. Unit test with a moving clock. |
| AC-14 | `FavouriteForecastService#lookup` (catches `WeatherProviderUnavailableException`), `FavouriteWeatherErrorDto` (reused), `BoundedFanOut` MDC propagation | The failed item gets `error {UPSTREAM_UNAVAILABLE, ApiExceptionHandler.UPSTREAM_DETAIL}`. One WARN on the request thread: `Favourites forecast partial failure failed=1 total=3 timedOut=0`, which differs from the weather endpoint's line. The request id comes from the log pattern's `%X{requestId}`. Workers run with the caller's MDC, so the adapter's `Upstream call failed` line carries `[requestId=it-ffc-14]`. |
| AC-15 | same | Every item fails → `200` with 3 error items (ADR 0014 §3). Nothing is cached, so the next request succeeds. |
| AC-16 | `OpenMeteoWeatherProvider` (failures never cached, ADR 0007 §6) | Paris has no `forecast` entry after the failure, London and Tokyo do. The second request makes exactly 1 `DAILY` call. |
| AC-17 | `FavouriteForecastService#lookup` (catches `LocationNotFoundException`), `StubWeatherProvider#forecastAtLocation` (unknown coordinates → not found) | `error {LOCATION_NOT_FOUND, FavouriteWeatherErrorDto.LOCATION_NOT_FOUND_DETAIL}`, a fixed text with no city. |
| AC-18 | `BoundedFanOut` fail-fast rethrow, `ApiExceptionHandler#handleUnexpected` (existing) | An `IllegalStateException` escapes the task. The fan-out cancels the other workers and rethrows on the request thread → `500 INTERNAL_ERROR`. A database failure in `findAll` is thrown before the fan-out → `500`, and the provider is not called. |
| AC-19 | `BoundedFanOut` (k = min(max-concurrency, n) workers), `FavouritesWeatherProperties` (shared) | In-flight calls ≤ k by construction. 20 × 200 ms in waves of 4 ≈ 1 s, well under 4 s. |
| AC-20 | `BoundedFanOut` deadline, `FavouritesWeatherProperties.timeout` (shared) | The request thread returns at the deadline. Unfinished slots → `UPSTREAM_UNAVAILABLE`, counted as `timedOut`. No task starts after the deadline. The response is built from the snapshot. |
| AC-21 | `FavouriteForecastService` injects `FavouritesWeatherProperties`; `application.yml` comment only | No new property, env var, `.env.example` or `compose.yaml` change (ADR 0016 §2). The existing range checks are untouched. |
| AC-22 | `openapi-v1.json` | See [API](#api). |
| AC-23 | `FavouritesForecastController` `@GetMapping("/forecast")` | Spring prefers the literal `/api/v1/favourites/forecast` over `/{id}` across controllers. `POST`/`PUT` match no handler for that path → the existing 405 handler with `Allow` (GET, PATCH, DELETE). `PATCH`/`DELETE` hit `{id}` → `400` field `id`. |
| AC-24 | `RequestIdFilter` (unchanged), `FavouriteForecastService` (no INFO+ logging on success) | One access line, with the path only. The new code never logs client ids, labels, cities or coordinates. |
| AC-25 | none | No migration. |
| AC-26 | `FavouriteForecastService` | Only `findAll` (a single auto-commit `SELECT`) runs before the fan-out. No transaction template, no writes, no DB access on workers (ADR 0013 §7). |
| AC-27 | all | `forecast(city, …)` in the adapter and `WeatherService#forecast` are refactored to share their private steps. Calls, keys and session use stay the same. No existing test file changes. |

## Components & packages
New (main):

| Class | Responsibility |
|---|---|
| `favourites.FavouriteForecast` | `record FavouriteForecast(Favourite favourite, Forecast forecast, FavouriteWeatherFailure failure)`. The compact constructor requires `favourite` and exactly one of `forecast`/`failure` (`IllegalArgumentException`). Factories `ok(Favourite, Forecast)` and `failed(Favourite, FavouriteWeatherFailure)`. `toString` delegates to `Favourite#toString` (redacted), like `FavouriteWeather`. |
| `favourites.FavouriteForecastService` | `@Service`. Dependencies: `FavouriteService`, `WeatherService`, `BoundedFanOut` (the existing single bean), `FavouritesWeatherProperties`, `Clock`. `List<FavouriteForecast> forecastForAll(String clientId, Units units, int days)`: (1) `favourites.list(clientId)`; (2) empty → `List.of()`; (3) `LocalDate start = LocalDate.now(clock.withZone(ZoneOffset.UTC))`, read once; (4) `fanOut.map(list, f -> lookup(f, units, start, days), properties.maxConcurrency(), properties.timeout())`; (5) an empty slot → `failed(f, UPSTREAM_UNAVAILABLE)` and `timedOut++`; (6) if failed > 0, one WARN `Favourites forecast partial failure failed={} total={} timedOut={}`. `lookup` catches only `WeatherProviderUnavailableException` → `UPSTREAM_UNAVAILABLE` and `LocationNotFoundException` → `LOCATION_NOT_FOUND`. Everything else propagates. No `@Transactional` and no `TransactionOperations`. It deliberately repeats the ~20-line assembly loop of `FavouriteWeatherService` rather than refactoring that class (see Risks). |
| `api.v1.FavouritesForecastController` | `@RestController @RequestMapping(value = FavouritesController.BASE, produces = APPLICATION_JSON_VALUE)`, one handler `@GetMapping("/forecast") FavouriteForecastListResponse forecast(ClientId clientId, @Valid @ModelAttribute FavouritesForecastQuery query)` → `service.forecastForAll(clientId.value(), query.resolvedUnits(), query.resolvedDays())`. No constraint annotations on parameters (ADR 0010 §2). |
| `api.v1.FavouritesForecastQuery` | `record FavouritesForecastQuery(@Pattern(regexp = "metric\|imperial", message = "must be one of: metric, imperial") String units, @DaysParam String days)`. Compact constructor: `units` null → `"metric"` (an empty string is kept, so it is invalid), `days` null or empty → null. `resolvedUnits()` and `resolvedDays()` behave exactly as in `ForecastQuery`: `days == null ? ForecastQuery.DEFAULT_DAYS : DaysParamValidator.parse(days).orElseThrow(...)`. There is no `city` component. |
| `api.v1.dto.FavouriteForecastListResponse` | `record (List<FavouriteForecastResponse> items)` with `from(List<FavouriteForecast>)`. |
| `api.v1.dto.FavouriteForecastResponse` | `record (String id, String city, @JsonInclude(NON_NULL) String label, LocationDto location, @JsonInclude(NON_NULL) ForecastResponse forecast, @JsonInclude(NON_NULL) FavouriteWeatherErrorDto error)` with `from(FavouriteForecast)`. `FavouriteWeatherErrorDto.from(failure)` is reused unchanged. |

Changed (main):

| Class | Change |
|---|---|
| `provider.WeatherProvider` | + `default LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days) { return forecast(location.name(), startDate, days); }` with Javadoc per ADR 0015. |
| `provider.openmeteo.OpenMeteoWeatherProvider` | + override `forecastAtLocation`: `checkDays(days); return forecast(cache.session(), location, startDate, days);`. The new private `forecast(Session, Location, LocalDate, int)` holds today's cache-read / `DAILY` call / put body. `forecast(String, LocalDate, int)` becomes `checkDays(days); session = cache.session(); return forecast(session, geocode(session, city), startDate, days);`. The order (days check, then session, geocode, cache read) is unchanged. `checkDays` is a private extraction of the existing `IllegalArgumentException` check. |
| `provider.stub.StubWeatherProvider` | + override `forecastAtLocation`: check `days` (same `IllegalArgumentException`), find the `CityData` whose fixed location has `latitude == location.latitude() && longitude == location.longitude()`, otherwise `throw new LocationNotFoundException(location.name())`. Return `new LocationForecast(location, firstDays(data, startDate, days))`. Extract `private static Optional<CityData> atCoordinates(Location)` (also used by `currentAtLocation`, same behaviour) and `private static List<DailyForecast> firstDays(CityData, LocalDate, int)` (also used by `forecast`, same behaviour). |
| `weather.WeatherService` | + `public Forecast forecastAt(Location location, Units units, LocalDate startDate, int days)` → `convert(provider.forecastAtLocation(location, startDate, days), units)`. Extract `private static Forecast convert(LocationForecast, Units)` from `forecast(city, units, days)`, which keeps reading the clock once and calling `provider.forecast` as before. |
| `favourites.FavouriteWeatherFailure`, `api.v1.dto.FavouriteWeatherErrorDto` | Javadoc only: "used by `/favourites/weather` and `/favourites/forecast` (ADR 0016)". No code change. |
| `favourites.FavouriteWeatherConfiguration`, `favourites.FavouritesWeatherProperties` | Javadoc only: the bean and the settings also serve `/favourites/forecast`. No code change, and the bean name and the `fav-weather-` prefix stay. |
| `application.yml` | Comment only, above `weather.favourites.weather`: `# GET /api/v1/favourites/weather and /api/v1/favourites/forecast (ADR 0013, 0016): …`. The keys and placeholders are unchanged, so `FavouritesWeatherConfigFilesTest` still passes. |
| `openapi-v1.json` | see [API](#api). |

Unchanged on purpose: `FavouriteWeatherService`, `FavouritesWeatherController`,
`FavouritesWeatherQuery`, `ForecastQuery`, `BoundedFanOut`, `ApiExceptionHandler`, `CacheKeys`,
`RedisWeatherCache`, `.env.example`, `compose.yaml` and all migrations.

Dependency direction (as before): `api.v1` → `favourites` → (`weather`, `concurrent`, `provider`
exceptions). `weather` and `provider` don't depend on `favourites`.

## API
`GET /api/v1/favourites/forecast`

| Input | Rule |
|---|---|
| `X-Client-Id` (header, required) | `^[A-Za-z0-9._-]{1,64}$` (ADR 0009) |
| `units` (query, optional) | `metric` (default) \| `imperial`, case-sensitive, an empty value is invalid. Same as `/weather/forecast`. |
| `days` (query, optional) | 1..7, default 5; optional surrounding whitespace, optional sign, ASCII digits only; an empty value is absent. Same as `/weather/forecast`. |
| `X-Request-Id` (header, optional) | ADR 0003 |
| anything else (e.g. `city`, `page`) | ignored |

`200` `application/json`, header `X-Request-Id` (`?days=2`, shortened):
```json
{
  "items": [
    {
      "id": "0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f",
      "city": "London",
      "label": "Home",
      "location": {"name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278},
      "forecast": {
        "location": {"name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278},
        "units": "metric",
        "days": [
          {"date": "2026-11-15", "minTemperature": 6.0, "maxTemperature": 12.5, "precipitationProbability": 40,
           "condition": {"code": "RAIN", "description": "Light rain"}},
          {"date": "2026-11-16", "minTemperature": 5.5, "maxTemperature": 11.0, "precipitationProbability": 20,
           "condition": {"code": "PARTLY_CLOUDY", "description": "Partly cloudy"}}
        ]
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
- Each item has exactly one of `forecast` / `error`. `label` is omitted when not set.
- Every `forecast` in one response has the same `units`, the same number of `days` and the same
  dates, starting at the request's UTC date.
- `error.code` is `UPSTREAM_UNAVAILABLE` (the provider failed, or the item didn't finish within the
  time budget) or `LOCATION_NOT_FOUND` (detail `No weather data is available for this location.`).

Errors (Problem Details, existing handlers):
- `400 VALIDATION_FAILED`: invalid `X-Client-Id` (field `X-Client-Id`; the resolver runs first),
  and/or invalid `units` (field `units`) and/or `days` (field `days`), all reported together.
- `405 METHOD_NOT_ALLOWED` for `POST`/`PUT` (`Allow` includes `GET`). `PATCH`/`DELETE` hit `{id}` →
  `400` field `id` (A-1, documented).
- `500 INTERNAL_ERROR`: a database failure or an unexpected exception.
- No `404` and no `503` from this endpoint.

OpenAPI changes (hand-written, ADR 0002). Insert the path after `/api/v1/favourites/weather` and
the schemas after `FavouriteWeatherError`:
- New path `/api/v1/favourites/forecast` → `get`:
  - `operationId listFavouritesForecast`, `tags ["favourites"]`;
  - summary "Daily forecast for every favourite of the client";
  - description: stored order; lookup by stored coordinates (no re-geocoding of city); the same
    `units`/`days` rules as `/api/v1/weather/forecast`, applied to every item; every item starts
    at today's UTC date; at most one upstream call per favourite and none when cached; per-item
    errors with `200` even if all fail; time budget and concurrency shared with
    `/api/v1/favourites/weather`; unknown query parameters ignored; clients must tolerate unknown
    `error.code` values; `PATCH`/`DELETE` on this path hit `/api/v1/favourites/{id}` and are 400
    (field id);
  - parameters, in this order: `ClientId`, `Units`, `Days`, `RequestId` (all `$ref`, unchanged);
  - responses `200` (description "The client's favourites in list order, each with its daily
    forecast or a per-item error.", header `X-Request-Id` `$ref XRequestId`, `application/json`
    `$ref FavouriteForecastList`), `400` `$ref BadRequest`, `500` `$ref InternalError`.
- `components.schemas.FavouriteForecastList`: `type object`, `required [items]`, `items`: array,
  `maxItems 100`, description "One item per favourite of the client, in list order.", `items
  $ref FavouriteForecast`.
- `components.schemas.FavouriteForecast`:
  - `type object`, `required [id, city, location]`;
  - description "A favourite with its daily forecast. Exactly one of forecast or error is present.";
  - properties in this order: `id`, `city`, `label` (the same definitions as in
    `FavouriteWeather`, copied), `location` `$ref Location`, `forecast` `$ref Forecast`, `error`
    `$ref FavouriteWeatherError`.
- `components.parameters.FavouriteId.description`: replace the note with "note that \"order\",
  \"weather\" and \"forecast\" are not ids, so PATCH and DELETE on /api/v1/favourites/order,
  /api/v1/favourites/weather and /api/v1/favourites/forecast, and GET on /api/v1/favourites/order,
  are 400." The rest of the text is unchanged.
- Nothing else changes: the info description, the other paths, and the `Forecast`, `ForecastDay`,
  `Location`, `Favourite`, `FavouriteWeather*` and `Problem` schemas (including the
  `FavouriteWeatherError` description, A-3) stay as they are.

## Data model
- **PostgreSQL**: no change and no migration (AC-25). The endpoint reads `favourite` through the
  existing `findAll(clientId)` and never writes.
- **Redis**: no new key kind. It reads and writes only the existing
  `CacheKeys.forecast(location, startDate, days)` = `<prefix>:forecast:v1:<lat>:<lon>:<yyyy-MM-dd>:<days>`
  with `weather.cache.forecast-ttl` (default 1h, `SET … PX`). Keys never contain the client id,
  label or city text. Values are the existing versioned forecast JSON (`CacheCodec`).
- **Configuration**: nothing new (ADR 0016).

## External integrations
- Open-Meteo forecast API, `DAILY` request only
  (`/v1/forecast?latitude=…&longitude=…&daily=…&start_date=…&end_date=…`), through the existing
  adapter call path. The per-call connect timeout (2 s) and read timeout (3 s), no retries, no
  redirects, the 1 MiB cap and the sanitised WARN line all apply unchanged (ADR 0006).
- Per request: at most `n` `DAILY` calls (n = number of favourites), 0 on a warm cache for the
  requested `days`, 0 `GEOCODING`, 0 `CURRENT`. At most `max-concurrency` in flight. The overall
  deadline is `timeout`.
- Failure behaviour per item:
  - non-2xx, I/O, timeout or malformed body (including wrong dates/number of days) →
    `WeatherProviderUnavailableException` → item `UPSTREAM_UNAVAILABLE`, nothing cached;
  - deadline reached first → item `UPSTREAM_UNAVAILABLE`, the worker is interrupted (its adapter
    WARN may follow after the response), and a late success may still be cached;
  - Redis failure → cache miss with the existing WARN, and the item is fetched upstream.
- Geocoding API: never called by this endpoint.

## Error handling
| Situation | Result |
|---|---|
| Invalid `X-Client-Id` (any query) | `400 VALIDATION_FAILED` with an `X-Client-Id` error |
| Invalid `units` and/or `days` | `400 VALIDATION_FAILED`, the same `errors` as `/weather/forecast` |
| DB failure loading favourites | `500 INTERNAL_ERROR` (existing handler), the provider is not called |
| `WeatherProviderUnavailableException` for an item | item `error UPSTREAM_UNAVAILABLE`, `200` |
| `LocationNotFoundException` for an item | item `error LOCATION_NOT_FOUND` with a fixed detail, `200` |
| Item not done at the deadline | item `error UPSTREAM_UNAVAILABLE`, `200`, counted in `timedOut` |
| Any other exception in a lookup (incl. `IllegalArgumentException` for bad `days`, which validation makes unreachable) | the fan-out cancels the others and rethrows → `500 INTERNAL_ERROR` |
| Request thread interrupted / executor shut down | `500` (existing `BoundedFanOut` behaviour) |

Logging:
- When ≥ 1 item failed, exactly one WARN from `FavouriteForecastService`:
  `Favourites forecast partial failure failed=<n> total=<m> timedOut=<k>`. The request id comes
  from the pattern.
- Successful requests write nothing new at INFO or above.
- The adapter and cache WARN lines are unchanged and carry the request id on workers.

## Security considerations
- **Client scoping**: only rows with `client_id = X-Client-Id` are returned (ADR 0009).
- **No new data**: items expose only what `GET /api/v1/favourites` and `/weather/forecast` already
  expose. `createdAt`/`updatedAt` are not included.
- **Privacy in logs**: the new code logs counts only. `Favourite#toString` (and therefore
  `FavouriteForecast#toString`) and `ClientId#toString` are redacted. Workers copy only the MDC.
- **Error texts**: per-item `detail` values are the existing fixed constants. Exception messages
  (which for `LocationNotFoundException` contain the name) are never used.
- **Redis keys**: only stored coordinates and the server-computed date and validated `days` go
  into keys, through `CacheKeys` validation.
- **Resource exhaustion**: per request, threads are bounded by `max-concurrency`, upstream calls
  by `max-per-client`, and time by `timeout`. Global rate limiting is out of scope (see Risks).

## Test strategy
Unit tests (`src/test/java`, `*Test`) use no Spring context and no Docker. ITs
(`src/integrationTest/java`, `*IT`) use profile `it` against the compose Postgres/Redis, never
Testcontainers, and never start or stop containers (ADR 0001). Every test has
`@DisplayName("AC-n: …")`. ITs delete the favourite rows of their client ids in `@AfterEach`.
**No existing test file is modified** (AC-27). New behaviour of existing classes is tested in new
test classes.

Unit tests (all new):
| Test | ACs | What |
|---|---|---|
| `provider.WeatherProviderForecastAtLocationDefaultTest` | 17 | A hand-written provider implementing only the abstract methods: the default `forecastAtLocation` delegates to `forecast(location.name(), start, days)` and returns its result unchanged. |
| `provider.stub.StubWeatherProviderForecastLocationTest` | 1, 3, 17 | For each of the 5 fixed locations and n ∈ {1, 5, 7}: the result equals `forecast(city, start, n).days()` and its location is the passed-in location. A known name with other coordinates and `0.0/0.0` → `LocationNotFoundException`. `days` 0/8 → `IllegalArgumentException`. |
| `provider.openmeteo.OpenMeteoWeatherProviderForecastLocationTest` | 9, 10, 11, 16 | Same harness as `OpenMeteoWeatherProviderLocationTest` (`MockRestServiceServer`, `RedisWeatherCache` on `InMemoryCacheStore`): a miss makes exactly one daily request with the stored coordinate texts, `start_date`/`end_date`, no geocoding request, and writes only `CacheKeys.forecast(stored, start, n)`; the result's location is the stored one; a second call is a hit with no request; an entry written by `forecast("London", start, n)` is served to `forecastAtLocation(LONDON, start, n)` (and the reverse); `n+1` misses; a 500 writes nothing. |
| `weather.WeatherServiceForecastAtTest` | 1, 4 | `forecastAt(location, units, start, n)` passes `start` and `n` through and converts like `forecast(city, units, n)` (metric unchanged, imperial deg F). It never reads the clock (a clock that throws on use). |
| `favourites.FavouriteForecastServiceTest` | 1, 6, 13, 14, 15, 17, 18, 26 | In-memory fake `FavouriteRepository`, hand-written `WeatherProvider` fake, real `WeatherService`, real `BoundedFanOut.virtualThreads(...)` (closed in `@AfterEach`). Order and mapping; empty → no provider call and no clock read; per-item failure mapping; `IllegalStateException` propagates; a repository failure propagates and the provider is untouched; the WARN line (Logback `ListAppender`: one event, `Favourites forecast partial failure failed=1 total=3 timedOut=0`, no city/label/client id/coordinates) and no WARN on success. **AC-13**: a clock that answers `2026-11-15T23:59:59.900Z` on the first read and `2026-11-16T00:00:00.100Z` afterwards. The fake provider also reads it on every call. Every recorded `startDate` and every item's first date is `2026-11-15`, with `maxConcurrency` 1 and with 4. |
| `favourites.FavouriteForecastTest` | 14 | The exactly-one invariant and redacted `toString` (no client id or label). |
| `api.v1.FavouritesForecastControllerSignatureTest` | 7 | Reflection: exactly 1 handler, `ClientId` first, no `jakarta.validation.constraints` parameter annotations. |
| `api.v1.FavouritesForecastQueryParityTest` | 3, 4, 5 | Plain `Validator`. For every AC-3/AC-5 `units`/`days` value (`IMPERIAL`, `kelvin`, `""`, `0`, `8`, `abc`, `3.5`, `0x3`, `99999999999`, `+3`, `03`, ` 3 `, `""`, null, and the pair `kelvin`/`99`), the `(field, message)` violation set equals `ForecastQuery("London", units, days)`'s set without its `city` entries. On valid input `resolvedUnits()`/`resolvedDays()` are equal. |
| `api.v1.dto.FavouriteForecastResponseJsonTest` | 1, 14, 22 | Plain `ObjectMapper` configured as Boot does (`JavaTimeModule`, dates as strings): property names and order, `label`/`forecast`/`error` omitted when null, the `forecast` node equals the serialised `ForecastResponse`, the error detail equals `ApiExceptionHandler.UPSTREAM_DETAIL` / `LOCATION_NOT_FOUND_DETAIL`. |
| `api.v1.FavouritesForecastOpenApiDocumentTest` | 22 | Parses the spec: the operation with tag, parameters (`ClientId`, `Units`, `Days`, `RequestId` refs) and responses exactly `200, 400, 500`; `FavouriteForecastList` (`required [items]`, `maxItems 100`); `FavouriteForecast` required set, properties `id, city, label, location, forecast, error`, and `forecast` → `Forecast`, `error` → `FavouriteWeatherError`; the `FavouriteId` description mentions `forecast`. |
| `favourites.FavouritesForecastConfigFilesTest` | 21 | No `forecast` key under `weather.favourites` in `application.yml`, and no `WEATHER_FAVOURITES_FORECAST` text in `application.yml`, `.env.example` or `compose.yaml`. The tester also runs `git diff main -- .env.example compose.yaml`. |

Integration tests (all new, under `src/integrationTest/java/com/example/weather/favourites/`):
| IT | Base / context | ACs |
|---|---|---|
| `FavouritesForecastIT` | `FavouritesIntegrationTestBase`, **no extra configuration** (shares the existing stub + `@MockitoSpyBean WeatherProvider` context) | 1, 2 (stub reorder), 3, 4, 5 (each case compared with `/weather/forecast?city=London&<qs>`; "provider not called" = `clearInvocations(provider)` after setup, then `verifyNoInteractions`), 6, 7, 8, 14 stub (`doThrow(new WeatherProviderUnavailableException("x", null)).when(provider).forecastAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any(), anyInt())`), 15 stub, 17 (spy `LocationNotFoundException`, plus a row at `0.0/0.0` through `FavouriteRows.insert`), 18 (spy `IllegalStateException` → `500`, no message or class in the body), 23 (routing, including `GET /favourites/weather`, `/order` and a random uuid as today), 26 (row snapshot before and after AC-1/14/15, compare every column; `doAnswer` on `forecastAtLocation(any(), any(), anyInt())` recording `TransactionSynchronizationManager.isActualTransactionActive()` and `HikariPoolMXBean#getActiveConnections()`, as `FavouritesWeatherIT#ac23_…`). F(city, units, days) is fetched in the same test from `/weather/forecast`. |
| `FavouritesForecastLoggingIT` | `FavouritesIntegrationTestBase` + `@ExtendWith(OutputCaptureExtension.class)` (an extension, not a context customisation, so it shares the context) | 14 stub (exactly one endpoint WARN containing `[requestId=it-ffc-14]`, `failed=1`, `total=3`, no city/label/client id/coordinate text, and no `Favourites weather partial failure` line), 24. Use the polling approach of `FavouritesLoggingIT#logged` for the access line, and compare only the slice after a mark taken before the request. |
| `FavouritesForecastContractConformanceIT` | `FavouritesIntegrationTestBase` | 22: the AC-1, `days=7`, AC-6 empty, AC-14 mixed and AC-15 all-error bodies against `FavouriteForecastList` (`OpenApiSchemaAssert`), plus an explicit exactly-one-of `forecast`/`error` check per item; the AC-5 and AC-7 `400` bodies and the AC-18 `500` body against `Problem`. |
| `FavouritesForecastDbFailureIT` | `FavouritesIntegrationTestBase` + `@MockitoSpyBean FavouriteRepository` → own context → **`@DirtiesContext`** | 18 DB part: `doThrow(new DataAccessResourceFailureException("db-SECRET18 down")).when(repository).findAll(anyString())` → `500 INTERNAL_ERROR`, nothing leaked, `verifyNoInteractions(provider)`. |
| `FavouritesForecastRealProviderIT` | `CachingIntegrationTestBase` + `@TestPropertySource("weather.favourites.weather.max-concurrency=4", "weather.favourites.weather.timeout=5s")` + `OutputCaptureExtension` → **`@DirtiesContext`** | 2 real (an `InFlightResponder.daily(...)` keyed on `latitude`: T 600 ms, L 300 ms, P 0 ms, or a `before` hook that waits with `awaitCompleted`), 9, 10 (all four bullets in one test, in order), 11 (key diff: keys after minus keys before = exactly the `forecast` keys of the distinct coordinates; PTTL in (0, 3600000]; no key contains the client id, label or city), 14 real (fake answers 500 to `DAILY` with `latitude=48.8566`; the adapter's WARN contains `[requestId=it-ffc-14]`), 15 real, 16, 19 (20 rows via `FavouriteRows.insert` at distinct coordinates, `InFlightResponder.daily(r -> 200 ms).gate(2)`, warm-up first). |
| `FavouritesForecastDeadlineIT` | `CachingIntegrationTestBase` + `@TestPropertySource("weather.favourites.weather.max-concurrency=4", "weather.favourites.weather.timeout=1s")` + `@DynamicPropertySource` read-timeout `5s` (see the trap below) → **`@DirtiesContext`** | 20 |
| `FavouritesForecastRedisDownIT` | `CachingIntegrationTestBase` + `@Import` its own nested `@TestConfiguration` with a `@Primary IsolatedCacheStore` on a closed port (as `FavouritesWeatherRedisDownIT`) + `OutputCaptureExtension` → **`@DirtiesContext`** | 12: 3 items with `forecast`, 3 `DAILY` calls, and every WARN/ERROR line in the request's captured slice matches `Cache operation failed …` |
| existing `OpenApiDriftIT`, `RealProviderOpenApiDriftIT` | unchanged | 22 (the new mapping must be in the spec) |
| existing `FavouritesSchemaIT`, `MigrationFilesTest` | unchanged | 25 |
| entire existing suite | unchanged | 27 |

Postgres connections (NFR): the three stub-path classes add **no** context. The four classes
with their own configuration are all `@DirtiesContext`, so each one's pool (3 connections from
`RealProviderInitializer`, or 4 from `FavouritesIntegrationTestBase`) is closed after the class.
They never coexist with each other, so the peak rises by at most one transient context compared
with today's ~86. The tester runs `scripts/test-env.sh it` twice in a row and records the peak
`client backend` count from `pg_stat_activity` during one run.

Test support (additive only; every existing caller behaves the same):
- `support.FakeOpenMeteo`: + `public static Response dailyFor(RecordedRequest request)`, the body
  of today's default `DAILY` branch moved into a method. The default `DAILY` branch calls it, so
  the default response is byte-for-byte unchanged.
- `support.InFlightResponder`: + `public static InFlightResponder daily(Function<RecordedRequest,
  Duration> delay)` = `new InFlightResponder(delay).respond(FakeOpenMeteo::dailyFor)`. The
  constructor, `fixedDelay` and the `CURRENT_AC6` default are unchanged.
- `support.FavouriteRows`: reused as is.

Traps carried forward and new ones (developer and tester must read these):
- **Read-timeout trap (AC-20, carried forward from weather-for-all-favourites)**:
  `FakeOpenMeteo.RealProviderInitializer` writes `weather.provider.read-timeout=1s` (and
  `connect-timeout=1s`) into the inlined-properties source. That **overrides** a class-level
  `@TestPropertySource` value for the same key. The test report confirmed this: all deadline
  tests failed on the guard with `1s`. So in `FavouritesForecastDeadlineIT`:
  - set `weather.provider.read-timeout=5s` through `@DynamicPropertySource`, never through
    `@TestPropertySource`;
  - in `@BeforeEach`, assert `environment.getProperty("weather.provider.read-timeout")` is `"5s"`,
    `WeatherProviderProperties#readTimeout()` is 5 s, and `FavouritesWeatherProperties#timeout()`
    is 1 s and `maxConcurrency()` is 4;
  - add a visible guard test, `@DisplayName("AC-20 (guard): …")`.

  Without the guard, a 1 s read timeout would end Tokyo's 3 s `DAILY` call by itself, and the test
  would pass without proving the deadline.
- **`InFlightResponder` defaults to the `CURRENT_AC6` body.** For `DAILY` routes, use
  `InFlightResponder.daily(...)` (or `.respond(FakeOpenMeteo::dailyFor)`). Otherwise every item
  fails mapping as `UPSTREAM_UNAVAILABLE` and the concurrency tests measure the wrong thing.
- **Mockito and the `int` parameter**: stub and verify `forecastAtLocation(…, any(), anyInt())`.
  `any()` for `days` returns null and throws `NullPointerException` on unboxing. Stubbing
  `forecast(...)` has no effect on this endpoint (ADR 0015 Consequences).
- **Distinct WARN text**: filter log lines on `Favourites forecast partial failure`. The weather
  endpoint's ITs filter on `Favourites weather partial failure`, so neither picks up the other.
- **Warm-up before timing (AC-19, AC-20)**: send one request for a throw-away client with one
  directly inserted fast row at a unique coordinate, then `deleteRunRedisKeys()` and
  `fake.reset()`. Then lazy initialisation (DispatcherServlet, `HttpClient`, Lettuce) is not
  measured and the warm-up's request is not counted.
- **Late handlers (AC-20)**: after asserting the response, `responder.awaitIdle(5s)` before
  counting `DAILY` requests and before the test ends, so a late handler doesn't leak into the
  next test's `fake.reset()`. Assert the elapsed time is < 2 s and ≥ 900 ms, and that the body
  captured at the response is the one asserted. For "no call started after the deadline": 6 rows,
  4 slow latitudes delayed 3 s with `gate(4)`, 2 fast ones; exactly the 4 slow latitudes ever
  reach the fake, all 6 items are `UPSTREAM_UNAVAILABLE`, and `gateTimedOut()` is false.
- **AC-19 bounds**: the lower bound (≥ 2 in flight) is the `gate(2)` latch (5 s cap): a serial
  implementation times out on it and records max 1. The upper bound (≤ 4) is structural, because
  the responder decrements before the response is written. `< 4 s` has about 3 s of headroom
  over the ideal ~1 s.
- **AC-10 ordering**: the four bullets depend on each other's cache state. Run them in one test
  method, with `fake.reset()` between the steps, and use a fresh client and `deleteRunRedisKeys()`
  for the reverse-direction bullet.
- **AC-9 setup**: create London through the API first (1 `GEOCODING`), insert Paris and Tokyo
  with `FavouriteRows.insert`, **then** `fake.reset()`, then request. Compare `latitude`/`longitude`
  with `BigDecimal.valueOf(stored).toPlainString()`, not with the literal text.
- No test relies on thread scheduling order. Per-item behaviour is keyed on `latitude`, and items
  are compared by id.

## Implementation plan
1. `feat(provider): add WeatherProvider#forecastAtLocation`: the default method, the stub override
   (with the `atCoordinates`/`firstDays` extraction), the Open-Meteo override (with the shared
   private `forecast(Session, …)` step and `checkDays`), plus
   `WeatherProviderForecastAtLocationDefaultTest`, `StubWeatherProviderForecastLocationTest` and
   `OpenMeteoWeatherProviderForecastLocationTest`. `./gradlew build` passes, with every existing
   provider and caching test unchanged.
2. `feat(weather): WeatherService#forecastAt(Location, Units, LocalDate, int)`: extract
   `convert(LocationForecast, Units)`, add `WeatherServiceForecastAtTest`.
3. `feat(favourites): FavouriteForecastService and FavouriteForecast`, with
   `FavouriteForecastServiceTest` (including AC-13) and `FavouriteForecastTest`. Javadoc notes on
   `FavouriteWeatherFailure`, `FavouriteWeatherConfiguration` and `FavouritesWeatherProperties`.
4. `feat(api): GET /api/v1/favourites/forecast`: `FavouritesForecastController`,
   `FavouritesForecastQuery`, `FavouriteForecastListResponse`, `FavouriteForecastResponse`, the
   Javadoc note on `FavouriteWeatherErrorDto`, plus the signature, query parity and JSON tests.
5. `docs(api): document /api/v1/favourites/forecast in openapi-v1.json`, the `FavouriteId`
   description and the `application.yml` comment, with `FavouritesForecastOpenApiDocumentTest`
   and `FavouritesForecastConfigFilesTest`. Run `OpenApiDriftIT` and `RealProviderOpenApiDriftIT`.
6. `test: DAILY support in FakeOpenMeteo and InFlightResponder` (`dailyFor`, `daily(...)`). Run the
   existing `FavouritesWeather*IT` and cache ITs to confirm that existing callers are unaffected.
7. `test: stub-path ITs`: `FavouritesForecastIT`, `FavouritesForecastLoggingIT`,
   `FavouritesForecastContractConformanceIT`, `FavouritesForecastDbFailureIT`.
8. `test: real-provider ITs`: `FavouritesForecastRealProviderIT`, `FavouritesForecastDeadlineIT`,
   `FavouritesForecastRedisDownIT`.
9. Run `scripts/test-env.sh all`, then `scripts/test-env.sh it` a second time. Confirm that
   `git diff main -- src/main/resources/db/migration/ .env.example compose.yaml` is empty and that
   `git diff --name-status main -- src/test src/integrationTest` shows only additions, except the
   two additive support files (`FakeOpenMeteo`, `InFlightResponder`).

## Risks / open questions
- **A-4 (human decision)**: `200` with all items failed vs `503`. The design follows ADR 0014.
- **A-5 (human decision)**: stored coordinates vs re-geocoding. The design follows it (ADR 0015).
- **A-6 (human decision, confirm sharing)**: shared `weather.favourites.weather.*` settings and
  fan-out bean (ADR 0016). Splitting later is additive.
- **A-3**: reusing `FavouriteWeatherError` (description "Why an item has no weather") for
  forecasts. It is referenced, not copied (ADR 0016 §3).
- **Duplicated assembly loop**: `FavouriteForecastService` repeats ~20 lines of
  `FavouriteWeatherService` (fill timed-out slots, count, WARN). Extracting a shared helper would
  change a released class, so it is deferred to a third aggregate endpoint (rule of three). The
  mechanics that matter (limit, deadline, MDC, cancellation, fail-fast) are already shared in
  `BoundedFanOut`.
- **Stub location asymmetry**: the stub's `forecastAtLocation` returns the passed-in location, and
  its `currentAtLocation` returns its fixed one (ADR 0015 Consequences). They differ only for
  directly inserted rows with stub coordinates under another name (tests only).
- **Quota (A-7, A-8)**: up to 20 `DAILY` calls per cold request per `days` value and UTC date. No
  in-request de-duplication of identical coordinates. A UI that loads `/favourites/weather` and
  `/favourites/forecast` together can have 2 × `max-concurrency` calls in flight for one client.
  There is no global rate limiting (out of scope, would need its own ADR).
- **Lingering workers after the deadline**: an interrupted `DAILY` call may log `Upstream call
  failed` (or `Cache operation failed … reason=unavailable`) after the response. This happens only
  on the timeout path. AC-12 and AC-24 are success paths.
- **Postgres headroom (NFR)**: four new `@DirtiesContext` classes keep the peak at about
  today's level (≈ 86/100). Raising `max_connections` is a separate chore.
- **Thread names**: forecast workers are named `fav-weather-<n>`. This is cosmetic (ADR 0016).
