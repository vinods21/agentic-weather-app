## Summary
Adds `GET /api/v1/favourites/weather`, which returns current weather for every saved favourite of
the calling client (`X-Client-Id`), in the stored order, as `{"items": [...]}`. Each item has the
favourite's `id`, `city`, optional `label` and stored `location`, plus either `weather` (the
unchanged `CurrentWeather` schema, equal to `/weather/current` for the same city and units) or a
per-item `error {code, detail}`.

**Lookup:** weather is fetched by each favourite's stored coordinates through a new
`WeatherProvider#currentAtLocation` (ADR 0012, amends 0011). It does no geocoding and shares the
existing `current` Redis cache entry with `/weather/current`.

**Concurrency:** calls run in parallel through a new `BoundedFanOut` (ADR 0013):
- at most `max-concurrency` (default 4) calls on virtual threads, results kept in input order;
- an overall deadline (default 5s), after which no new upstream call starts and pending items
  become `UPSTREAM_UNAVAILABLE`;
- the request id (MDC) is propagated to the workers.

**Failures:** ADR 0014 (amends 0003) sets the per-item error convention. The response is always
200, even when every item fails, and one WARN line per request carries only the counts. Bugs and
database failures stay 500.

There is no migration, no new dependency, no database write and no transaction held during
provider calls. The OpenAPI spec adds the path and three schemas; existing paths and schemas are
unchanged.

## Docs
- Requirements: [requirements.md](docs/sdlc/weather-for-all-favourites/requirements.md)
- Design: [design.md](docs/sdlc/weather-for-all-favourites/design.md)
- Test report: [test-report.md](docs/sdlc/weather-for-all-favourites/test-report.md)
- Status and deviations: [status.md](docs/sdlc/weather-for-all-favourites/status.md)
- ADRs:
  - [0012 WeatherProvider current weather at a stored location](docs/adr/0012-weather-provider-current-at-stored-location.md)
    (amends 0011)
  - [0013 Bounded parallel fan-out with deadline](docs/adr/0013-bounded-parallel-fan-out-with-deadline.md)
  - [0014 Per-item errors in aggregate read responses](docs/adr/0014-per-item-errors-in-aggregate-read-responses.md)
    (amends 0003)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Happy path
- [x] AC-1: returns 200 `{"items": [...]}` with id, city, label, location and weather per
  favourite.
- [x] AC-2: items follow the stored order, including after a reorder and when upstream replies
  arrive out of order.
- [x] AC-3: `units` behaves exactly like `/weather/current`, including invalid values.
- [x] AC-4: an empty list gives `{"items": []}` with no provider calls.

Client id
- [x] AC-5: the `X-Client-Id` rules are unchanged; an invalid id gives 400.
- [x] AC-6: a client sees only its own favourites.

Lookup, cache and quota
- [x] AC-7: on a cold cache, N favourites make N current calls at the stored coordinates and no
  geocoding or daily calls.
- [x] AC-8: on a warm cache there are no upstream calls, and the entry is shared with
  `/weather/current` in both directions.
- [x] AC-9: the existing `current` cache key is reused, with no new key kind.
- [x] AC-10: with Redis down, every item is still returned (fail open).

Partial failure
- [x] AC-11: one failing item gets `error UPSTREAM_UNAVAILABLE`, the request is 200, and one
  counts-only WARN carries the request id.
- [x] AC-12: when every item fails the request is still 200.
- [x] AC-13: failures are not cached.
- [x] AC-14: a per-item `LOCATION_NOT_FOUND` is reported for an unknown stored location.
- [x] AC-15: an unexpected exception or a database failure gives 500 for the whole request.

Concurrency and deadline
- [x] AC-16: at most `max-concurrency` calls run at once; 20 items finish in under 4s.
- [x] AC-17: past the deadline, pending items become `UPSTREAM_UNAVAILABLE` and no new call
  starts.
- [x] AC-18: `max-concurrency` (1..20, default 4) and `timeout` (1s..30s, default 5s) are bound
  from env vars and range-checked.

Contract and regression
- [x] AC-19: the OpenAPI spec documents the endpoint, and the drift and conformance ITs pass.
- [x] AC-20: the `/favourites/weather` route wins over `/favourites/{id}`.
- [x] AC-21: client ids, labels and cities stay out of logs at INFO or above.
- [x] AC-22: no new migration; `FavouritesSchemaIT` and `MigrationFilesTest` pass unmodified.
- [x] AC-23: no writes to `favourite`, and no connection or transaction held during provider
  calls.
- [x] AC-24: all existing tests pass, and existing endpoints are unchanged.

## Needs a human decision
- **A-3, partial failure:** the response is always 200 with per-item errors, even when every item
  fails. The alternative is 503 when all items fail.
- **A-4, stored coordinates:** weather comes from the coordinates saved at creation, with no
  re-geocoding. If geocoding data changes later, the stored coordinates go stale.
- **A-5, limits:** concurrency 4 and deadline 5s, both configurable. Confirm the values.
- **A-6, quota:** one client polling every minute with 20 favourites could use about 2,900
  upstream calls a day against the roughly 10k/day free tier. Rate limiting is out of scope.
- **Concurrency limit:** the limit is per request; the global total is bounded only by Tomcat's
  request threads.
- **Postgres connection headroom:** the full IT suite now peaks at about 86 of Postgres's 100
  connections. The four new ITs with their own contexts use `@DirtiesContext` to stay under the
  limit. Future context-specific ITs should do the same, or pool size and `max_connections`
  need a chore.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 576 unit tests and 441 integration tests, 0 failures, 0 skipped (compose Postgres and
Redis, stub and fake Open-Meteo, no Testcontainers). The timing-sensitive ITs passed 8 of 8 runs
on Windows.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
