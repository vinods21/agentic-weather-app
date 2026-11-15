# Real weather provider: Test report
Branch: feature/real-weather-provider @ 7ec19cf (code under test: 2b322b8) · Date: 2026-11-15 · Env: docker compose (postgres:17, redis:7), host JDK, Windows 11

All real-adapter ITs talk to `support.FakeOpenMeteo` (JDK `com.sun.net.httpserver.HttpServer`
on `127.0.0.1`, random port, one per JVM). No internet, no Testcontainers, no new dependencies.

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `WeatherProviderSelectionIT#ac1_defaultIsOpenMeteo`; `WeatherProviderPropertiesTest#defaults` (dev) | integration (context runner) + unit | PASS |
| AC-2 | `WeatherProviderSelectionIT#ac2_stubSelectedByEnv`; `StubSelectionIT#ac2_singleStubBean`, `#ac2_stubAnswersWithoutUpstream` | integration | PASS |
| AC-3 | `WeatherProviderSelectionIT#ac3_bogusTypeFailsStartup`, `#ac3_edge_emptyTypeFailsStartup`, `#ac3_edge_upperCaseTypeFailsStartup`; `WeatherProviderPropertiesTest#invalidType` (dev) | integration + unit | PASS |
| AC-4 | `ProviderConfigFilesTest` (dev); `WeatherProviderSelectionIT#ac4_envVarsAreBound`, `#ac4_edge_invalidUrlFailsWithoutEcho` | unit + integration | PASS |
| AC-5 | `ItProfileProviderDefaultsIT#ac5_typeIsStub`, `#ac5_baseUrlsAreLoopback`, `#ac5_itProfileValuesAreLiterals`; all pre-existing ITs (stub) | integration | PASS |
| AC-6 | `RealProviderCurrentIT#ac6_currentWeatherFromUpstream` (incl. OpenAPI schema check), `#ac6_realProviderIsWired`, `#ac6_edge_slowButInTime` | integration | PASS |
| AC-7 | `RealProviderCurrentIT#ac7_imperialConvertedLocallyUpstreamAlwaysMetric`; `OpenMeteoWeatherProviderTest#dailyRequestParameters` | integration + unit | PASS |
| AC-8 | `RealProviderForecastIT#ac8_threeDayForecast`, `#ac8_edge_windowBounds` (fixed clock 2026-11-15T10:00Z) | integration | PASS |
| AC-9 | `RealProviderCurrentIT#ac9_lowerCaseCity`, `#ac9_paddedCity` | integration | PASS |
| AC-10 | `RealProviderCurrentIT#ac10_cityCannotInjectQueryParameters`, `#ac10_edge_reservedCharactersAreEncoded` | integration | PASS |
| AC-11 | `OpenMeteoWeatherCodesTest` (dev, all 28 codes) | unit | PASS |
| AC-12 | `OpenMeteoMapperTest` humidity/direction/precipitation/country (dev) | unit | PASS |
| AC-13 | `RealProviderNotFoundIT#ac13_noResultsIs404` (4 cases), `#ac13_unknownCityByDefault` (2); `OpenMeteoWeatherProviderTest#notFoundPassesThrough` | integration + unit | PASS |
| AC-14 | `RealProviderNotFoundIT#ac14_missingCountryIs404` (missing, empty, blank, null) | integration | PASS |
| AC-15 | `RealProviderFailureIT#ac15_readTimeoutIs503Quickly` (geocoding, forecast; ~1.0 s each) | integration | PASS |
| AC-16 | `UpstreamUnreachableIT#ac16_connectionRefusedIs503` (current, forecast) | integration | PASS |
| AC-17 | `RealProviderFailureIT#ac17_non2xxIs503` (500/502/503/429/400 x geocoding/forecast), `#ac17_dailyNon2xxIs503`; `OpenMeteoWeatherProviderTest#geocodingFailureStopsBeforeForecast` | integration + unit | PASS |
| AC-18 | `RealProviderFailureIT#ac18_malformedForecastIs503` (7 bodies), `#ac18_edge_emptyBodyIs503`, `#ac18_edge_oversizeBodyIs503`, `#ac18_edge_malformedGeocodingIs503`; `OpenMeteoWeatherProviderTest#emptyBody`, `#bodyCap` | integration + unit | PASS |
| AC-19 | `RealProviderForecastIT#ac19_shortDailyArrayIs503`, `#ac19_shiftedDatesAre503`, `#ac19_edge_longDailyArrayIs503`; `OpenMeteoMapperTest` (dev) | integration + unit | PASS |
| AC-20 | `RealProviderForecastIT#ac20_nullPrecipitationIsZero`, `#ac20_nullOtherDailyFieldIs503` | integration | PASS |
| AC-21 | `RealProviderFailureIT#ac21_warnLineContentForFixedRequestId`, `#ac21_exceptionMessageIsSanitised`, plus the one-WARN/no-leak assertions in every AC-15/17/18 case; `UpstreamUnreachableIT`; `OpenMeteoWeatherProviderTest#invalidResponseMessageIsFixed` | integration + unit | PASS |
| AC-22 | `RealProviderCurrentIT#ac22_noQueryStringsLoggedOnSuccess` | integration | PASS |
| AC-23 | `RealProviderContractConformanceIT` (13 inherited AC-22 checks), `RealProviderOpenApiDriftIT` (inherited AC-21 drift check, `#ac23_realProviderIsActive`, `#ac23_openApiDocumentUnchanged`); `git diff origin/main -- src/main/resources/openapi/` is empty | integration | PASS |

## Summary
- Unit (`./gradlew test`): **289 passed / 0 failed** (10 new in `OpenMeteoWeatherProviderTest`).
- Integration (`scripts/test-env.sh it`): **188 passed / 0 failed** (110 pre-existing on the stub, 78 new).
- New test files: `support/FakeOpenMeteo`, `support/RealProviderIntegrationTestBase`,
  `provider/{WeatherProviderSelection,StubSelection,ItProfileProviderDefaults,RealProviderCurrent,RealProviderForecast,RealProviderNotFound,RealProviderFailure,UpstreamUnreachable}IT`,
  `api/RealProvider{ContractConformance,OpenApiDrift}IT`, `provider/openmeteo/OpenMeteoWeatherProviderTest`.

## Failures / defects found
### Product bugs
None. No production code was changed.

### Test issues (fixed in the tests)
- First run: `RealProviderCurrentIT#ac22_noQueryStringsLoggedOnSuccess` failed because it required
  *no* INFO line for the request. The existing `RequestIdFilter` access line
  (`GET /api/v1/weather/current -> 200 ...`, path only, no query) is INFO and is allowed by AC-22.
  The assertion now checks that no INFO+ line has a query string or comes from the adapter.

## Deviations from design (tests)
- `WeatherProviderSelectionTest` (design: `src/test`, `ApplicationContextRunner`) is
  `src/integrationTest/.../provider/WeatherProviderSelectionIT` instead, because CLAUDE.md keeps
  Spring contexts out of unit tests. It does not extend `IntegrationTestBase` and does not use
  the `it` profile. An initializer replaces `systemProperties` with an empty source (removing
  `spring.profiles.active=it`) and `systemEnvironment` with an explicit
  `SystemEnvironmentPropertySource` (removing exported `.env` values) **before**
  `ConfigDataApplicationContextInitializer` loads the real `application.yml`. The test asserts
  that no profile is active.
- The real-provider ITs and the AC-23 subclasses select the fake through a shared
  `ApplicationContextInitializer` (`FakeOpenMeteo.RealProviderInitializer`) instead of
  `@DynamicPropertySource`, so all of them share **one** cached context (subclasses of existing ITs
  can't inherit a `@DynamicPropertySource` method). Extra contexts set
  `spring.datasource.hikari.maximum-pool-size=3` to keep the compose Postgres connection count low.

## Not covered / risks
- **Real Open-Meteo is never called** (by design). Field names, the encoding Open-Meteo actually
  accepts, TLS to the real hosts and the free-tier quota (A-2) are not verified. A manual smoke run
  (`./gradlew bootRun` with internet) is recommended before merge.
- **AC-21 interpretation**: for AC-18/AC-19 failures the WARN line has `outcome=invalid-response reason=...`
  (a fixed reason), not an HTTP status or exception class. The tests accept that, as in the design.
  "Exactly one WARN line" is counted for the adapter's `Upstream call failed` line only.
  `ApiExceptionHandler` writes its own WARN line as well (required to stay).
- **AC-15 timing** is checked with a 1 s read timeout only; the 2 s / 3 s defaults (worst case about 10 s)
  are not measured. A slowly trickled body (bytes arriving under the read timeout) is not tested.
- **AC-16 on Windows**: connection refused to a closed loopback port can take about 2 s (SYN retries),
  so it is cut by the 1 s connect timeout. The test allows < 5 s and does not distinguish
  "refused" from "connect timeout".
- **Timeout env vars** (`WEATHER_PROVIDER_*_TIMEOUT`) exported from `.env` can override the `it`
  timeouts by relaxed binding (design notes this). The real-provider ITs pin 1 s themselves.
- **AC-23 "byte-for-byte unchanged"** is verified with `git diff origin/main` (empty) and by comparing
  the classpath copy with the source file. The test can't see `main`.
- The compose `app` service with the real default provider was not started (needs outbound internet).
