## Summary
Defines and implements the v1 HTTP API contract that the UI uses to call the backend. It adds two
read endpoints, `GET /api/v1/weather/current` and `GET /api/v1/weather/forecast`. Both return
JSON with a fixed shape, check their inputs, and report errors in one shared format: RFC 9457
Problem Details with `code`, `requestId` and `errors`, using six fixed error codes. The PR also
adds:
- an `X-Request-Id` header on every `/api/` response, which is also written to the logs;
- CORS that rejects every origin unless an allow-list is configured
  (`WEATHER_API_CORS_ALLOWED_ORIGINS`);
- a hand-written OpenAPI 3.0.3 document served at `/api/v1/openapi.json`.

Integration tests fail if the endpoints and the spec drift apart. Weather data comes through a
`WeatherProvider` interface; for now its only implementation returns fixed, in-memory data for
five cities. There are no new dependencies, Flyway migrations or Redis keys.

## Docs
- Requirements: [requirements.md](docs/sdlc/ui-backend-api-contracts/requirements.md)
- Design: [design.md](docs/sdlc/ui-backend-api-contracts/design.md)
- Test report: [test-report.md](docs/sdlc/ui-backend-api-contracts/test-report.md)
- ADRs:
  - [0002 Spec-first, hand-written OpenAPI](docs/adr/0002-spec-first-hand-written-openapi.md)
  - [0003 HTTP API conventions](docs/adr/0003-http-api-conventions.md)
  - [0004 WeatherProvider port with stub default](docs/adr/0004-weather-provider-port-with-stub-default.md)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Current weather
- [x] AC-1: `GET /api/v1/weather/current?city=London` returns 200 with the current-weather body
  (metric).
- [x] AC-2: `units=imperial` returns values converted from metric.
- [x] AC-3: the same request made twice returns the same data.
- [x] AC-4: whitespace around `city` is trimmed.

Forecast
- [x] AC-5: `forecast?city=London&days=3` returns 3 consecutive UTC days.
- [x] AC-6: without `days`, the forecast has 5 days in metric.

Validation (400)
- [x] AC-7: each invalid parameter returns 400 `VALIDATION_FAILED` with field errors.
- [x] AC-8: every invalid field is reported together (missing city, bad units, `days` out of
  range).
- [x] AC-9: the 100-character limit on `city` is inclusive and is checked after trimming.

Not found / method (404, 405)
- [x] AC-10: an unknown city returns 404 `LOCATION_NOT_FOUND`.
- [x] AC-11: an unknown `/api/v1` path returns 404 `NOT_FOUND` as Problem Details.
- [x] AC-12: `POST` returns 405 with `Allow: GET`.

Provider failure / internal error (503, 500)
- [x] AC-13: when the provider is unavailable, the API returns 503 `UPSTREAM_UNAVAILABLE` and
  logs a WARN line.
- [x] AC-14: an unexpected exception returns 500 `INTERNAL_ERROR`; the body leaks no message or
  class name, and the error is logged.

Request id
- [x] AC-15: a valid `X-Request-Id` is sent back on every response.
- [x] AC-16: a missing or invalid id is replaced with a newly generated UUID.

CORS
- [x] AC-17: a preflight from an allowed origin gets the CORS headers, and `X-Request-Id` is
  exposed.
- [x] AC-18: a preflight from any other origin is rejected (403, no allow-origin header).

OpenAPI document
- [x] AC-19: `GET /api/v1/openapi.json` returns 200 with an OpenAPI 3 JSON document.
- [x] AC-20: each weather operation documents its parameters, its 200 schema, and its
  400/404/500/503 responses.
- [x] AC-21: drift guard: the endpoints the app actually serves match the operations in the spec.
- [x] AC-22: contract conformance: real responses match the spec's schemas.

Regression
- [x] AC-23: `/actuator/health` is unaffected.

## Needs a human decision
- **A-1, fixed weather data:** the fixed-data provider is the default and only provider in every
  environment, so production would serve fake weather until a real source exists.
- **A-8, CORS origin:** which origin does the UI dev server run on? `http://localhost:5173` is
  only an example, and the allow-list is empty by default.
- **Defaults to confirm:**
  - no authentication in v1;
  - the version goes in the URL;
  - `metric` is the default unit, and unit values are case-sensitive;
  - `days` is 1 to 7, default 5;
  - unknown query parameters are ignored;
  - city matching ignores case.
- **Stricter `days` parsing:** `days` now accepts only plain whole numbers. Hex values like
  `0x3` and `#3`, which Spring used to accept, now get a 400.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 139 unit tests and 104 integration tests, 0 failures, 0 skipped (Docker Compose
services, no Testcontainers).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
