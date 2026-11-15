# real-weather-provider: SDLC status
Request: Real weather provider: replace the fixed weather data with a real upstream provider behind WeatherProvider (settles decision A-1 from ui-backend-api-contracts).
Branch: feature/real-weather-provider · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-23)
- 2026-11-15 architect: design.md written; ADR 0005 (supersedes 0004) and ADR 0006 added; overview.md updated
- 2026-11-15 developer: design steps 1-6 implemented (codes, mapper, properties + yml, Open-Meteo adapter, provider selection, env docs) with unit tests; ./gradlew build and scripts/test-env.sh it green (existing ITs on the stub). Steps 7-10 (FakeOpenMeteo, AC ITs) and WeatherProviderSelectionTest left to the tester.

## Deviations from design
- `OpenMeteoWeatherProvider#call`: the `exchange(...)` function only reads status + capped body bytes; JSON parsing and mapping run after `exchange` returns. Reason: `RestClient` wraps any `IOException` thrown inside the exchange function (including Jackson's `JsonProcessingException`) into `ResourceAccessException`, which would log invalid JSON as `exception=...` instead of `invalid-response reason=invalid JSON`. The catch is on `IOException` (covers `JsonProcessingException`, the only checked exception left there). Behaviour and log format are as designed.
- `OpenMeteoMapper#toLocation`: a `results[0]` that is not a JSON object → invalid response (503); a non-string `country_code` → not found (404, same as missing). The design does not cover these cases.
- `WeatherProviderProperties.OpenMeteo`: the URL scheme check is case-insensitive and the scheme is normalised to lower case (e.g. `HTTPS://host/` → `https://host`).
- `WeatherProviderSelectionTest` (design AC-1..3 unit test with `ApplicationContextRunner`) was not added by the developer, per orchestrator instruction (CLAUDE.md "unit tests: no Spring infra"); the tester owns it. Manually checked instead: `WEATHER_PROVIDER=stub` exported as an env var does not break binding of `weather.provider` (ADR 0005 relaxed-binding note), and the `it` literal `stub` wins over an exported `WEATHER_PROVIDER=bogus`.
