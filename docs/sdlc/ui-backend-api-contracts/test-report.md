# UI-backend API contracts: Test report
Branch: feature/ui-backend-api-contracts @ 2cb3e3e (code under test at 8ea2431 = ConversionNotSupportedException fix, on top of the 372aa3b merge of main) · Date: 2026-11-15 (re-run after the pre-review minor fix) · Env: docker compose (postgres:17, redis:7)

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
| AC-7 | `ValidationIT#ac7_invalidParameter` (8 cases), `DaysParsingIT#ac7_nonDecimalDaysRejected` (8 cases, incl. Arabic-Indic `%D9%A3` and fullwidth `%EF%BC%93`), `#ac7_repeatedDaysRejected`, `QueryValidationTest#blankCityInvalid/#invalidUnits/#invalidDays/#nullCityInvalid/#nonIntegerDaysInvalid`, `DaysParamValidatorTest#ac7_nonDecimalFormsRejected` (incl. `٣`, `３`, `१`), `#ac7_outOfRangeIntegersGetRangeMessage/#ac7_messagesMatchContractRange`, `ApiExceptionHandlerTest#methodArgumentTypeMismatchIsValidationFailed/#typeMismatchWithoutPropertyName/#typeMismatchOutsideApiIsRethrown` (type-mismatch handler, unit only: unreachable via current endpoints; the 400 is not used for `ConversionNotSupportedException`, see AC-14) | integration, unit | PASS |
| AC-8 | `ValidationIT#ac8_allViolationsReported`, `ValidationIT#edge_typeMismatchCombinedWithOtherViolations` (D-1), `DaysParsingIT#ac8_hexDaysCollectedWithOthers`, `QueryValidationTest#allViolationsCollected/#nonIntegerDaysCollectedWithOthers` | integration, unit | PASS |
| AC-9 | `CurrentWeatherIT#ac9_hundredCharacterCityIsInclusive`, `#ac9_edge_lengthIsCheckedAfterTrimming`, `QueryValidationTest#cityLengthIsInclusive` | integration, unit | PASS |
| AC-10 | `CurrentWeatherIT#ac10_unknownCityCurrent`, `ForecastIT#ac10_unknownCityForecast` | integration | PASS |
| AC-11 | `RoutingErrorsIT#ac11_unknownApiPath`, `#ac11_edge_instanceHasNoQueryString`, `ApiPathsTest#pathExcludesQuery` | integration, unit | PASS |
| AC-12 | `RoutingErrorsIT#ac12_postIsMethodNotAllowed`, `#ac12_edge_deleteIsMethodNotAllowed` | integration | PASS |
| AC-13 | `ProviderFailureIT#ac13_currentUpstreamUnavailable`, `#ac13_forecastUpstreamUnavailable`, `#ac13_edge_credentialsInProviderMessageAreNotLogged` (`@MockitoSpyBean` + `OutputCaptureExtension`), `ApiExceptionHandlerTest#upstreamUnavailableWarnLogsNoProviderMessage/#upstreamUnavailableWithoutCauseLogsNone` | integration, unit | PASS |
| AC-14 | `ProviderFailureIT#ac14_unexpectedErrorIsSanitised`, `ApiExceptionHandlerTest#conversionNotSupportedIsInternalError/#methodArgumentConversionNotSupportedIsInternalError` (no converter -> 500 `INTERNAL_ERROR`, sanitised body, ERROR log) | integration, unit | PASS |
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
**Latest run (HEAD 2cb3e3e, code at 8ea2431 + merge of main 372aa3b):**
- **Unit** (`./gradlew test --rerun-tasks`): **150 passed / 0 failed / 0 skipped**. That is the 148
  below plus the developer's 2 new `ApiExceptionHandlerTest` cases for
  `ConversionNotSupportedException` and `MethodArgumentConversionNotSupportedException`. Both assert
  500 `INTERNAL_ERROR`, `application/problem+json`, a fixed detail, no `errors` key, no leaked
  value/class names, and an ERROR log line with the request id.
  `#methodArgumentTypeMismatchIsValidationFailed` still asserts that a plain
  `MethodArgumentTypeMismatchException` gets 400 `VALIDATION_FAILED`.
- **Integration** (`scripts/test-env.sh it`): **107 passed / 0 failed / 0 skipped**.
- **All 23 ACs pass.** No open product defects. I added no tests this round. The fix is covered
  at the unit level and cannot be reached over HTTP (see risks). The merge of main (372aa3b)
  changed no files compared with fb09223, because main held only the earlier PR #2 head.
- I found no Testcontainers, `@ServiceConnection` or docker-java usage. The only matches are the
  "never use" comments in `IntegrationTestBase` and `build.gradle.kts`.

Previous run, after the PR #2 round-1 fixes (0fabbfd, d93f1af, 7e1b6d7, bf8dc0e, 9bff0de, 267206f, 286b706):
- **Unit** (`./gradlew test`): **148 passed / 0 failed / 0 skipped**. 147 are the developer's after
  the fixes (139 earlier + non-ASCII digit cases, the contract-range check, three type-mismatch
  handler tests and the 503 log-redaction test); 1 is new from me:
  `ApiExceptionHandlerTest#upstreamUnavailableWithoutCauseLogsNone` (503 with no cause logs
  `cause=none`, message not logged).
- **Integration** (`scripts/test-env.sh it`): **107 passed / 0 failed / 0 skipped**. 106 are the
  developer's (104 earlier + the two non-ASCII digit cases in `DaysParsingIT`); 1 is new from me:
  `ProviderFailureIT#ac13_edge_credentialsInProviderMessageAreNotLogged`. It checks over HTTP that a
  provider message and cause holding a credential-bearing URL show up in neither the 503 body nor
  any log line, and that the WARN line names both classes.
- **All 23 ACs pass.** No open product defects.
- Review-fix verification: ASCII-only `days` (unit + IT), fixed `DaysParam.MIN/MAX` with derived
  messages (unit), dead `days` branch removed (unit asserts the generic message), 503 WARN has
  class names only (unit + IT), `TypeMismatchException` -> 400 `VALIDATION_FAILED` (unit only, see
  risks), and the `@MockitoSpyBean` deviation, which matches `ProviderFailureIT`.
- No Testcontainers, `@ServiceConnection` or docker-java anywhere in `src/` or the build.

Earlier runs: after the D-1 fix, unit 139/0 and integration 104/0/0. Before it, unit 106/0 and
integration 92/0/1 skipped (D-1).

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
- **Type-mismatch 400 handler is unreachable through the current endpoints (unit-tested only).**
  Both endpoints bind a `@ModelAttribute` record with only `String` fields (`days` is a `String`
  since the D-1 fix), and there are no typed `@RequestParam`/`@PathVariable` parameters, so neither
  `TypeMismatchException` nor a `BindException` binding failure can come out of a v1 request. The
  `handleTypeMismatch` handler and the `isBindingFailure()` branch in `toDto` are defensive, and only
  `ApiExceptionHandlerTest` covers them.
- **Observation RESOLVED in 8ea2431 and verified:** `@ExceptionHandler(TypeMismatchException.class)`
  also matched its subclasses `ConversionNotSupportedException` and
  `MethodArgumentConversionNotSupportedException`, so a missing-converter server fault would have
  been returned as 400 `VALIDATION_FAILED`. `handleTypeMismatch` now hands those to
  `handleUnexpected` (500 `INTERNAL_ERROR`). Two unit tests pin this, and a plain type mismatch
  still gets 400. Like the 400 path, it is unit-tested only because no current endpoint has a
  typed parameter.
- Non-ASCII digits in `days`: resolved by 0fabbfd (ASCII `[+-]?[0-9]+` only), now covered by unit
  and IT cases.
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
