# ui-backend-api-contracts: SDLC status
Request: build the api contracts between ui and backend
Branch: feature/ui-backend-api-contracts · PR: #2 https://github.com/vinods21/agentic-weather-app/pull/2
## Phases
- [x] pm  - [x] architect  - [x] developer  - [x] tester  - [x] pr  - [x] review round 1  - [ ] review round 2
## Log
- 2026-11-15: run started. `scripts/test-env.sh up` failed: Docker daemon not running (npipe docker_engine not found).
- 2026-11-15 pm: requirements.md written (AC-1..AC-23)
- 2026-11-15: Docker now available; ran scripts/test-env.sh up.
- 2026-11-15 architect: design.md written; ADRs 0002 (hand-written OpenAPI), 0003 (API conventions), 0004 (provider port + stub); overview.md updated; no new dependencies, no migrations
- 2026-11-15 developer: implemented design steps 1-7 (domain + stub provider, WeatherService, v1 controller/DTOs, Problem Details advice, request-id filter, CORS, OpenAPI doc + controller) with unit tests; `./gradlew build` and `scripts/test-env.sh it` green; AC-mapped ITs (design step 8 and the per-step ITs) left to the tester.
- 2026-11-15 tester: 12 AC-mapped IT classes + 3 unit test classes added; unit 106/0, integration 92 passed/0 failed/1 skipped; all AC-1..AC-23 PASS; defect D-1 (days type mismatch hides other validation errors) reported in test-report.md, reproducer @Disabled
- 2026-11-15 developer: fixed D-1: forecast `days` bound as String and validated by new `@DaysParam` constraint, so `days=abc` is reported with city/units; ValidationIT D-1 reproducer re-enabled; `./gradlew build` (unit 115/0) and `scripts/test-env.sh it` (93 passed/0 failed/0 skipped) green
- 2026-11-15 tester: re-test after D-1 fix: unit 139/0, integration 104 passed/0 failed/0 skipped; D-1 verified fixed (reproducer passes); added DaysParamValidatorTest + DaysParsingIT for days parse-strictness edges (0x3, 1 2, #3, repeated days, +3/03); all AC-1..AC-23 PASS; test-report.md updated

## Deviations from design
- `WeatherProvider#forecast` returns `weather.LocationForecast(Location location, List<DailyForecast> days)` instead of `List<DailyForecast>`: the forecast response needs the location, and the design's signature gave no way to obtain it.
- `RequestIds` was added in the error-handling commit (design step 4) rather than step 5, because `ProblemFactory` needs it for `requestId`.
- `ProblemFactory.build` has an extra overload taking `HttpHeaders` (used for the 405 `Allow` header).
- `StubWeatherProvider#forecast` throws `IllegalArgumentException` for `days` outside 1..7 (unreachable through the API, which validates first).
- Extra unit tests beyond the design list: `QueryRecordsTest`, `ApiExceptionHandlerTest`, `RequestIdFilterTest` (servlet mocks, no Spring context).
- D-1 fix: `ForecastQuery.days` is a `String` (validated by `@DaysParam`/`DaysParamValidator`, read via `resolvedDays()`) instead of `Integer`. Parsing is strict decimal after stripping surrounding whitespace, so values Spring's converter used to accept (e.g. hex `0x3`, `#3`, or internal whitespace) are now 400 with the non-integer message. After PR #2 review: only ASCII `[+-]?[0-9]+` is accepted (as documented on the `Days` parameter), and the unreachable `days` binding-failure branch in `ApiExceptionHandler` was removed.
- `ProviderFailureIT` uses `@MockitoSpyBean` instead of the `@MockitoBean` the design named, so unstubbed calls keep the stub's real behaviour; design.md and ADR 0004 wording updated to match.
- 2026-11-15 orchestrator: re-checked build + it (104 IT, 0 fail), no Testcontainers; pushed and opened PR #2.
- 2026-11-15 review round 1: READY FOR HUMAN MERGE (0 blocker, 0 major, 3 minor, 3 nit) https://github.com/vinods21/agentic-weather-app/pull/2#pullrequestreview-5909139300. Loop stopped; round 2 not needed.
- 2026-11-15 developer: fixed all 6 PR #2 review points (ASCII-only days + spec wording, fixed DaysParam range, dead days branch removed, 503 WARN logs class names only, TypeMismatchException -> 400 VALIDATION_FAILED, @MockitoSpyBean docs/deviation); build 147 unit / it 106 IT, 0 fail; not pushed.
