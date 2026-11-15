## Summary
Caches Open-Meteo results in Redis to cut upstream calls. Today each API request makes 2 upstream
calls, against a free-tier quota of about 10k calls a day.

The Open-Meteo adapter checks an explicit `WeatherCache` before calling the upstream:
- **Geocoding** is cached for 7 days and shared by both endpoints.
- **"Not found"** is cached for 1 hour.
- **Current weather** (10 minutes) and **forecasts** (1 hour) are cached as metric data.
  Imperial values are still converted locally, so units are never part of a key.
- **Upstream failures (503) are never cached.**
- **The stub is never cached.**
- Caching can be switched off with `WEATHER_CACHE_ENABLED=false`.

Keys are built only by new typed methods on `CacheKeys`, and the city part is a SHA-256 of the
normalised name. Every write carries a TTL (`SET … PX`). Values are versioned JSON with strict
validation, so a corrupt, empty, old-format or wrong-type entry counts as a miss and is overwritten.

The cache fails open. If Redis is unreachable or slow, requests go to the upstream with the same
200/404/503 responses and never a 500, and each failed operation logs one sanitised WARN. After the
first Redis failure, the rest of that request skips the cache. Redis command and connect timeouts
default to 500 ms.

The `/api/v1` contract and `openapi-v1.json` are unchanged. There are no new dependencies and no
migrations. Integration tests use the Docker Compose Redis and the existing fake Open-Meteo. The
Redis-down tests break only the cache's own `CacheStore`, pointing it at a closed port or at an
in-process socket that accepts connections and never answers. No containers are touched and there
is no Testcontainers.

## Docs
- Requirements: [requirements.md](docs/sdlc/redis-weather-caching/requirements.md)
- Design: [design.md](docs/sdlc/redis-weather-caching/design.md)
- Test report: [test-report.md](docs/sdlc/redis-weather-caching/test-report.md)
- Status and deviations: [status.md](docs/sdlc/redis-weather-caching/status.md)
- ADR: [0007 Redis cache for upstream results, fail open](docs/adr/0007-redis-cache-for-upstream-results-fail-open.md)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Configuration
- [x] AC-1: TTLs, the enabled switch and the Redis timeouts are bound from env-var placeholders
  with defaults, and are documented in `.env.example` and `compose.yaml`.
- [x] AC-2: a TTL of `0s`, a negative value or a non-duration fails startup, naming the property.
- [x] AC-3: the stub provider is never cached, and no keys are written.
- [x] AC-4: `weather.cache.enabled=false` behaves exactly like having no cache (every request
  calls the upstream).

Hits and keys
- [x] AC-5: a repeated current-weather request makes 0 upstream calls and returns the same body.
- [x] AC-6: units are not part of the key; imperial values are converted from cached metric data.
- [x] AC-7: city case and whitespace map to one geocoding entry.
- [x] AC-8: the geocoding entry is shared between current weather and forecast.
- [x] AC-9: forecast keys include the UTC start date and `days`.
- [x] AC-10: key encoding keeps separator and glob characters in a city from colliding or
  escaping the prefix.
- [x] AC-11: non-ASCII names and negative coordinates round-trip exactly.

TTLs and expiry
- [x] AC-12: every key has a TTL within its configured bound.
- [x] AC-13: when a current entry expires, the next request calls the upstream again
  (geocoding is still cached).
- [x] AC-14: "not found" is cached across spellings, and the 404 `detail` shows the city as sent.
- [x] AC-15: when a not-found entry expires, a now-known city resolves.

Failures are never cached
- [x] AC-16: a geocoding 500, timeout or invalid JSON returns 503 and caches nothing.
- [x] AC-17: a current or forecast failure after successful geocoding caches only geocoding.

Redis down: fail open
- [x] AC-18: the app starts and is ready with the cache's Redis unreachable.
- [x] AC-19: with Redis unreachable, responses are 200/404/503 as without a cache (never 500),
  each in under 2 s.
- [x] AC-20: each failed cache operation logs one sanitised WARN (request id, operation, cache,
  reason) with no city, key, value or stack trace.
- [x] AC-21: a Redis that accepts connections but never answers still gives a 200 in under 3 s.
- [x] AC-22: a cache read or write failure on a reachable Redis still serves from the upstream.
- [x] AC-23: an unreadable entry (not JSON, `{}`, an old version or the wrong Redis type) is a
  miss and is overwritten.

Semantics and regression
- [x] AC-24: on a cache hit, `observedAt` is the time the data was fetched from the upstream.
- [x] AC-25: the OpenAPI drift and conformance ITs pass with the stub and with the real provider,
  a hit adds no headers, and `openapi-v1.json` is unchanged.

## Needs a human decision
- **A-1, TTL defaults:** geocoding 7d, not-found 1h, current 10m, forecast 1h. Cached current
  weather can be up to 10 minutes old, on top of Open-Meteo's own 15-minute update interval.
- **A-8, `observedAt` meaning:** on a hit, `observedAt` is the fetch time, not the response time.
  The format is unchanged.
- **A-6, cached "not found":** a newly added place can stay "not found" for up to 1 hour.
- **A-3, key normalisation:** keys are trimmed, lower-cased, NFC-normalised and have whitespace
  runs collapsed. This assumes Open-Meteo geocoding treats those spellings the same.
- **A-5, `days` in forecast keys:** `days=3` and `days=5` are cached and fetched separately.
- **A-12, Redis timeouts:** 500 ms for commands and connects. Previously a hung Redis could block
  a request for up to Lettuce's 60 s default.
- **Testing limit:** "Redis down" is tested by breaking only the cache's own connection. Spring
  Boot's main Redis connection stays up so that test cleanup works, so readiness without Redis
  relies on Boot's default readiness group.
- **Local `.env`:** `RedisTimeoutConfigIT` fails if your `.env` sets `REDIS_TIMEOUT` or
  `REDIS_CONNECT_TIMEOUT`, because Gradle passes `.env` into the ITs.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 398 unit tests and 234 integration tests, 0 failures, 0 skipped (compose Redis and
Postgres, fake upstream, no Testcontainers).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
