# Forecast for all favourites: Test report
Branch: feature/forecast-for-all-favourites @ aee937b · Date: 2026-11-16 · Env: docker compose (postgres:17, redis:7)

Tests run on the host (JDK 21, Gradle wrapper). ITs use the already running compose Postgres (schema
`it_<run-id>`) and Redis (prefix `it:<run-id>`), the stub provider or the in-process `FakeOpenMeteo`.
No Testcontainers, no test starts or stops a container, no new dependency, no migration.

New IT classes (all in `src/integrationTest/java/com/example/weather/favourites/`):

| Class | Context | Tests |
|---|---|---|
| `FavouritesForecastIT` | shares `FavouritesIntegrationTestBase` (no extra config) | 54 |
| `FavouritesForecastLoggingIT` | shares it (`OutputCaptureExtension` only) | 3 |
| `FavouritesForecastContractConformanceIT` | shares it | 6 |
| `FavouritesForecastDbFailureIT` | own (`@MockitoSpyBean FavouriteRepository`), `@DirtiesContext` | 1 |
| `FavouritesForecastRealProviderIT` | own (`CachingIntegrationTestBase` + properties), `@DirtiesContext` | 8 |
| `FavouritesForecastDeadlineIT` | own (timeout 1s, `@DynamicPropertySource` read-timeout 5s), `@DirtiesContext` | 3 |
| `FavouritesForecastRedisDownIT` | own (`@Import` closed-port `CacheStore`), `@DirtiesContext` | 1 |

New unit class: `src/test/java/com/example/weather/api/v1/FavouritesForecastQueryEdgeParityTest` (28
tests). The developer's 11 unit classes are listed as `*Test` below and were not duplicated.

Additive test support changes (design step 6): `support.FakeOpenMeteo.dailyFor(RecordedRequest)` (the
default `DAILY` branch moved into a public method, so the default response is unchanged) and
`support.InFlightResponder.daily(Function<RecordedRequest, Duration>)` (`respond(FakeOpenMeteo::dailyFor)`).
Every existing caller behaves the same, and all existing ITs pass.

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `FavouritesForecastIT#ac1_happyPath`; `FavouriteForecastServiceTest#happyPath`, `StubWeatherProviderForecastLocationTest`, `WeatherServiceForecastAtTest`, `FavouriteForecastResponseJsonTest` | integration + unit | PASS |
| AC-2 | `FavouritesForecastIT#ac2_storedOrder` (stub reorder); `FavouritesForecastRealProviderIT#ac2_realReverseCompletion` (latch-driven reverse completion P, L, T) | integration | PASS |
| AC-3 | `FavouritesForecastIT#ac3_days` (1/3/7), `#ac3_defaultDays` (absent, `days=`), `#ac3_normalisedDays` (`+3`, `%2B3`, `03`, `%203%20`); `FavouritesForecastQueryParityTest`, `FavouritesForecastQueryEdgeParityTest` | integration + unit | PASS |
| AC-4 | `FavouritesForecastIT#ac4_units`; `WeatherServiceForecastAtTest` | integration + unit | PASS |
| AC-5 | `FavouritesForecastIT#ac5_validationParity` (10 query strings vs `/weather/forecast?city=London&<qs>`, errors compared as sets, `verifyNoInteractions`), `#ac5_exactMessages`, `#ac5_unknownParametersIgnored`; `FavouritesForecastQueryParityTest`, `FavouritesForecastQueryEdgeParityTest` | integration + unit | PASS |
| AC-6 | `FavouritesForecastIT#ac6_emptyList` (3 queries); `FavouriteForecastServiceTest#emptyList` | integration + unit | PASS |
| AC-7 | `FavouritesForecastIT#ac7_invalidClientId`, `#ac7_invalidClientIdAndQuery` (5 ids each), `#ac7_sixtyFourChars`; `FavouritesForecastControllerSignatureTest` | integration + unit | PASS |
| AC-8 | `FavouritesForecastIT#ac8_isolation` | integration | PASS |
| AC-9 | `FavouritesForecastRealProviderIT#ac9_ac11_storedCoordinatesAndKeys`; `OpenMeteoWeatherProviderForecastLocationTest` | integration + unit | PASS |
| AC-10 | `FavouritesForecastRealProviderIT#ac10_warmCacheSharedBothWays` (all four bullets, in order); `OpenMeteoWeatherProviderForecastLocationTest` | integration + unit | PASS |
| AC-11 | `FavouritesForecastRealProviderIT#ac9_ac11_storedCoordinatesAndKeys` (key diff = exactly 3 `forecast` keys, shape `<prefix>:forecast:v1:<lat>:<lon>:2026-11-15:5`, PTTL in (0, 3600000], no client id/label/city) | integration | PASS |
| AC-12 | `FavouritesForecastRedisDownIT#ac12_failOpen` | integration | PASS |
| AC-13 | `FavouriteForecastServiceTest` (AC-13: clock crossing UTC midnight, maxConcurrency 1 and 4) | unit | PASS |
| AC-14 | `FavouritesForecastIT#ac14_oneItemFails`, `#ac14_edge_failedItemKeepsLabel`; `FavouritesForecastLoggingIT#ac14_partialFailureWarn`, `#ac14_edge_distinctFromWeatherWarn`; `FavouritesForecastRealProviderIT#ac14_ac16_realPartialFailure` (adapter WARN carries `it-ffc-14`), `#ac14_edge_malformedDailyBody`; `FavouriteForecastServiceTest`, `FavouriteForecastTest` | integration + unit | PASS |
| AC-15 | `FavouritesForecastIT#ac15_allItemsFail`; `FavouritesForecastRealProviderIT#ac15_realAllFail`; `FavouriteForecastServiceTest` | integration + unit | PASS |
| AC-16 | `FavouritesForecastRealProviderIT#ac14_ac16_realPartialFailure` (no Paris key; the repeat makes exactly 1 `DAILY`, for Paris); `OpenMeteoWeatherProviderForecastLocationTest` | integration + unit | PASS |
| AC-17 | `FavouritesForecastIT#ac17_notFoundFromProvider`, `#ac17_notFoundForUnknownStoredLocation` (row at 0.0/0.0), `#ac17_edge_knownNameOtherCoordinates`; `WeatherProviderForecastAtLocationDefaultTest`, `StubWeatherProviderForecastLocationTest` | integration + unit | PASS |
| AC-18 | `FavouritesForecastIT#ac18_unexpectedFailureIs500`; `FavouritesForecastDbFailureIT#ac18_databaseFailureIs500`; `FavouriteForecastServiceTest` | integration + unit | PASS |
| AC-19 | `FavouritesForecastRealProviderIT#ac19_boundedParallelism` (warm-up, `InFlightResponder.daily(200 ms).gate(2)`, 20 `DAILY`, max in flight in [2, 4], < 4 s) | integration | PASS |
| AC-20 | `FavouritesForecastDeadlineIT#ac20_guardEffectiveConfiguration` (guard: read-timeout 5s, timeout 1s, max-concurrency 4, also asserted in `@BeforeEach`), `#ac20_deadline` (< 2 s and >= 900 ms, Tokyo `UPSTREAM_UNAVAILABLE`, `timedOut=1`), `#ac20_noCallStartedAfterDeadline` (6 items, 4 workers blocked, only the 4 slow latitudes reach the fake); `FavouriteForecastServiceTest` | integration + unit | PASS |
| AC-21 | `FavouritesForecastConfigFilesTest`; `FavouritesForecastDeadlineIT` / `FavouritesForecastRealProviderIT` set only the shared properties; `git diff origin/main -- .env.example compose.yaml` is empty; the `application.yml` diff is comment-only | unit + integration + manual | PASS |
| AC-22 | `FavouritesForecastContractConformanceIT` (6 tests: AC-1, days=7, imperial, empty, mixed, all-error bodies vs `FavouriteForecastList` + exactly-one-of check; AC-5/AC-7 400s and AC-18 500 vs `Problem`; only 200/400/500 declared); `FavouritesForecastOpenApiDocumentTest`; existing `OpenApiDriftIT`, `RealProviderOpenApiDriftIT` | integration + unit | PASS |
| AC-23 | `FavouritesForecastIT#ac23_routing` (POST/PUT 405 with `Allow: GET`), `#ac23_patchAndDeleteHitId`, `#ac23_neighboursUnchanged` | integration | PASS |
| AC-24 | `FavouritesForecastLoggingIT#ac24_successLogging` | integration | PASS |
| AC-25 | existing `FavouritesSchemaIT`, `MigrationFilesTest` (unmodified); `git diff origin/main -- src/main/resources/db/migration/` is empty (still only V1 and V2) | integration + unit + manual | PASS |
| AC-26 | `FavouritesForecastIT#ac1_happyPath`, `#ac14_oneItemFails`, `#ac15_allItemsFail`, `#ac26_rowsUnchangedOnMixedOutcome` (row snapshots, every column); `#ac26_noConnectionHeldDuringProviderCalls` (`tx=false active=0` on all 3 calls); `FavouriteForecastServiceTest` | integration + unit | PASS |
| AC-27 | the entire existing suite (unit + IT) passes unmodified; `FavouritesForecastRealProviderIT#ac27_quotaRegression`; `git diff --name-status origin/main -- src/test src/integrationTest` shows only additions plus the two additive support files | integration + unit + manual | PASS |

## Summary
- Unit (`./gradlew test`): **750 passed / 0 failed** (722 existing, including the developer's 11 new classes, plus 28 new in `FavouritesForecastQueryEdgeParityTest`).
- Integration (`scripts/test-env.sh it`): **517 passed / 0 failed** (441 existing + 76 new), on two
  consecutive full runs (runs 3 and 4 below).
- All 27 ACs PASS. No product defect found.

Full IT runs:

| Run | Result | `too many clients` | Peak `client backend` (pg_stat_activity, ~1.5 s sampling) | Notes |
|---|---|---|---|---|
| 1 | 517 / 0 failed | 0 | 92 / 100 (single sample, about 1 s before JVM shutdown; the next highest was 86) | |
| 2 | 516 / 1 failed | 0 | 86 / 100 | Environmental, see below |
| 3 | 517 / 0 failed | 0 | **89 / 100** | consecutive green run 1 |
| 4 | 517 / 0 failed | 0 | **87 / 100** | consecutive green run 2 |

Where the peak comes from: in every run the peak is in the last seconds of the run (provider-package
ITs plus the run cleanup), while the 18 cached Spring contexts (pools 1-15, 20, 24-26 in run 3) are all
still open. Of the 26 pools in a run, 8 are `@DirtiesContext` contexts closed after their class: the 4
existing `FavouritesWeather*` ones and the 4 new `FavouritesForecast*` ones. The three new stub-path
classes reuse the existing `FavouritesIntegrationTestBase` context. **So this feature adds no cached
context, and at most one transient pool (3-4 connections) runs at a time.** The level matches the 86
recorded by weather-for-all-favourites; the 87-92 spread is sampling at the end of the run. I did not
change any context configuration, because the new classes are already at the NFR minimum.

Flake reruns: `FavouritesForecastDeadlineIT`, `FavouritesForecastRealProviderIT`,
`FavouritesForecastLoggingIT` and `FavouritesForecastRedisDownIT` (15 tests, all timing-, latch- or
log-capture-sensitive) ran **5 times in a row: 75/75 passed, 0 flakes**. Each was also green in the
four full runs.

## Failures / defects found
**Product bugs: none.** No production code was changed.

**Test issues / environment:**
- Full run 2 had one failure in the **existing** `FavouritesWeatherRealProviderIT#ac11_ac13_realPartialFailure`:
  `RedisConnectionFailureException: Unable to connect to Redis`. The run's wall time was 10 h 41 min:
  the host went to sleep during the run, and Docker's port forwarding dropped the Redis connection.
  Postgres connections peaked at 86 in that run, and no `too many clients` occurred. The same test
  passed in runs 1, 3 and 4. I count this as an environment failure, not a defect or a flaky test, and
  replaced the run with two new consecutive full runs (3 and 4), both green.
- My new tests needed no fixes: they passed on the first run. The design's traps were applied from
  the start: read-timeout through `@DynamicPropertySource` plus a guard, `InFlightResponder.daily(...)`
  for `DAILY`, `anyInt()` for `days`, filtering on `Favourites forecast partial failure`, warm-up
  before timed tests, and `awaitIdle` for late handlers (also in `@AfterEach` of the deadline IT).

## Not covered / risks
- **Postgres headroom**: peaks of 86-92 of 100 leave little room. The next feature that adds a
  *cached* context, or a run where two dirtied contexts overlap, could hit `too many clients`.
  Raising `max_connections` or shrinking per-context pools is out of scope (separate chore,
  requirements "Out of scope").
- **AC-13** is covered only at unit level (a moving clock). The IT clock is fixed, so midnight crossing
  is not exercised end-to-end.
- **AC-20 "late result may be cached"** is not asserted either way: it is optional behaviour.
- **AC-12** asserts that every WARN/ERROR in the request slice is a `Cache operation failed` line
  carrying the request id. It does not require such lines to appear ("may appear").
- **AC-24** checks the request's own log slice plus the whole captured output for the secrets. Lines
  written by other JVM threads outside the capture are not covered.
- The `+3` case relies on the servlet container decoding `+` as a space (` 3` → 3); `%2B3` (a literal
  plus) is tested as well, and both match `/weather/forecast`.
- Timing ITs have >= 1 s margin (AC-19: < 4 s vs ~1 s ideal; AC-20: < 2 s vs 1 s deadline). A heavily
  loaded or suspended host can still break them, as run 2 shows for an existing Redis-dependent IT.
