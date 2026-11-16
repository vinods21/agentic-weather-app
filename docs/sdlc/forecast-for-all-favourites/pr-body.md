## Summary
Adds `GET /api/v1/favourites/forecast`, the forecast counterpart of `/favourites/weather` (#7).
It returns a multi-day forecast for every saved favourite of the calling client (`X-Client-Id`),
in the stored order, as `{"items": [...]}`. Each item has the favourite's `id`, `city`, optional
`label` and stored `location`, plus either `forecast` (the unchanged `Forecast` schema) or a
per-item `error {code, detail}` (ADR 0014).

**Parameters:** `days` (1..7, default 5) and `units` validate exactly like `/weather/forecast`,
with all violations reported together. The validation reuses `@DaysParam` and
`DaysParamValidator`, and a parity unit test guards it.

**Lookup:** each forecast is fetched by the favourite's stored coordinates through a new
`WeatherProvider#forecastAtLocation` (ADR 0015, amends 0012). It does no geocoding and shares the
`forecast` Redis cache entry with `/weather/forecast` in both directions. The UTC start date is
read once per request.

**Concurrency:** the endpoint reuses the existing `BoundedFanOut` bean and the
`weather.favourites.weather.{max-concurrency,timeout}` settings (4 and 5s; ADR 0016). There are
no new settings or env vars, and each endpoint keeps its own WARN text.

There is no migration, no new dependency, no database write and no transaction held during
provider calls. No existing test file changed, apart from additive helpers in `FakeOpenMeteo` and
`InFlightResponder`. The OpenAPI spec adds the path and two schemas.

## Docs
- Requirements: [requirements.md](docs/sdlc/forecast-for-all-favourites/requirements.md)
- Design: [design.md](docs/sdlc/forecast-for-all-favourites/design.md)
- Test report: [test-report.md](docs/sdlc/forecast-for-all-favourites/test-report.md)
- Status and deviations: [status.md](docs/sdlc/forecast-for-all-favourites/status.md)
- ADRs:
  - [0015 WeatherProvider forecast at a stored location](docs/adr/0015-weather-provider-forecast-at-stored-location.md)
    (amends 0012)
  - [0016 Favourites aggregate endpoints share fan-out settings and item errors](docs/adr/0016-favourites-aggregate-endpoints-share-fan-out-settings-and-item-errors.md)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Happy path and parameters
- [x] AC-1: returns 200 `{"items": [...]}` with id, city, label, location and forecast per
  favourite.
- [x] AC-2: items follow the stored order, including after a reorder and when replies arrive out
  of order.
- [x] AC-3: `days` works for 1, 3 and 7, defaults to 5, treats an empty value as absent, and
  normalises forms like `+3`, `03` and ` 3 `.
- [x] AC-4: `units=imperial` converts every item exactly like `/weather/forecast`.
- [x] AC-5: validation status and errors match `/weather/forecast`; all violations are reported
  together, with no provider call, and unknown parameters are ignored.
- [x] AC-6: an empty list gives `{"items": []}` with no provider calls.

Client id
- [x] AC-7: the `X-Client-Id` rules are unchanged; an invalid id gives 400.
- [x] AC-8: a client sees only its own favourites.

Lookup, cache and quota
- [x] AC-9: on a cold cache, N favourites make N daily calls at the stored coordinates and no
  geocoding or current calls.
- [x] AC-10: on a warm cache there are no upstream calls, and the entry is shared with
  `/weather/forecast` in both directions.
- [x] AC-11: only `forecast:v1:<lat>:<lon>:<date>:<days>` keys are written, with a TTL and
  nothing private in them.
- [x] AC-12: with Redis down, every item is still returned (fail open).
- [x] AC-13: every item uses the same UTC start date, even across midnight.

Partial failure
- [x] AC-14: one failing item gets `error UPSTREAM_UNAVAILABLE`, the request is 200, and one
  forecast-specific WARN carries the request id.
- [x] AC-15: when every item fails the request is still 200.
- [x] AC-16: failures are not cached.
- [x] AC-17: a per-item `LOCATION_NOT_FOUND` is reported.
- [x] AC-18: an unexpected exception or a database failure gives 500.

Concurrency, deadline and configuration
- [x] AC-19: at most 4 calls run at once; 20 items finish in under 4s.
- [x] AC-20: past the 1s deadline, pending items become `UPSTREAM_UNAVAILABLE` and no new call
  starts.
- [x] AC-21: the endpoint uses the existing `weather.favourites.weather.*` settings, with no new
  properties or env vars.

Contract and regression
- [x] AC-22: the OpenAPI spec documents the endpoint, and the drift and conformance ITs pass.
- [x] AC-23: `/favourites/forecast` wins over `/favourites/{id}` (405 for POST/PUT, 400 for
  PATCH/DELETE as on `/order` and `/weather`).
- [x] AC-24: client ids, labels and cities stay out of logs at INFO or above.
- [x] AC-25: no new migration; `FavouritesSchemaIT` and `MigrationFilesTest` pass unmodified.
- [x] AC-26: no writes to `favourite`, and no connection or transaction held during provider
  calls.
- [x] AC-27: all existing tests pass, and no existing test file was modified (only additive test
  helpers).

## Needs a human decision
- **A-6, shared settings (ADR 0016):** forecast reuses `weather.favourites.weather.*`, so the
  property name is a little misleading and the two endpoints can't be tuned separately. The
  alternative is separate `weather.favourites.forecast.*` settings, which can be added later
  without breaking anything.
- **A-4, status:** the response is always 200 with per-item errors, even when every item fails.
- **A-5, stored coordinates:** forecasts use the coordinates saved at creation, which can go
  stale.
- **A-7, quota:** a cold request costs up to 20 daily calls. With the 1h TTL that is about 480
  calls a day per polling client. Each different `days` value and each new UTC date start cold.
- **Error schema wording:** forecast items reuse `FavouriteWeatherError`, whose description reads
  "Why an item has no weather".
- **Postgres connection headroom:** the full IT suite peaks at 86–92 of 100 connections. This
  feature adds no cached context, but a chore to lower the per-context pool size or raise
  `max_connections` is overdue.
- **Duplicated code:** about 20 lines of result assembly are repeated between
  `FavouriteForecastService` and `FavouriteWeatherService`.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 750 unit tests and 517 integration tests, 0 failures, 0 skipped (compose Postgres and
Redis, stub and fake Open-Meteo, no Testcontainers). The timing-sensitive ITs passed 75 of 75
reruns on Windows.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
