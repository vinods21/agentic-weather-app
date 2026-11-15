# redis-weather-caching: SDLC status
Request: Redis caching for weather provider responses
Branch: feature/redis-weather-caching · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-25)
- 2026-11-15 architect: design.md written; ADR 0007 (Redis cache, fail open, Redis timeouts); overview.md updated
- 2026-11-15 orchestrator: developer stopped by usage limit after 5 feat/refactor commits (design steps 1-5). Step 6 (IntegrationTestBase cleanup protected, RealProviderCurrentIT#ac7 setup) is uncommitted in the working tree; build/it not yet verified. Resume: developer continues from step 6, then tester.
- 2026-11-15 developer: design steps 1-6 implemented (5466f66..f856324); ./gradlew build green; scripts/test-env.sh it green (188 ITs, caching enabled); openapi-v1.json unchanged. Steps 7-8 (new AC-mapped ITs and their support classes) left to the tester.

## Deviations from design
- `OpenMeteoWeatherProviderTest` new cases live in a separate class `OpenMeteoWeatherProviderCachingTest` (same package) so the existing class is untouched.
- `RedisWeatherCache` also has a public 3-arg constructor `(CacheStore, CacheKeys, WeatherCacheProperties)` because `CacheCodec` is package-private and tests in other packages need to build it; the 4-arg constructor is package-private.
- `WeatherCache.disabled()` is backed by a package-private enum `DisabledWeatherCache`.
- `WeatherCacheProperties.defaults()` static factory added (used by unit tests).
- `CacheCodec` also disables Jackson scalar coercion and `ACCEPT_FLOAT_AS_INT` (so `"81"` or `81.5` for an int field is unreadable) and requires `windSpeed >= 0` (the mapper's invariant).
- Existing-test setup change not listed in the design: `WeatherProviderSelectionIT` (an `ApplicationContextRunner` with only `WeatherProviderConfiguration`) now registers `WeatherCache.disabled()` as a bean, since the provider factory takes a `WeatherCache`. No assertion changed (AC-25).
- Test-support classes from design step 6 (`MutableClock`, `CachingIntegrationTestBase`, `IsolatedCacheStore`, `SilentRedisServer`) were not added; they belong with the tester's step 7-8 ITs.
