# 0007. Redis cache for upstream weather results: typed JSON entries on StringRedisTemplate, per-kind TTLs, fail open, bounded Redis timeouts
Date: 2026-11-15 · Status: Accepted · Feature: redis-weather-caching

## Context
Each `/api/v1/weather/*` request with the Open-Meteo adapter makes 2 upstream calls: geocoding,
then forecast (ADR 0006). The free tier allows about 10,000 calls/day. Redis is already in the
stack (`spring-boot-starter-data-redis`, `CacheKeys`, `weather.cache.key-prefix`), but nothing
uses it. The `redis-weather-caching` requirements ask for these entries:
- geocoding results, including "not found";
- current conditions per location;
- daily forecasts per location, start date and day count.

The `/api/v1` contract must not change. The cache must fail open: a Redis outage or a bad entry
must never cause a 5xx, a startup failure or a readiness failure. Values may only be
deserialised into the app's own types. Log lines may not contain cities, keys or values. No new
dependency is allowed. Integration tests must simulate Redis failures without stopping
containers (ADR 0001).

This is the first cross-cutting use of Redis, so later caches will copy these choices: the
abstraction, key scheme, serialisation, failure semantics, Redis client timeouts and the way
tests break Redis.

## Decision
1. **No Spring Cache abstraction (`@Cacheable`)**. The cache is an explicit component,
   `cache.WeatherCache`, which the Open-Meteo adapter calls. Its implementation
   `RedisWeatherCache` sits on a small store port `cache.CacheStore` (`get(key)`,
   `set(key, value, ttl)`). The production store `RedisCacheStore` uses Boot's auto-configured
   `StringRedisTemplate`. `GET` is a plain read and `SET ... PX` writes the value and its TTL
   in one command, so a key without a TTL can't exist.
2. **Caching sits inside the Open-Meteo adapter, never around the port.** The adapter already
   separates geocoding from the forecast call, and that is where the geocoding entry can be
   shared by both endpoints. `StubWeatherProvider` is never cached. With
   `weather.cache.enabled=false` the adapter gets `WeatherCache.disabled()`, a no-op that never
   talks to Redis.
3. **Time stays with the service (refines ADR 0005 §1, does not supersede it).** The port
   gains `default ObservedConditions currentAt(String city, Instant now)`. By default it
   returns `current(city)` with `observedAt = now`. `WeatherService` passes its clock time,
   truncated to seconds, and uses the returned `observedAt`. The Open-Meteo adapter overrides
   the method: a fresh fetch stores `now` with the entry, and a cache hit returns the stored
   instant. Adapters still never read the clock.
4. **Keys** are always built by `CacheKeys`. Every part must match `[A-Za-z0-9._-]+`, which is
   checked for every key:
   - `<prefix>:geocoding:v1:<sha256-hex of the normalised city>`
   - `<prefix>:current:v1:<lat>:<lon>`
   - `<prefix>:forecast:v1:<lat>:<lon>:<yyyy-MM-dd>:<days>`

   City normalisation: NFC, strip, collapse runs of Unicode whitespace to one space,
   `toLowerCase(Locale.ROOT)`, then NFC again. Coordinates use the same
   `BigDecimal.toPlainString()` text that is sent upstream. Units never appear in a key.
5. **Values** are versioned JSON (`"v":1`) written with a dedicated `ObjectMapper`. They map to
   package-private DTO records that hold only strings, numbers, booleans and lists. Default
   typing is off and Java serialisation is not used. Every read is validated: version, required
   fields, enum names, ranges, and the number and dates of the forecast days. A value that
   fails validation is "unreadable" and counts as a miss.
6. **TTLs per kind** come from `weather.cache.{geocoding,not-found,current,forecast}-ttl`
   (defaults 7d / 1h / 10m / 1h). Startup fails if a TTL is not positive. Only
   `LocationNotFoundException` is cached negatively. Upstream failures are never cached.
7. **Fail open, with a narrow catch set.**
   - Redis operations: `DataAccessException` and `io.lettuce.core.RedisException`.
   - Decoding: `JacksonException` and the internal `UnreadableCacheEntryException`.

   A failed read is a miss, and a failed write is skipped. A Redis `WRONGTYPE` error and any
   decode failure count as `reason=unreadable`. All other caught failures count as
   `reason=unavailable`. Any other exception is a bug and is not caught.
8. **Per-call short circuit.** Each adapter call opens a `WeatherCache.Session`. After the first
   `unavailable` failure, the session skips its remaining cache operations, which are logged
   at DEBUG only. With Redis down, a request therefore pays at most one Redis timeout instead
   of four. An `unreadable` failure does not trip the short circuit, so the entry can be
   rewritten.
9. **Logging**: one WARN per failed operation:
   `Cache operation failed op=<get|put> cache=<geocoding|current|forecast> reason=<unavailable|unreadable> exception=<class> cause=<root class>`.
   The request id comes from the MDC log pattern (ADR 0003). The line never carries a
   throwable, message, city, key or value. Hits and misses are logged at DEBUG only.
10. **Redis client timeouts**: `spring.data.redis.timeout=${REDIS_TIMEOUT:500ms}` and
    `spring.data.redis.connect-timeout=${REDIS_CONNECT_TIMEOUT:500ms}`. These are Boot's global
    Lettuce settings, so Lettuce's 60 s default never applies. They also bound the Redis
    health indicator. Readiness keeps Boot's default group, so it does not depend on Redis.
11. **Tests break only the cache's Redis connection.** Integration tests that need Redis to be
    unreachable or silent add a `@Primary CacheStore`, built in the test on a private
    `LettuceConnectionFactory` that is not registered as a bean. It points at a closed local
    port, or at an in-process `ServerSocket` that accepts connections and never replies. It
    uses the timeouts from Boot's `RedisProperties` bean. Read and write failures against a
    healthy Redis are simulated with `@MockitoSpyBean CacheStore`. The context's
    `StringRedisTemplate` keeps pointing at the compose Redis, so `IntegrationTestBase`
    cleanup and key assertions keep working. No container is started or stopped (ADR 0001).

## Consequences
- Within the TTLs, a repeated request makes 0 upstream calls, a first request for a city that
  is already geocoded makes 1, and an unknown city makes 1 per not-found TTL. With Redis down,
  a request is at most about 1 x 500 ms slower than without a cache.
- Cached current weather can be up to `current-ttl` old, and `observedAt` says when it was
  fetched. A newly added place stays "not found" for up to `not-found-ttl`.
- Anyone who can write to Redis can inject plausible weather values. Validation limits a
  tampered entry to a well-formed value or a miss. Redis auth/TLS stays out of scope.
- A future format change bumps `v1` → `v2` in both the key and the value. Old entries are never
  read and expire by TTL.
- The global Redis timeouts also apply to any future Redis use. A feature that needs longer
  commands must revisit decision 10.
- The cache has no stampede protection: concurrent misses for the same key each call the
  upstream, and the last write wins.
- More Spring test contexts are created (TTL variants, unreachable Redis), each with its own
  small connection pools.

## Alternatives considered
- **Spring Cache (`@EnableCaching`, `RedisCacheManager`, `@Cacheable`)**: it is already on the
  classpath and has per-cache TTLs and a `CacheErrorHandler`. Rejected for these reasons:
  - It can't cache an exception, so "not found" would need a sentinel return type.
  - It needs proxies, so `geocode` would have to move into another bean.
  - Its `GenericJackson2JsonRedisSerializer` relies on polymorphic default typing, which the
    security requirement forbids. Typed serializers per cache would remove most of the benefit.
  - A per-request short circuit and the stored `observedAt` don't fit `@Cacheable`.
- **A caching decorator around `WeatherProvider`**: the port only exposes `current(city)` and
  `forecast(city, …)`, so it could not cache geocoding separately or share it between the
  endpoints.
- **Caching raw upstream JSON**: every hit would have to be mapped and validated again, and the
  cache would hold upstream fields the app doesn't use.
- **URL-safe Base64 of the city in keys**: reversible, so cities would be readable in `KEYS`
  output, and the key length grows with the input. SHA-256 is fixed-length, and a collision
  is negligible.
- **A separate production connection factory for the cache (`weather.cache.redis.*`)**: it would
  isolate the cache's timeouts, but it adds configuration and a second client only to make
  tests easier. The test-only `@Primary CacheStore` gives the same isolation.
- **Pointing the whole context at a dead Redis in tests**: this breaks
  `IntegrationTestBase` cleanup and the key assertions, which use the context's template.
- **Lettuce `REJECT_COMMANDS` while disconnected / a circuit breaker**: replacing Boot's
  `ClientOptions` would drop its connect-timeout setting, and a circuit breaker is out of
  scope. The per-call short circuit plus timeouts are enough.
