# Architecture overview

Maintained by the `architect` agent: update it whenever a feature changes the structure.

## System
agentic-weather-app is a Spring Boot 3 (Java 21) HTTP service.

```
UI / client ──HTTP /api/v1──▶ app (Spring Boot :8080)
                               ├─ api        RequestIdFilter → controllers → ApiExceptionHandler (Problem Details)
                               ├─ weather    WeatherService (units, clock)
                               ├─ provider   WeatherProvider port (one bean, chosen by weather.provider.type)
                               │               ├─ OpenMeteoWeatherProvider (default) ──HTTPS──▶ Open-Meteo geocoding + forecast
                               │               │     └─ WeatherCache (geocoding / current / forecast entries, fail open)
                               │               └─ StubWeatherProvider (opt-in: WEATHER_PROVIDER=stub; fixed in profile `it`; never cached)
                               ├──JDBC──▶ PostgreSQL 17   (system of record; schema via Flyway)
                               └──RESP──▶ Redis 7         (cache of upstream results; keys built by CacheKeys, always with a TTL)
```
PostgreSQL is wired up but not used by any feature yet. Redis caches Open-Meteo results
(ADR 0007). Within the TTLs, a repeated request makes 0 upstream calls, and a cache miss makes
at most 2 (ADR 0006). Redis is optional at runtime: when it is down or holds bad data, the
service behaves as if uncached.

## Code layout
| Path | Purpose |
|------|---------|
| `src/main/java/com/example/weather/` | Application code (`WeatherApplication`) |
| `.../api/` | Cross-cutting `/api/**` web concerns: `RequestIdFilter`, `RequestIds`, `ApiCorsProperties`, `ApiWebConfig` (CORS) |
| `.../api/error/` | `ErrorCode` (closed set), `ProblemFactory`, global `ApiExceptionHandler` (only acts on `/api/`) |
| `.../api/v1/` | v1 controllers (`WeatherController`, `OpenApiController`), query records, `dto/` response records |
| `.../weather/` | Domain types (`Units`, `ConditionCode`, records), `WeatherService`, `UnitConverter`, `Clock` bean |
| `.../provider/` | `WeatherProvider` port, its exceptions, `WeatherProviderProperties`, `WeatherProviderConfiguration` (selects the one bean), and `stub/StubWeatherProvider` |
| `.../provider/openmeteo/` | Open-Meteo adapter: `OpenMeteoWeatherProvider` (HTTP, sanitised WARN logging), `OpenMeteoMapper`, `OpenMeteoWeatherCodes` |
| `.../cache/` | `CacheKeys` (namespaced, validated Redis keys under `weather.cache.key-prefix`), `WeatherCacheProperties`, `WeatherCache` + `RedisWeatherCache` (typed entries, fail open), `CacheStore`/`RedisCacheStore` (Redis access), `CacheCodec` (versioned JSON), `WeatherCacheConfiguration` |
| `src/main/resources/openapi/openapi-v1.json` | Hand-written OpenAPI 3.0.3 contract served at `/api/v1/openapi.json` |
| `src/main/resources/db/migration/` | Flyway migrations `V<n>__*.sql` |
| `src/test/` | Unit tests (no Docker) |
| `src/integrationTest/` | Integration tests against compose services (profile `it`) |

## HTTP API conventions (ADR 0003)
- Path versioning `/api/v1`, additive-only within a version.
- Errors: RFC 9457 `application/problem+json` with `code`, `requestId` and `errors` (validation only).
- `X-Request-Id` on every `/api/` response. It is in the MDC as `requestId`, with one INFO access-log line per request.
- CORS allow-list `WEATHER_API_CORS_ALLOWED_ORIGINS`, deny-by-default.
- Spec-first OpenAPI with drift and conformance tests (ADR 0002).

## Runtime environment
`compose.yaml` (see ADR 0001): `postgres`, `redis`, plus `app` under the `app` profile.
Health is exposed at `/actuator/health` (readiness at `/actuator/health/readiness`). Readiness
does not depend on the weather upstream.

Weather provider configuration (ADR 0005, 0006): `WEATHER_PROVIDER` (`open-meteo` default | `stub`),
`WEATHER_PROVIDER_GEOCODING_BASE_URL`, `WEATHER_PROVIDER_FORECAST_BASE_URL`,
`WEATHER_PROVIDER_CONNECT_TIMEOUT` (2s), `WEATHER_PROVIDER_READ_TIMEOUT` (3s). With the default,
the app needs outbound HTTPS. Integration tests never reach the internet: profile `it` pins the
stub, and real-adapter ITs use the in-process fake `support.FakeOpenMeteo` (JDK `HttpServer`).

Cache configuration (ADR 0007): `WEATHER_CACHE_ENABLED` (true), `WEATHER_CACHE_GEOCODING_TTL` (7d),
`WEATHER_CACHE_NOT_FOUND_TTL` (1h), `WEATHER_CACHE_CURRENT_TTL` (10m), `WEATHER_CACHE_FORECAST_TTL` (1h),
`WEATHER_CACHE_KEY_PREFIX` (weather), and the global Lettuce timeouts `REDIS_TIMEOUT` and
`REDIS_CONNECT_TIMEOUT` (500ms each). Readiness does not depend on Redis. The `/actuator/health`
aggregate includes Boot's Redis indicator.

## Caching conventions (ADR 0007)
- Explicit `WeatherCache` component called by the adapter, not `@Cacheable`. Values are
  versioned JSON (`"v":1`) mapped to the app's own records and validated on read. No default
  typing, no Java serialisation.
- Keys `<prefix>:<kind>:v1:<parts>` contain only `[A-Za-z0-9._-]` parts. User input is only
  ever SHA-256-hashed into a key. Every write is `SET … PX <ttl>`.
- Fail open: a Redis or decoding failure gives a miss or a skipped write, plus one WARN
  `Cache operation failed op=… cache=… reason=unavailable|unreadable exception=… cause=…`.
  The line never contains a city, key or value. After the first Redis failure, a provider call
  bypasses the cache.
- ITs break only the cache's connection: a test `@Primary CacheStore` on a closed or silent
  local port, or `@MockitoSpyBean CacheStore`. No containers are stopped (ADR 0001).

## Outbound integration conventions (ADR 0006)
- `RestClient` on the JDK `HttpClient`, with connect and read timeouts. No retries, no redirects, 1 MiB body cap.
- Any I/O error, non-2xx status or malformed body → `WeatherProviderUnavailableException` (no cause,
  sanitised message) → 503 `UPSTREAM_UNAVAILABLE`.
- One WARN line per failed call (call name, host, path, status or exception class, elapsed ms). Never
  query strings, bodies, exception messages or credentials.

## Features
| Feature | Components | ADRs | Docs |
|---------|------------|------|------|
| UI-backend API contracts | `api`, `api.error`, `api.v1`, `weather`, `provider` (stub) | 0002, 0003, 0004 | [docs/sdlc/ui-backend-api-contracts/](../sdlc/ui-backend-api-contracts/) |
| Real weather provider | `provider`, `provider.openmeteo`, `provider.stub` (opt-in) | 0005, 0006 | [docs/sdlc/real-weather-provider/](../sdlc/real-weather-provider/) |
| Redis weather caching | `cache`, `provider.openmeteo`, `provider` (`WeatherProvider#currentAt`), `weather` (`ObservedConditions`) | 0007 | [docs/sdlc/redis-weather-caching/](../sdlc/redis-weather-caching/) |

## ADRs
- [0001](../adr/0001-integration-tests-use-docker-compose.md) Integration tests use the Docker Compose services, not Testcontainers
- [0002](../adr/0002-spec-first-hand-written-openapi.md) Spec-first, hand-written OpenAPI document verified by tests
- [0003](../adr/0003-http-api-conventions.md) HTTP API conventions: path versioning, Problem Details errors, request ids, CORS allow-list
- [0004](../adr/0004-weather-provider-port-with-stub-default.md) WeatherProvider port with a deterministic in-process stub as the default adapter (Superseded by 0005)
- [0005](../adr/0005-real-weather-provider-default-stub-opt-in.md) Open-Meteo adapter is the default WeatherProvider; the stub is opt-in
- [0006](../adr/0006-outbound-http-to-weather-upstream.md) Outbound HTTP to the weather upstream: RestClient on the JDK HttpClient, bounded timeouts, no retries, sanitised logging, in-process fake for ITs
- [0007](../adr/0007-redis-cache-for-upstream-results-fail-open.md) Redis cache for upstream weather results: typed JSON entries on StringRedisTemplate, per-kind TTLs, fail open, bounded Redis timeouts
