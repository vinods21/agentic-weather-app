# Redis caching of weather provider responses: Test report
Branch: feature/redis-weather-caching @ f1df6c6 · Date: 2026-11-15 · Env: docker compose (postgres:17, redis:7), Windows 11 host, JDK 21+ via Gradle wrapper

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `WeatherCachePropertiesTest` (defaults, binding), `CacheConfigFilesTest` (application.yml / .env.example / compose.yaml), `RedisTimeoutConfigIT#ac1_redisTimeoutsAreBounded` | unit + integration | PASS |
| AC-2 | `WeatherCachePropertiesTest` (4 TTLs x `0s`/`-5m`/`abc`, message names property/env var) | unit | PASS |
| AC-3 | `StubNotCachedIT#ac3_stubIsNeverCached` | integration | PASS |
| AC-4 | `CacheDisabledIT#ac4_disabledCacheCallsUpstreamEveryTime`, `RedisWeatherCacheTest` (disabled cache never touches a store) | integration + unit | PASS |
| AC-5 | `CacheHitIT#ac5_repeatedCurrentIsCacheHit`, `OpenMeteoWeatherProviderCachingTest`, `OpenMeteoWeatherProviderCachingEdgeCaseTest#geocodingHitForOtherSpelling` | integration + unit | PASS |
| AC-6 | `CacheHitIT#ac6_unitsAreNotPartOfTheKey`, `CacheKeysTest` | integration + unit | PASS |
| AC-7 | `CacheHitIT#ac7_caseAndPaddingShareOneEntry`, `#ac7_whitespaceRunsAndUnicodeFormsShareOneEntry`, `#ac7_accentsAreNotStripped`, `CacheKeysTest` | integration + unit | PASS |
| AC-8 | `CacheHitIT#ac8_geocodingSharedBetweenEndpoints`, `OpenMeteoWeatherProviderCachingTest` | integration + unit | PASS |
| AC-9 | `CacheHitIT#ac9_forecastKeyedByStartDateAndDays` (MutableClock), `OpenMeteoWeatherProviderCachingEdgeCaseTest#invalidDaysTouchesNothing` | integration + unit | PASS |
| AC-10 | `CacheKeysIT#ac10_rawInputNeverFormsKeyStructure`, `CacheKeysTest` | integration + unit | PASS |
| AC-11 | `CacheHitIT#ac11_nonAsciiAndNegativeCoordinatesRoundTrip`, `CacheCodecTest`, `RedisWeatherCacheTest` | integration + unit | PASS |
| AC-12 | `CacheKeysIT#ac12_everyKeyHasItsTtl` (PTTL ranges), `RedisWeatherCacheTest` | integration + unit | PASS |
| AC-13 | `CacheExpiryIT#ac13_currentEntryExpires`, `#ac13_forecastEntryExpires` | integration (timing) | PASS |
| AC-14 | `NegativeCacheIT#ac14_notFoundIsCachedAndEchoesTheRequestCity`, `#ac14_missingCountryCodeIsCachedAsNotFound`, `#ac14_notFoundIsPerCity`, `OpenMeteoWeatherProviderCachingTest` | integration + unit | PASS |
| AC-15 | `CacheExpiryIT#ac15_notFoundEntryExpires` | integration (timing) | PASS |
| AC-16 | `CacheUpstreamFailureIT#ac16_geocodingFailureIsNotCached` (HTTP 500, delay past read timeout, invalid JSON) | integration | PASS |
| AC-17 | `CacheUpstreamFailureIT#ac17_currentFailureKeepsGeocodingOnly`, `#ac17_dailyFailureKeepsGeocodingOnly` (500, short days array) | integration | PASS |
| AC-18 | `RedisUnreachableIT#ac18_startupAndReadinessIndependentOfCacheRedis` | integration | PASS |
| AC-19 | `RedisUnreachableIT#ac19_ac20_failOpenWhenRedisUnreachable`, `RedisWeatherCacheEdgeCaseTest#bypassIsPerSession`, `OpenMeteoWeatherProviderCachingEdgeCaseTest#redisDownMakesFullUpstreamCalls` | integration (timing) + unit | PASS |
| AC-20 | `RedisUnreachableIT#ac19_ac20_failOpenWhenRedisUnreachable`, `RedisWeatherCacheTest`, `RedisWeatherCacheEdgeCaseTest#exceptionMessageWithKeyIsNotLogged` | integration + unit | PASS |
| AC-21 | `RedisSilentIT#ac21_silentRedisIsBoundedByTimeouts`, `RedisWeatherCacheEdgeCaseTest#lettuceTimeoutOnGetTripsShortCircuit` | integration (timing) + unit | PASS |
| AC-22 | `CacheStoreFailureIT#ac22_failedReadIsMiss`, `#ac22_failedWriteIsIgnored`, `#ac22_failedReadsOnForecast`, `RedisWeatherCacheEdgeCaseTest#otherErrorReplyIsUnavailable`, `#programmingErrorPropagates` | integration + unit | PASS |
| AC-23 | `CacheUnreadableEntryIT#ac23_unreadableEntryIsRewritten` (3 kinds x `not-json`/`{}`/`"v":0`/hash = 12 cases), `CacheCodecTest`, `RedisWeatherCacheTest`, `RedisWeatherCacheEdgeCaseTest#unreadableDoesNotBypass`, `#bareWrongTypeIsUnreadable` | integration + unit | PASS |
| AC-24 | `CacheHitIT#ac24_observedAtIsFetchTime`, `#ac24_freshFetchUsesNewClockTime`, `WeatherServiceTest`, `OpenMeteoWeatherProviderCachingTest` | integration + unit | PASS |
| AC-25 | `CacheHitIT#ac25_cacheHitAddsNoHeaders`; existing `ContractConformanceIT`, `OpenApiDriftIT`, `RealProviderContractConformanceIT`, `RealProviderOpenApiDriftIT` and all other existing ITs/unit tests; `git diff origin/main -- src/main/resources/openapi/` is empty | integration + check | PASS |

## Summary
- Unit (`./gradlew test`): **398 passed / 0 failed** (0 skipped). 12 of them are new tester edge cases
  (`RedisWeatherCacheEdgeCaseTest` 7, `OpenMeteoWeatherProviderCachingEdgeCaseTest` 5).
- Integration (`scripts/test-env.sh it`): **234 passed / 0 failed** (0 skipped). 46 of them are new in
  `com.example.weather.cache` (the developer's run had 188). Existing ITs all pass with caching enabled.
- New IT support (`src/integrationTest/.../support`): `MutableClock`, `CachingIntegrationTestBase`
  (TTLs set explicitly, shared MutableClock via `@TestBean`, keys cleared before each test),
  `IsolatedCacheStore` (own Lettuce connection using Boot's `RedisProperties` timeouts, registered only
  as a `@Primary CacheStore`), `SilentRedisServer` (in-process `ServerSocket`, accepts, never answers),
  `RunRedisKeys` (SCAN of `cacheKeys.pattern()`).
- No Testcontainers, no container start/stop from tests, no new dependencies. All keys are built with
  `CacheKeys` under the per-run `it:<id>` prefix and are removed by `IntegrationTestBase` after each test.

## Failures / defects found
**Product bugs: none.** Every AC passes against the developer's implementation as committed (969fa16).

Test issues, found and fixed in my own tests during the run (no production code changed):
- `RedisUnreachableIT` / `RedisSilentIT`: a nested `@TestConfiguration` was **not** auto-detected,
  because the configuration lookup stops at `RealProviderIntegrationTestBase`'s `@ContextConfiguration`.
  The cache silently used the compose Redis, so AC-19/AC-21 initially failed for the wrong reason.
  Fixed with an explicit `@Import(...)`. `RedisUnreachableIT#ac18` now also asserts that the injected
  `CacheStore` is the `IsolatedCacheStore` (guard against the same regression).
- `RedisUnreachableIT`: the WARN-format regex was applied with a full-string match, but the line has a
  timestamp prefix. Switched to `containsPattern`.

Observed values (this host):
- AC-19, refused loopback connect: requests took 49 / 23 / 15 / 13 ms (server-side). Windows didn't
  show the 1–2 s SYN retry delay that design.md warns about. One WARN per request
  (`op=get cache=geocoding reason=unavailable ... cause=java.net.ConnectException`), as designed.
- AC-21, silent server with 250 ms timeouts: 334 ms server-side (limit 3 s), and one WARN.
- AC-23: Spring Data surfaced the hash-typed key's `WRONGTYPE` reply in a form the cache
  classifies as `reason=unreadable`. The subsequent `SET ... PX` overwrote the hash with a string.

## Not covered / risks
- **Timing-sensitive tests**:
  - `RedisUnreachableIT` (AC-19: < 2 s per request) and `RedisSilentIT` (AC-21: < 3 s) depend on
    loopback connect behaviour. On Windows, a refused connect can take 1–2 s if SYNs are retried; it is
    bounded by `connect-timeout` 500 ms (250 ms in AC-21). This host showed large margins. On a slow
    CI host, consider setting `spring.data.redis.connect-timeout=250ms` in `RedisUnreachableIT`, as
    design.md allows.
  - `CacheExpiryIT` (AC-13, AC-15) uses real Redis expiry: 1 s TTLs and `Thread.sleep(1500)`. Its
    "still a hit" checks before the sleep assume the two back-to-back requests finish within 1 s.
  - `CacheUpstreamFailureIT` (AC-16 timeout case) uses a 1.6 s fake delay against the 1 s read timeout.
- **AC-1 IT and the environment**: `RedisTimeoutConfigIT` expects 500 ms. Gradle exports
  `.env`/`.env.example` into ITs, so a developer who sets `REDIS_TIMEOUT`/`REDIS_CONNECT_TIMEOUT` in a
  local `.env` will see this test fail. The cache TTL ITs are immune because they set their values
  explicitly (A-13).
- **AC-18 scope**: only the cache's own connection is broken (design A-10). The case where Boot's
  auto-configured factory itself is down isn't tested, since that would break `IntegrationTestBase`
  cleanup. Readiness independence relies on Boot's default readiness group.
- **AC-2** is verified at Binder level (unit), not by starting a full context with a bad TTL.
- **AC-19 bodies** are compared field by field (AC-6 values, 404/503 code and detail, `observedAt` =
  the clock). They are not byte-compared against a separate uncached run.
- **Untested by design / out of scope**: concurrent misses (no stampede protection, A-11),
  `OpenMeteoWeatherProvider#current(city)` caching conditions (it doesn't, and the API never calls it).
  Redis auth/TLS is also out of scope.
- Test contexts: the new ITs add about 7 Spring contexts (caching base, expiry, spy, unreachable, silent,
  disabled, stub-with-explicit-enabled). Each keeps Hikari at 3 connections. The full IT run took about
  1 minute.
