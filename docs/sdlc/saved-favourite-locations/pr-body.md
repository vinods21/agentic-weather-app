## Summary
Adds saved favourite locations: a CRUD API under `/api/v1/favourites`, stored in Postgres through
a new Flyway migration, `V2__create_favourite_table.sql`.

**Ownership.** Favourites belong to an opaque, client-generated id sent in a required
`X-Client-Id` header. This is a partition key, not authentication (ADR 0009). Another client's
favourite looks exactly like a missing one (404).

**Creating a favourite:**
- the city is checked once against the weather provider through a new geocoding-only
  `WeatherProvider#locate` (ADR 0011), which reuses the Redis geocoding cache;
- the resolved location is stored as a snapshot;
- a duplicate (same client, same city after the shared `CityNames` normalisation) gives 409
  `FAVOURITE_ALREADY_EXISTS`;
- a full list gives 409 `FAVOURITES_LIMIT_REACHED`. The limit defaults to 20 and is set with
  `WEATHER_FAVOURITES_MAX_PER_CLIENT`.

List, get, rename (`PATCH`, label only), reorder (`PUT /favourites/order`) and delete never call
the provider.

**Persistence:** `JdbcClient` on the existing JDBC starter, a `UNIQUE (client_id, city_key)`
constraint and a per-client advisory lock, so concurrent duplicate creates give one 201 and one
409, never a 500 (ADR 0008).

**API conventions:** write endpoints follow ADR 0010, which amends 0003. Bodies are JSON only, an
unreadable body is 400 `VALIDATION_FAILED`, and create returns 201 with `Location`. CORS now
allows `POST`, `PUT`, `PATCH` and `DELETE`.

The hand-written OpenAPI spec documents every new operation and both new error codes. The
`/api/v1/weather/*` entries are byte-identical. There are no new dependencies, and V1 is
untouched.

## Docs
- Requirements: [requirements.md](docs/sdlc/saved-favourite-locations/requirements.md)
- Design: [design.md](docs/sdlc/saved-favourite-locations/design.md)
- Test report: [test-report.md](docs/sdlc/saved-favourite-locations/test-report.md)
- Status and deviations: [status.md](docs/sdlc/saved-favourite-locations/status.md)
- ADRs:
  - [0008 Favourites persistence: JdbcClient, unique constraint, advisory lock](docs/adr/0008-favourites-persistence-jdbcclient-unique-constraint-advisory-lock.md)
  - [0009 Client-scoped resources via X-Client-Id](docs/adr/0009-client-scoped-resources-via-x-client-id-header.md)
  - [0010 Write endpoint conventions and CORS methods](docs/adr/0010-write-endpoint-conventions-and-cors-methods.md)
    (amends 0003)
  - [0011 WeatherProvider location lookup](docs/adr/0011-weather-provider-location-lookup.md)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Schema
- [x] AC-1: exactly one new migration, `V2__*.sql`, with no schema name, V1 untouched, and a
  unique constraint on (client id, normalised city).

Create
- [x] AC-2: `POST /api/v1/favourites` returns 201 with `Location` and the stored favourite.
- [x] AC-3: the label is optional and is omitted when absent.
- [x] AC-4: invalid fields (blank or too-long city, too-long label, wrong types) give 400
  `VALIDATION_FAILED`.
- [x] AC-5: an empty, malformed or wrong-shape body gives 400 with field `body`.
- [x] AC-6: a non-JSON content type gives 415.
- [x] AC-7: an unknown city gives 404 `LOCATION_NOT_FOUND`.
- [x] AC-8: a provider outage gives 503 `UPSTREAM_UNAVAILABLE`, and nothing is stored.
- [x] AC-9: a duplicate after normalisation gives 409 `FAVOURITE_ALREADY_EXISTS`.
- [x] AC-10: duplicates are checked per client.
- [x] AC-11: favourites and the cache share one city normalisation (unit test).
- [x] AC-12: concurrent duplicate creates give one 201 and one 409, with one row and never a 500.
- [x] AC-13: creating past the limit gives 409 `FAVOURITES_LIMIT_REACHED`.
- [x] AC-14: `max-per-client` is bound from an env var (default 20, range 1..100), and a bad value
  fails startup.

Client id
- [x] AC-15: a missing or invalid `X-Client-Id` gives 400 on every operation.
- [x] AC-16: another client's favourite returns 404, the same as a missing one.

Read, rename, reorder, delete
- [x] AC-17: an empty list gives `{"items": []}`.
- [x] AC-18: the list is in the stored order.
- [x] AC-19: list, get, rename, reorder and delete work while the provider is down (no upstream
  calls).
- [x] AC-20: get by id returns 200; an unknown id gives 404 `NOT_FOUND`; a non-UUID gives 400.
- [x] AC-21: `PATCH` renames the label and updates `updatedAt`; `null` clears the label.
- [x] AC-22: `PATCH {}` (no `label` key) gives 400.
- [x] AC-23: reorder takes the full id list; a missing, repeated or unknown id gives 400.
- [x] AC-24: a new favourite goes to the end after a reorder.
- [x] AC-25: delete gives 204, and a second delete gives 404.

Contract, CORS, logging, regression, quota
- [x] AC-26: an unsupported method gives 405 with `Allow` set to exactly the supported methods.
- [x] AC-27: `ErrorCode` gains exactly the two new 409 codes, also in the OpenAPI enum.
- [x] AC-28: the OpenAPI spec documents every new operation, and the drift and conformance ITs
  pass with the stub and with the real provider.
- [x] AC-29: CORS preflight is allowed for the write methods from an allowed origin and denied by
  default.
- [x] AC-30: the client id and label never appear in logs at INFO or above.
- [x] AC-31: all existing tests pass, and `/api/v1/weather/*` is unchanged.
- [x] AC-32: with the real provider, create makes 1 geocoding call, 0 current and 0 daily calls,
  and shares the geocoding cache entry.

## Needs a human decision
- **A-1, ownership:** the opaque `X-Client-Id` header is **not security**. Anyone who knows or
  guesses an id can read and change that list. The alternative is a single global list; ADR 0009
  describes how to reverse it.
- **A-2, check on create:** creating a favourite needs the upstream. While Open-Meteo is down,
  nothing can be created, though everything else still works.
- **A-5, duplicate rule:** a duplicate is the same submitted text after normalisation, so
  `Londres` and `London` are different favourites.
- **A-6, limit:** 20 per client by default; exceeding it gives 409.
- **Beyond the ACs** (each cheap to drop):
  - `Location` is added to the CORS exposed headers.
  - pgjdbc `logServerErrorDetail=false` keeps row values out of logs.
  - Reorder takes at most 100 ids.
- **Accepted risk:** a JSON number for `city` (`{"city": 123}`) is coerced to the string `"123"`.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 496 unit tests and 394 integration tests, 0 failures, 0 skipped (compose Postgres and
Redis, no Testcontainers; no leftover `it_*` schemas or `it:*` keys).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
