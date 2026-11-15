# UI-backend API contracts: Test report
Branch: feature/ui-backend-api-contracts @ aeb7714 (code under test, includes the D-1 fix; re-test tests in 855787e) · Date: 2026-11-15 (re-run after D-1 fix) · Env: docker compose (postgres:17, redis:7)

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
| AC-5 | `ForecastIT#ac5_threeDayForecast`, `ForecastIT#ac5_edge_boundaryDays` (1, 7), `DaysParsingIT#ac5_lenientDecimalFormsAccepted`, `QueryValidationTest#validDays`, `DaysParamValidatorTest#ac5_lenientButDecimalFormsAccepted` | integration, unit | PASS |
| AC-6 | `ForecastIT#ac6_defaultsToFiveDaysMetric`, `ForecastIT#edge_emptyDaysIsDefault`, `DaysParamValidatorTest#ac6_absentOrEmptyIsDefault` | integration, unit | PASS |
| AC-7 | `ValidationIT#ac7_invalidParameter` (8 cases), `DaysParsingIT#ac7_nonDecimalDaysRejected` (6 cases), `#ac7_repeatedDaysRejected`, `QueryValidationTest#blankCityInvalid/#invalidUnits/#invalidDays/#nullCityInvalid/#nonIntegerDaysInvalid`, `DaysParamValidatorTest#ac7_nonDecimalFormsRejected/#ac7_outOfRangeIntegersGetRangeMessage` | integration, unit | PASS |
| AC-8 | `ValidationIT#ac8_allViolationsReported`, `ValidationIT#edge_typeMismatchCombinedWithOtherViolations` (D-1), `DaysParsingIT#ac8_hexDaysCollectedWithOthers`, `QueryValidationTest#allViolationsCollected/#nonIntegerDaysCollectedWithOthers` | integration, unit | PASS |
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
Re-run after the D-1 fix (7fad9d9, 23f8b10, aeb7714):
- **Unit** (`./gradlew test`): **139 passed / 0 failed**. Of these, 115 are the developer's after
  the fix and 24 are new in `DaysParamValidatorTest`: hex `0X3`, `#3`, `3L`, `3.0`, `1e0`, `3,4`,
  `--3`, NBSP prefix, int overflow, `-0`/`00`/`+8` range message, `03`/`+3`/surrounding whitespace
  accepted, empty is the default, and `resolvedDays()` fails fast when the query wasn't validated.
- **Integration** (`scripts/test-env.sh it`): **104 passed / 0 failed / 0 skipped**. That is the
  93 from before, now including the re-enabled D-1 reproducer, plus 11 new in `DaysParsingIT`:
  `0x3`, `1 2`, `#3`, `3.0`, `1e0` and overflow over HTTP, a repeated `days=2&days=3`, `03`/`+3`/` 3 `
  accepted, and `days=0x3` reported together with `city` and `units`.
- **All 23 ACs pass.** No open product defects.

Earlier run, before the fix: unit 106/0, integration 92 passed / 0 failed / 1 skipped (D-1).

## Failures / defects found

### Product defects
- **D-1 (minor): FIXED in 7fad9d9 and verified.** Before the fix, a `days` type mismatch hid the
  other validation errors: `GET /api/v1/weather/forecast?units=kelvin&days=abc` returned only
  `[days]`. Now `days` is bound as a `String` and checked by `@DaysParam`, so all three fields
  are reported. The reproducer `ValidationIT#edge_typeMismatchCombinedWithOtherViolations` is
  re-enabled (23f8b10) and passes. `DaysParsingIT#ac8_hexDaysCollectedWithOthers` and
  `QueryValidationTest#nonIntegerDaysCollectedWithOthers` also cover it.
- **Behaviour change from the fix (intended, documented in status.md deviations):** `0x3` and
  `#3` were accepted as 3 by Spring's `Integer` converter. They are now 400 with the
  non-integer message. `03`, `+3` and surrounding whitespace are still accepted. The new tests
  pin this.

### Test issues
- None. Earlier, a flake risk in my own test was fixed (`days=abc` vs. random UUID request id;
  it now uses `days=xyzzy`).

## Not covered / risks
- **Non-ASCII digits in `days`** (observation, not filed as a defect): `Integer.parseInt` accepts
  Unicode decimal digits, so for example Arabic-Indic `٣` or fullwidth `３` probably resolve to
  3. This was not asserted either way. The old `Integer` converter behaved the same, so this is
  not a regression. If the contract should be ASCII-only, `DaysParamValidator.parse` needs an
  `[+-]?[0-9]+` pre-check. That is a product decision.
- The `days` binding-failure branch in `ApiExceptionHandler` can no longer be reached through the
  API (kept as a defensive fallback, per the developer). It has no integration coverage.
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
