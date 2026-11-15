# forecast-for-all-favourites: SDLC status
Request: Forecast for all favourites: the same per-favourite fan-out as weather-for-all-favourites (#7), but for multi-day forecasts.
Branch: feature/forecast-for-all-favourites · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [x] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-27)
- 2026-11-15 architect: design.md written; ADR 0015 (forecastAtLocation, amends 0012) and ADR 0016 (shared fan-out bean/settings/per-item error schema, refines 0013); overview updated
- 2026-11-16 developer: design steps 1-5 implemented in 5 commits (forecastAtLocation port/adapters, WeatherService#forecastAt, FavouriteForecastService, GET /api/v1/favourites/forecast, OpenAPI + application.yml comment) with 11 new unit test classes; ./gradlew build green; scripts/test-env.sh it green (441 ITs, 0 failures); steps 6-8 (test support + AC ITs) left to the tester

## Deviations from design
- `FavouriteForecastServiceTest` uses a Mockito-mocked `FavouriteRepository` (as `FavouriteWeatherServiceTest` does) instead of a hand-written in-memory fake; behaviour covered is the same.
- `application.yml`: the existing comment under `weather.favourites.weather` was reworded in place (it sits below the `weather:` key, not above it); keys and placeholders are unchanged.
- Design steps 6-8 (`FakeOpenMeteo.dailyFor`, `InFlightResponder.daily`, and all AC-mapped ITs) were not done by the developer: per the task, additive changes to shared IT support and the AC ITs are the tester's.
