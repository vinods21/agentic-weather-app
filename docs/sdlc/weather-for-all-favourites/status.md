# weather-for-all-favourites: SDLC status
Request: Weather for all favourites: one endpoint that returns current weather for every saved favourite of a client.
Branch: feature/weather-for-all-favourites · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-24)
- 2026-11-15 architect: design.md written; ADRs 0012 (currentAtLocation, amends 0011), 0013 (bounded fan-out/deadline/MDC), 0014 (per-item errors, amends 0003); overview.md updated
- 2026-11-15 developer: implemented design steps 1-7 (currentAtLocation, WeatherService#currentAt, BoundedFanOut, FavouritesWeatherProperties + config, FavouriteWeatherService, GET /api/v1/favourites/weather, OpenAPI) with unit tests; ./gradlew build green; scripts/test-env.sh it green (394 existing ITs); no migration; AC-mapped ITs (steps 8-9) left to the tester

## Deviations from design
- Default-method unit test is named `provider.WeatherProviderCurrentAtLocationDefaultTest` (the design allowed `WeatherProviderDefaultsTest` or a new class).
- `BoundedFanOut`: a task returning `null` is treated as a bug (`NullPointerException` rethrown, like any escaping exception), making the "task must return non-null" contract explicit. A worker failure reported between the caller's last `poll` and setting `stopped` is drained and rethrown; failures after that are logged at ERROR (class name only). A failure that is neither `RuntimeException` nor `Error` (only possible via sneaky throws) is wrapped in `IllegalStateException`.
- `BoundedFanOutTest` has a few tests beyond (a)-(h): `Error`/null result rethrow, MDC clearing on a pooled (single-thread) executor, interrupted caller, and `map` after `close`. Test (c) uses a single blocking input so it does not depend on a second task finishing before a 200 ms deadline.
- The check that `FavouriteWeatherFailure` names are `ErrorCode` names lives in `api.v1.dto.FavouriteWeatherResponseJsonTest` (and `FavouritesWeatherOpenApiDocumentTest`), so the `favourites` test package does not depend on `api.error`.
- `FavouriteWeatherServiceTest` uses a Mockito-mocked `FavouriteRepository` (the existing `FavouriteServiceTest` pattern) instead of an in-memory fake; the provider is a hand-written thread-safe fake over the stub. Its timeout case uses a 1 s budget with one item blocked until interrupted (upper-bound timing only).
- The `listFavouritesWeather` description also notes that PATCH/DELETE on the path hit `{id}` and are 400 (A-1), besides the `FavouriteId` description change.
