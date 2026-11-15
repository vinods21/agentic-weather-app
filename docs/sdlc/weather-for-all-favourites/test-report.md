# Weather for all favourites: Test report
Branch: feature/weather-for-all-favourites @ e928de9 (code under test; tests added on top in the `test:` commits) · Date: 2026-11-15 · Env: docker compose (postgres:17, redis:7), Windows 11 host, JDK 21

ITs are in `src/integrationTest/java/com/example/weather/favourites/` (all `FavouritesWeather*IT`) and
run against the running compose Postgres (schema `it_<run-id>`) and Redis (prefix `it:<run-id>`). No
Testcontainers, no container is started or stopped from test code, no new dependency and no migration.
Upstream is the stub provider (`it` profile, `@MockitoSpyBean WeatherProvider`) or the in-process
`FakeOpenMeteo`. New test support: `support.FavouriteRows` (direct inserts) and
`support.InFlightResponder` (thread-safe `CURRENT` responder that counts in-flight calls, with a latch
gate, per-request delays keyed on `latitude`, `awaitCompleted` and `awaitIdle`).

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `FavouritesWeatherIT#ac1_happyPath`, `#ac1_edge_observedAtIsTheRequestInstant`; `FavouriteWeatherServiceTest#happyPath` | integration + unit | PASS |
| AC-2 | `FavouritesWeatherIT#ac2_storedOrder` (stub reorder); `FavouritesWeatherRealProviderIT#ac2_realReverseCompletion` (responses complete P, L, T, verified); `BoundedFanOutTest` | integration + unit | PASS |
| AC-3 | `FavouritesWeatherIT#ac3_units`, `#ac3_invalidUnits` ×3 (`IMPERIAL`, `kelvin`, empty; compared with `/weather/current`); `FavouritesWeatherQueryTest`; `FavouritesWeatherQueryParityTest` (new, 10 cases) | integration + unit | PASS |
| AC-4 | `FavouritesWeatherIT#ac4_emptyList`; `FavouriteWeatherServiceTest#emptyList` | integration + unit | PASS |
| AC-5 | `FavouritesWeatherIT#ac5_invalidClientId` ×5, `#ac5_invalidClientIdAndUnits` ×5, `#ac5_sixtyFourChars`; `FavouritesWeatherControllerSignatureTest` | integration + unit | PASS |
| AC-6 | `FavouritesWeatherIT#ac6_isolation` | integration | PASS |
| AC-7 | `FavouritesWeatherRealProviderIT#ac7_ac8_ac9_storedCoordinatesAndCache` (3 `CURRENT` with exactly `51.50853/-0.12574`, `48.8566/2.3522`, `35.6762/139.6503`; 0 `GEOCODING`; 0 `DAILY`) | integration | PASS |
| AC-8 | `FavouritesWeatherRealProviderIT#ac7_ac8_ac9_storedCoordinatesAndCache` (warm: 0 requests, same `observedAt`; then `/weather/current` 0 `CURRENT`), `#ac8_reverseDirection` | integration | PASS |
| AC-9 | `FavouritesWeatherRealProviderIT#ac7_ac8_ac9_storedCoordinatesAndCache` (new keys = exactly the 3 `CacheKeys.current` keys, PTTL in (0, 600000], no client id/label/city; only `current`/`geocoding` kinds) | integration | PASS |
| AC-10 | `FavouritesWeatherRedisDownIT#ac10_failOpen` | integration | PASS |
| AC-11 | `FavouritesWeatherIT#ac11_oneItemFails`, `#ac11_edge_failedItemKeepsLabel` (stub); `FavouritesWeatherLoggingIT#ac11_partialFailureWarn` (one WARN `failed=1 total=3 timedOut=0` with `it-faw-11`, no city/label/client id/coordinates); `FavouritesWeatherRealProviderIT#ac11_ac13_realPartialFailure` (adapter `Upstream call failed` line carries `[requestId=it-faw-11]`) | integration | PASS |
| AC-12 | `FavouritesWeatherIT#ac12_allItemsFail` (stub); `FavouritesWeatherRealProviderIT#ac12_realAllFail` | integration | PASS |
| AC-13 | `FavouritesWeatherRealProviderIT#ac11_ac13_realPartialFailure` (no Paris `current` key; next request exactly 1 `CURRENT`, `latitude=48.8566`) | integration | PASS |
| AC-14 | `FavouritesWeatherIT#ac14_notFoundFromProvider`, `#ac14_notFoundForUnknownStoredLocation` (row at 0.0/0.0); `FavouriteWeatherEdgeCasesTest` (NaN coordinates, city not logged) | integration + unit | PASS |
| AC-15 | `FavouritesWeatherIT#ac15_unexpectedFailureIs500`; `FavouritesWeatherDbFailureIT#ac15_databaseFailureIs500`; `FavouritesWeatherContractConformanceIT#ac19_edge_internalErrorConforms`; `FavouriteWeatherServiceTest`, `BoundedFanOutTest` | integration + unit | PASS |
| AC-16 | `FavouritesWeatherRealProviderIT#ac16_boundedParallelism` (gate of 2 proves ≥ 2, counter proves ≤ 4, 20 `CURRENT`, < 4 s after a warm-up); `BoundedFanOutTest`; `FavouriteWeatherEdgeCasesTest#serialFanOut` | integration + unit | PASS |
| AC-17 | `FavouritesWeatherDeadlineIT#ac17_deadline`, `#ac17_noCallStartedAfterDeadline`, `#ac17_guardEffectiveConfiguration` (plus a `@BeforeEach` guard on the effective read timeout 5 s); `BoundedFanOutTest`; `FavouriteWeatherServiceTest#timedOutItem` | integration + unit | PASS |
| AC-18 | `FavouritesWeatherPropertiesTest`, `FavouritesWeatherConfigFilesTest` (developer); effective binding checked in `FavouritesWeatherDeadlineIT` guard | unit (+ integration guard) | PASS |
| AC-19 | `FavouritesWeatherContractConformanceIT` (5 tests: AC-1, AC-4, AC-11 mixed, AC-12 all-error 200 bodies, AC-3/AC-5 400 bodies, 500 body); existing `OpenApiDriftIT`, `RealProviderOpenApiDriftIT`; `FavouritesWeatherOpenApiDocumentTest`; `FavouriteWeatherEdgeCasesTest#exactlyOneOfWeatherOrFailure` | integration + unit | PASS |
| AC-20 | `FavouritesWeatherIT#ac20_routing`, `#ac20_edge_otherMethods` (PATCH/DELETE → 400 field `id`, PUT → 405) | integration | PASS |
| AC-21 | `FavouritesWeatherLoggingIT#ac21_successLogging`; `FavouriteWeatherEdgeCasesTest#toStringRedactsClientIdAndLabel` | integration + unit | PASS |
| AC-22 | existing `FavouritesSchemaIT`, `MigrationFilesTest` (unmodified); `git diff origin/main -- src/main/resources/db/migration/` is empty | integration + unit + check | PASS |
| AC-23 | `FavouritesWeatherIT#ac1_happyPath`, `#ac11_oneItemFails`, `#ac12_allItemsFail` (row snapshot incl. `updated_at`/`position` unchanged), `#ac23_noConnectionHeldDuringProviderCalls` (no tx, 0 active Hikari connections during each provider call); `FavouriteWeatherServiceTest#readOnly` | integration + unit | PASS |
| AC-24 | full existing suite: all unit tests and ITs pass; no existing test file changed except `WeatherServiceTest` (developer, extended) | unit + integration | PASS |

## Summary
- Unit (`./gradlew test`): **576 passed / 0 failed** (559 existing incl. the developer's, + 17 new:
  `FavouriteWeatherEdgeCasesTest` 7, `FavouritesWeatherQueryParityTest` 10).
- Integration (`scripts/test-env.sh it`): **441 passed / 0 failed** (394 existing + 47 new across 7
  classes: `FavouritesWeatherIT` 29, `FavouritesWeatherContractConformanceIT` 5,
  `FavouritesWeatherRealProviderIT` 6, `FavouritesWeatherDeadlineIT` 3, `FavouritesWeatherLoggingIT` 2,
  `FavouritesWeatherDbFailureIT` 1, `FavouritesWeatherRedisDownIT` 1).
- Full IT suite run 3 times: run 1 had 2 failures in an existing class caused by my new ITs exhausting
  Postgres connections (test issue 1 below, fixed). Runs 2 and 3 after the fix: 441/441.
- Repeatability on this Windows host: `FavouritesWeatherDeadlineIT`, `FavouritesWeatherRealProviderIT`,
  `FavouritesWeatherRedisDownIT` and `FavouritesWeatherLoggingIT` (12 tests) were run **5 times in a
  row**: 5/5 green, 0 flakes. Counting the targeted first run and the 2 green full runs, every
  timing-sensitive test passed **8 out of 8** times. Per-test durations across the 5 repeats (JUnit
  time, including warm-up and the post-response `awaitIdle`):

  | Test | min | max | bound asserted |
  |------|-----|-----|----------------|
  | AC-16 `ac16_boundedParallelism` (warm-up + 20×200 ms in waves of 4) | 1.21 s | 1.55 s | request < 4 s, max in flight in [2, 4] |
  | AC-17 `ac17_deadline` (incl. ~2 s `awaitIdle` for the 3 s Tokyo handler) | 3.09 s | 3.13 s | response in [0.9 s, 2 s) |
  | AC-17 `ac17_noCallStartedAfterDeadline` | 5.15 s | 6.98 s | response < 2 s; only 4 `CURRENT` |
  | AC-2 `ac2_realReverseCompletion` | 0.49 s | 0.59 s | completion order P, L, T (latch-driven) |
  | AC-10 `ac10_failOpen` | 0.23 s | 0.38 s | n/a |

## Failures / defects found
### Product bugs
None. No AC failed because of production code.

### Test issues (found and fixed in the tests)
1. **Postgres `too many clients` on the first full IT run.** It failed 2 tests in an *existing* class
   (`UpstreamUnreachableIT`, context load: `FATAL: sorry, too many clients already`). Postgres
   has `max_connections = 100`, and Spring's context cache keeps every context's Hikari pool open. The
   four new classes with their own configuration (`FavouritesWeatherDbFailureIT`,
   `FavouritesWeatherRealProviderIT`, `FavouritesWeatherDeadlineIT`, `FavouritesWeatherRedisDownIT`)
   pushed the run over the limit. Fix: `@DirtiesContext` on those four classes (no other class shares
   their configuration), so their contexts close after the class. After that the full suite passes.
   On the next full run, peak `client backend` connections sampled from `pg_stat_activity` was **86 of
   100**, so the suite has little headroom (see risks).
2. **Read-timeout trap (architect's note) confirmed.** With `weather.provider.read-timeout=5s` in a
   class-level `@TestPropertySource`, the effective value is `1s`, because
   `FakeOpenMeteo.RealProviderInitializer` writes `1s` into the same inlined property source. All 3
   `FavouritesWeatherDeadlineIT` tests then fail on the guard (`expected "5s" but was "1s"`). The
   committed test sets it via `@DynamicPropertySource` and asserts both the environment value and the
   bound `WeatherProviderProperties.readTimeout()` before every test, so AC-17 can't pass through a
   1 s read timeout.

## Not covered / risks
- **AC-18 startup failure** is covered by the developer's unit tests (`Binder`-based), not by an IT that
  starts a context with invalid values.
- **AC-17 "late result may be cached"** is not asserted (it's optional). The test does assert that the
  response was built at the deadline, that no call started after it (6 items / 4 workers: only the 4
  in-flight latitudes ever reach the fake) and that the late handler finished (`awaitIdle`) before the
  next test.
- **AC-10** asserts that every WARN/ERROR line in the request's captured slice is a
  `Cache operation failed` line carrying the request id. It doesn't check the exact count (it depends
  on Lettuce reconnect timing).
- **Connection headroom:** the IT suite now runs close to Postgres `max_connections` (see test issue 1).
  New ITs that bring their own context should use `@DirtiesContext`, or the compose
  `max_connections` / per-context pool sizes should be revisited (chore, outside this feature).
- **Lingering workers after the deadline** (design risk) log `Upstream call failed` after the response
  on the timeout path; this is expected and not asserted against.
- Timing bounds (AC-16 < 4 s, AC-17 < 2 s and ≥ 0.9 s) are wall-clock assertions with ≥ 1 s margin. Lower
  bounds of concurrency are latch-based, not sleep-based.
