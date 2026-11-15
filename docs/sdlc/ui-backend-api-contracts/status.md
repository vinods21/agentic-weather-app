# ui-backend-api-contracts: SDLC status
Request: build the api contracts between ui and backend
Branch: feature/ui-backend-api-contracts · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [x] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15: run started. `scripts/test-env.sh up` failed: Docker daemon not running (npipe docker_engine not found).
- 2026-11-15 pm: requirements.md written (AC-1..AC-23)
- 2026-11-15: Docker now available; ran scripts/test-env.sh up.
- 2026-11-15 architect: design.md written; ADRs 0002 (hand-written OpenAPI), 0003 (API conventions), 0004 (provider port + stub); overview.md updated; no new dependencies, no migrations
- 2026-11-15 developer: implemented design steps 1-7 (domain + stub provider, WeatherService, v1 controller/DTOs, Problem Details advice, request-id filter, CORS, OpenAPI doc + controller) with unit tests; `./gradlew build` and `scripts/test-env.sh it` green; AC-mapped ITs (design step 8 and the per-step ITs) left to the tester.
- 2026-11-15 tester: 12 AC-mapped IT classes + 3 unit test classes added; unit 106/0, integration 92 passed/0 failed/1 skipped; all AC-1..AC-23 PASS; defect D-1 (days type mismatch hides other validation errors) reported in test-report.md, reproducer @Disabled

## Deviations from design
- `WeatherProvider#forecast` returns `weather.LocationForecast(Location location, List<DailyForecast> days)` instead of `List<DailyForecast>`: the forecast response needs the location, and the design's signature gave no way to obtain it.
- `RequestIds` was added in the error-handling commit (design step 4) rather than step 5, because `ProblemFactory` needs it for `requestId`.
- `ProblemFactory.build` has an extra overload taking `HttpHeaders` (used for the 405 `Allow` header).
- `StubWeatherProvider#forecast` throws `IllegalArgumentException` for `days` outside 1..7 (unreachable through the API, which validates first).
- Extra unit tests beyond the design list: `QueryRecordsTest`, `ApiExceptionHandlerTest`, `RequestIdFilterTest` (servlet mocks, no Spring context).
