# UI-backend API contracts: Test report
Branch: feature/ui-backend-api-contracts @ f5f17ba (code under test; tests added on top) · Date: 2026-11-15 · Env: docker compose (postgres:17, redis:7)

Commands: `./gradlew test` (unit) and `scripts/test-env.sh it` (integration, against the running
compose services; no Testcontainers). Integration tests live in
`src/integrationTest/java/com/example/weather/api/` and share
`support/ApiIntegrationTestBase` (extends `IntegrationTestBase`) and `support/OpenApiSchemaAssert`
(Jackson-only schema checker). All requests are sent as `java.net.URI` so `%20` etc. are not
double-encoded. The tests create no rows and no Redis keys.

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `CurrentWeatherIT#ac1_currentWeatherForLondon` | integration | PASS |
| AC-2 | `CurrentWeatherIT#ac2_imperialIsConvertedFromMetric`, `UnitsTest` | integration, unit | PASS |
| AC-3 | `CurrentWeatherIT#ac3_stubIsDeterministic` | integration | PASS |
| AC-4 | `CurrentWeatherIT#ac4_cityIsTrimmed`, `QueryValidationTest#lengthAfterStrip` | integration, unit | PASS |
| AC-5 | `ForecastIT#ac5_threeDayForecast`, `ForecastIT#ac5_edge_boundaryDays` (1, 7), `QueryValidationTest#validDays` | integration, unit | PASS |
| AC-6 | `ForecastIT#ac6_defaultsToFiveDaysMetric` | integration | PASS |
| AC-7 | `ValidationIT#ac7_invalidParameter` (8 cases), `QueryValidationTest#blankCityInvalid/#invalidUnits/#invalidDays/#nullCityInvalid` | integration, unit | PASS |
| AC-8 | `ValidationIT#ac8_allViolationsReported`, `QueryValidationTest#allViolationsCollected` | integration, unit | PASS |
| AC-9 | `CurrentWeatherIT#ac9_hundredCharacterCityIsInclusive`, `#ac9_edge_lengthIsCheckedAfterTrimming`, `QueryValidationTest#cityLengthIsInclusive` | integration, unit | PASS |
| AC-10 | `CurrentWeatherIT#ac10_unknownCityCurrent`, `ForecastIT#ac10_unknownCityForecast` | integration | PASS |
| AC-11 | `RoutingErrorsIT#ac11_unknownApiPath`, `#ac11_edge_instanceHasNoQueryString`, `ApiPathsTest#pathExcludesQuery` | integration, unit | PASS |
| AC-12 | `RoutingErrorsIT#ac12_postIsMethodNotAllowed`, `#ac12_edge_deleteIsMethodNotAllowed` | integration | PASS |
| AC-13 | `ProviderFailureIT#ac13_currentUpstreamUnavailable`, `#ac13_forecastUpstreamUnavailable` (`@MockitoSpyBean` + `OutputCaptureExtension`) | integration | PASS |
| AC-14 | `ProviderFailureIT#ac14_unexpectedErrorIsSanitised` | integration | PASS |
| AC-15 | `RequestIdIT#ac15_echoedOnSuccess` (current, forecast, openapi.json), `#ac15_echoedOnError` (400, 404 city, 404 path), `#ac15_echoedOn405` | integration | PASS |
| AC-16 | `RequestIdIT#ac16_generatedWhenMissingOnSuccess`, `#ac16_generatedWhenMissingOnError`, `#ac16_invalidIdIsReplaced`, `#ac16_edge_lengthLimit`, `#ac16_edge_idsAreFresh` | integration | PASS |
| AC-17 | `CorsIT#ac17_allowedOriginPreflight`, `#ac17_edge_actualRequestFromAllowedOrigin` | integration | PASS |
| AC-18 | `CorsIT#ac18_unlistedOriginRejected`, `#ac18_edge_disallowedMethodRejected`, `CorsDefaultDenyIT#ac18_defaultDeniesEveryOrigin`, `#ac18_edge_actualCrossOriginGetRejected` | integration | PASS |
| AC-19 | `OpenApiIT#ac19_documentIsServed` | integration | PASS |
| AC-20 | `OpenApiIT#ac20_cityAndUnitsParameters`, `#ac20_daysParameter`, `#ac20_successSchemasRequireEveryField`, `#ac20_errorResponsesUseSharedProblem`, `#ac20_problemCodeEnum`, `#ac20_compatibilityPolicyDocumented` | integration | PASS |
| AC-21 | `OpenApiDriftIT#ac21_mappingsEqualSpecOperations` | integration | PASS |
| AC-22 | `ContractConformanceIT#ac22_currentWeatherConforms`, `#ac22_forecastConforms`, `#ac22_validationProblemConforms`, `#ac22_locationNotFoundConforms`, `#ac22_edge_routingProblemsConform`, `#ac22_checkerDetectsViolations` (self-test) | integration | PASS |
| AC-23 | `HealthRegressionIT#ac23_healthUnaffected`, `ApiPathsTest#nonApiPaths` | integration, unit | PASS |

## Summary
- **Unit** (`./gradlew test`): 106 passed / 0 failed. New: `QueryValidationTest`,
  `ApiPathsTest`, `UnitsTest` (boundary/edge cases for validation, the `/api/` guard and units).
- **Integration** (`scripts/test-env.sh it`): 92 passed / 0 failed / 1 skipped (93 total, including
  the 3 existing `InfrastructureIT` tests). The skipped test is the defect D-1 reproducer below.
  On the first run, with that test enabled, it failed (92 / 1).
- **All 23 ACs pass.** No AC fails because of a product bug, and no AC fails because of a test issue.

## Failures / defects found

### Product defects (not AC failures)
- **D-1 (minor): a `days` type mismatch hides the other validation errors.**
  `GET /api/v1/weather/forecast?units=kelvin&days=abc` returns 400 with `errors` = `[days]`
  only. The `city` (missing) and `units` (invalid) violations are not reported. Test:
  `ValidationIT#edge_typeMismatchCombinedWithOtherViolations`. It is `@Disabled("Defect D-1 ...")`
  so the AC suite stays green; remove the annotation to reproduce it. Failure output:
  ```
  Expecting ArrayList: ["days"] to contain: ["city", "units", "days"]
  but could not find the following element(s): ["city", "units"]
  ```
  This is the risk named in design.md ("days=abc combined with other errors"). AC-7 and AC-8
  as written still pass, because they don't combine `abc` with other violations. It does go
  against the intent of AC-8 ("all violations are reported, not only the first"). Likely
  cause: when `@ModelAttribute` constructor binding fails a conversion, Spring raises the
  `BindException` with only the binding failure and does not run Bean Validation on the other
  arguments. Possible fixes (developer's call): bind `days` as a `String` and validate it with
  `@Pattern`/a custom constraint, or validate the remaining arguments in the handler. Product
  code was not changed.

### Test issues
- None left. One flake risk was fixed in my own test before the first run: the "rejected value
  isn't echoed" check used `days=abc`, and a random UUID `requestId` could contain `abc`. It now
  uses `days=xyzzy`.

## Not covered / risks
- **Access-log content** (NFR: one INFO line per request with method, path, status, duration and
  request id; MDC `requestId`) is covered only by the developer's `RequestIdFilterTest` unit
  test. No IT asserts the line. AC-13/AC-14 do assert WARN/ERROR lines with the request id.
- **AC-17 `Access-Control-Expose-Headers` on preflight** passes with Spring 6.2's
  `DefaultCorsProcessor`, which also sends it on preflight. Browsers only read it on the actual
  response, which `ac17_edge_actualRequestFromAllowedOrigin` covers too.
- **AC-5 midnight race**: the test accepts today's UTC date taken either before or after the call.
- **AC-14 log check** depends on Logback writing to stdout (Boot default console appender). A
  future change to file-only logging would break the capture, not the feature.
- **Out of scope, untested**: 406/415 under `/api/` (fall back to Spring defaults by design),
  `HEAD`/plain `OPTIONS` behaviour, and the A-1 stub-as-prod-default decision (needs a human).
- **Redis/Postgres**: the feature touches neither, so the per-run schema/prefix isolation is
  exercised only by the existing `InfrastructureIT`.
