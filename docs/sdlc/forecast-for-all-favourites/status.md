# forecast-for-all-favourites: SDLC status
Request: Forecast for all favourites: the same per-favourite fan-out as weather-for-all-favourites (#7), but for multi-day forecasts.
Branch: feature/forecast-for-all-favourites · PR: #8 https://github.com/vinods21/agentic-weather-app/pull/8
## Phases
- [x] pm  - [x] architect  - [x] developer  - [x] tester  - [x] pr  - [x] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-27)
- 2026-11-15 architect: design.md written; ADR 0015 (forecastAtLocation, amends 0012) and ADR 0016 (shared fan-out bean/settings/per-item error schema, refines 0013); overview updated
- 2026-11-16 developer: design steps 1-5 implemented in 5 commits (forecastAtLocation port/adapters, WeatherService#forecastAt, FavouriteForecastService, GET /api/v1/favourites/forecast, OpenAPI + application.yml comment) with 11 new unit test classes; ./gradlew build green; scripts/test-env.sh it green (441 ITs, 0 failures); steps 6-8 (test support + AC ITs) left to the tester
- 2026-11-16 tester: design steps 6-8 done in 3 test: commits (FakeOpenMeteo.dailyFor/InFlightResponder.daily, 7 new AC-mapped IT classes, 1 unit edge-case class); unit 750/0, IT 517/0 on two consecutive full runs (peak client backends 89 and 87 of 100, no "too many clients"); timing classes 5x rerun 75/75; all AC-1..AC-27 PASS, no product defects; test-report.md written

## Deviations from design
- `FavouriteForecastServiceTest` uses a Mockito-mocked `FavouriteRepository` (as `FavouriteWeatherServiceTest` does) instead of a hand-written in-memory fake; behaviour covered is the same.
- `application.yml`: the existing comment under `weather.favourites.weather` was reworded in place (it sits below the `weather:` key, not above it); keys and placeholders are unchanged.
- Design steps 6-8 (`FakeOpenMeteo.dailyFor`, `InFlightResponder.daily`, and all AC-mapped ITs) were not done by the developer: per the task, additive changes to shared IT support and the AC ITs are the tester's.
- 2026-11-16 orchestrator: re-checked build + it (517 IT, 0 fail), no Testcontainers, no build-file/migration/.env.example/compose changes, no new leftover schemas or it:* keys; pushed and opened PR #8.
- 2026-11-16 review round 1: READY FOR HUMAN MERGE (0 blocker, 0 major, 0 minor, 2 nit) https://github.com/vinods21/agentic-weather-app/pull/8#pullrequestreview-5915566684. Loop stopped; round 2 not needed.
