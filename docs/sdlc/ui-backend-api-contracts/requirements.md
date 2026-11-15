# UI-backend API contracts: Requirements
Feature slug: ui-backend-api-contracts · Status: draft

## Problem / goal
The service is a skeleton: there are no HTTP endpoints apart from Actuator health. A UI
team cannot start building because nothing defines the shape of the API it will call: no
paths, no request parameters, no response bodies, no error format.

Goal: define **and implement** the first version of the HTTP contract the UI uses. That
means a versioned REST API (`/api/v1`) with:
- typed request/response DTOs and input validation,
- one error format used by every failure,
- a machine-readable OpenAPI 3 document that matches the implementation and is checked by a test.

Weather data comes from a `WeatherProvider` abstraction. This PR ships a deterministic
in-process **stub** adapter, so the contract can be built and tested end to end without a
real upstream weather API (see Assumptions A-1).

## User stories
- As a UI developer, I want a versioned, documented API for current weather and forecasts,
  so that I can build screens against a stable contract.
- As a UI developer, I want a machine-readable OpenAPI document, so that I can generate a
  typed client and mock server instead of hand-writing them.
- As a UI developer, I want every error in the same JSON shape with a stable machine-readable
  code, so that the UI can handle errors in one place and show the right message.
- As a UI developer running the UI on a different origin, I want CORS to be configurable, so
  that the browser can call the API in dev and prod.
- As an operator, I want each request to carry a request id that appears in responses and
  logs, so that I can trace a UI-reported error to a log line.

## Contract summary (normative for the ACs below)

### Common conventions
- Base path `/api/v1`. JSON property names in camelCase. Timestamps are ISO-8601 UTC with `Z`
  (e.g. `2026-11-15T10:00:00Z`), dates are `YYYY-MM-DD`.
- Success responses: `Content-Type: application/json`.
- Error responses: `Content-Type: application/problem+json`, RFC 9457 Problem Details body:

```json
{
  "type": "https://weather.example.com/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more request parameters are invalid.",
  "instance": "/api/v1/weather/current",
  "code": "VALIDATION_FAILED",
  "requestId": "3f2b1c9e-...",
  "errors": [ { "field": "city", "message": "must not be blank" } ]
}
```
  `errors` is present only for `VALIDATION_FAILED`. `type` URIs are identifiers and need not resolve.
- Error codes (closed set for v1):

| code | HTTP status | when |
|------|-------------|------|
| `VALIDATION_FAILED` | 400 | missing/invalid query parameter |
| `LOCATION_NOT_FOUND` | 404 | provider has no data for the requested city |
| `NOT_FOUND` | 404 | no endpoint at that path under `/api/` |
| `METHOD_NOT_ALLOWED` | 405 | path exists, HTTP method does not |
| `UPSTREAM_UNAVAILABLE` | 503 | weather provider failed or timed out |
| `INTERNAL_ERROR` | 500 | any other unhandled exception |

- Request id header: `X-Request-Id`. If the client sends one that matches `^[A-Za-z0-9._-]{1,64}$`,
  it is echoed back. Otherwise (missing or invalid) the server generates a UUID. Every
  response under `/api/` carries the header.

### Endpoints
1. `GET /api/v1/weather/current?city={city}&units={units}`
   - `city`: required. Trimmed, then 1..100 characters.
   - `units`: optional, `metric` (default) or `imperial`. Matched case-sensitively.
   - 200 body:
```json
{
  "location": { "name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278 },
  "observedAt": "2026-11-15T10:00:00Z",
  "units": "metric",
  "temperature": 11.2,
  "feelsLike": 9.8,
  "humidity": 81,
  "windSpeed": 4.1,
  "windDirection": 230,
  "condition": { "code": "CLOUDY", "description": "Overcast clouds" }
}
```
   - Units: `metric` = deg C and m/s; `imperial` = deg F and mph. `humidity` is an integer 0..100
     (percent). `windDirection` is an integer 0..359 (degrees). `country` is ISO 3166-1 alpha-2.
   - `condition.code` is one of `CLEAR`, `PARTLY_CLOUDY`, `CLOUDY`, `FOG`, `DRIZZLE`, `RAIN`,
     `SNOW`, `THUNDERSTORM`.
2. `GET /api/v1/weather/forecast?city={city}&units={units}&days={days}`
   - `city`, `units`: as above. `days`: optional integer 1..7, default 5.
   - 200 body:
```json
{
  "location": { "name": "London", "country": "GB", "latitude": 51.5074, "longitude": -0.1278 },
  "units": "metric",
  "days": [
    { "date": "2026-11-15", "minTemperature": 6.0, "maxTemperature": 12.5,
      "precipitationProbability": 40, "condition": { "code": "RAIN", "description": "Light rain" } }
  ]
}
```
   - `days` is ordered by `date` ascending, starts at the current UTC date, has no gaps, and
     its length equals the requested `days`. `precipitationProbability` is an integer 0..100.
3. `GET /api/v1/openapi.json`: the OpenAPI 3.x document for the API, `Content-Type: application/json`.

All fields shown in the bodies above are always present and non-null in v1.

## Acceptance criteria

### Current weather
- **AC-1**: Given the stub provider, when `GET /api/v1/weather/current?city=London` is called,
  then the response is 200 with `Content-Type: application/json`, and the body contains every
  field in the contract summary with `location.name = "London"`, `location.country = "GB"`,
  `units = "metric"`, `humidity` in 0..100, `windDirection` in 0..359, `condition.code` from the
  allowed set, and `observedAt` an ISO-8601 UTC timestamp ending in `Z`.
- **AC-2**: Given the stub provider, when `GET /api/v1/weather/current?city=London&units=imperial`
  is called, then the response is 200 with `units = "imperial"`, and `temperature` equals the
  metric `temperature` for the same city converted with F = C x 9/5 + 32 (within 0.1).
- **AC-3**: Given the stub provider, when the same current-weather request is made twice, then
  both bodies are identical apart from `observedAt` (the stub is deterministic per city and units).
- **AC-4**: Given `city=%20%20London%20` (surrounding whitespace), when current weather is
  requested, then the response is 200 and `location.name = "London"`.

### Forecast
- **AC-5**: Given the stub provider, when `GET /api/v1/weather/forecast?city=London&days=3` is
  called, then the response is 200, `days` has exactly 3 entries, `days[0].date` is today's UTC
  date, dates are consecutive ascending, and each entry has `minTemperature <= maxTemperature`,
  `precipitationProbability` in 0..100 and a `condition.code` from the allowed set.
- **AC-6**: Given no `days` parameter, when `GET /api/v1/weather/forecast?city=London` is
  called, then the response is 200 and `days` has exactly 5 entries and `units = "metric"`.

### Validation (400)
- **AC-7**: Given each of these requests, when called, then the response is 400 with
  `Content-Type: application/problem+json`, `code = "VALIDATION_FAILED"`, `status = 400`, and an
  `errors` array containing an entry whose `field` is the named parameter:
  - `/api/v1/weather/current` (no `city`) → `city`
  - `/api/v1/weather/current?city=%20%20` (blank) → `city`
  - `/api/v1/weather/current?city=` + 101 `a` characters → `city`
  - `/api/v1/weather/current?city=London&units=kelvin` → `units`
  - `/api/v1/weather/current?city=London&units=METRIC` → `units`
  - `/api/v1/weather/forecast?city=London&days=0` → `days`
  - `/api/v1/weather/forecast?city=London&days=8` → `days`
  - `/api/v1/weather/forecast?city=London&days=abc` → `days`
- **AC-8**: Given `/api/v1/weather/forecast?units=kelvin&days=99` (no city), when called, then
  the response is 400 and `errors` contains entries for all three of `city`, `units` and `days`
  (all violations are reported, not only the first).
- **AC-9**: Given a city of exactly 100 characters that the provider does not know, when current
  weather is requested, then the response is 404 `LOCATION_NOT_FOUND` (not 400). This shows the
  length limit is inclusive.

### Not found / method (404, 405)
- **AC-10**: Given the stub provider has no data for `Atlantis`, when
  `GET /api/v1/weather/current?city=Atlantis` or `GET /api/v1/weather/forecast?city=Atlantis` is
  called, then the response is 404, `application/problem+json`, `code = "LOCATION_NOT_FOUND"`,
  and `detail` contains `Atlantis`.
- **AC-11**: When `GET /api/v1/does-not-exist` is called, then the response is 404,
  `application/problem+json`, `code = "NOT_FOUND"`, and `instance = "/api/v1/does-not-exist"`.
- **AC-12**: When `POST /api/v1/weather/current?city=London` is called, then the response is 405,
  `application/problem+json`, `code = "METHOD_NOT_ALLOWED"`, and an `Allow` header that includes `GET`.

### Upstream failure / internal error (503, 500)
- **AC-13**: Given the `WeatherProvider` throws its "provider unavailable" exception (simulated
  in the test by replacing the provider bean), when current weather or forecast is requested
  for `London`, then the response is 503, `application/problem+json`,
  `code = "UPSTREAM_UNAVAILABLE"`, and a WARN log line containing the request id is written.
- **AC-14**: Given the `WeatherProvider` throws an unexpected `RuntimeException` with message
  `boom-secret`, when current weather is requested, then the response is 500 with
  `code = "INTERNAL_ERROR"`, the body contains neither `boom-secret` nor any Java class name or
  stack trace, and an ERROR log line containing the request id and the stack trace is written.

### Request id
- **AC-15**: Given the request header `X-Request-Id: ui-abc-123`, when any `/api/v1` endpoint is
  called (success or error), then the response header `X-Request-Id` is `ui-abc-123`, and for
  error responses the body `requestId` is `ui-abc-123`.
- **AC-16**: Given no `X-Request-Id` header, or the header `X-Request-Id: bad id!` (contains
  invalid characters), when any `/api/v1` endpoint is called, then the response carries an
  `X-Request-Id` header holding a freshly generated UUID (not the invalid value), and error
  bodies have the same value in `requestId`.

### CORS
- **AC-17**: Given `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173`, when a preflight
  `OPTIONS /api/v1/weather/current` with `Origin: http://localhost:5173` and
  `Access-Control-Request-Method: GET` is sent, then the response is 200 (or 204) with
  `Access-Control-Allow-Origin: http://localhost:5173`, and `Access-Control-Expose-Headers`
  includes `X-Request-Id`.
- **AC-18**: Given the same configuration, when the same preflight is sent with
  `Origin: http://evil.example`, then the response has no `Access-Control-Allow-Origin` header
  (Spring's default rejection, 403). Given the variable is unset (the default), every origin is
  rejected this way.

### OpenAPI document
- **AC-19**: When `GET /api/v1/openapi.json` is called, then the response is 200, JSON, with
  `openapi` starting with `3.`, `info.version = "1.0.0"`, and `paths` containing
  `/api/v1/weather/current` and `/api/v1/weather/forecast` (GET each).
- **AC-20**: Given the OpenAPI document, then for each weather operation it documents: the
  `city`, `units` and `days` (forecast only) parameters with their required flag, enum/range
  and default; the 200 response schema with every field in the contract summary as `required`;
  and the 400, 404, 500 and 503 responses with `application/problem+json` referencing one shared
  `Problem` schema whose `code` enum is exactly the six codes in the table.
- **AC-21**: (drift guard) Given the running application, when a test compares the set of
  `(HTTP method, path)` pairs mapped under `/api/v1` (excluding `/api/v1/openapi.json`) with the
  operations in the OpenAPI document, then the two sets are equal. The test fails if an endpoint
  is added or removed without updating the spec.
- **AC-22**: (contract conformance) Given the 200 responses from AC-1 and AC-5 and the 400 and
  404 responses from AC-7 and AC-10, when each body is checked against the matching schema in
  the OpenAPI document (required properties present, no undocumented top-level properties), then
  all of them conform.

### Regression
- **AC-23**: When `GET /actuator/health` is called, then the response is still 200 with
  `status = "UP"` and is not wrapped in the Problem format or affected by `/api/v1` handling.

## Non-functional requirements
- **Security**: error bodies never contain exception messages from unexpected errors, class
  names, stack traces, SQL or config values (AC-14). The API is read-only (GET only) and
  unauthenticated in v1 (A-4). CORS is deny-by-default (AC-18).
- **Observability**: every `/api/v1` request is logged once at INFO with method, path, status,
  duration in ms and request id. The request id is in the logging MDC under `requestId` for the
  duration of the request, so all log lines for a request can be correlated.
- **Compatibility**: the v1 contract is additive-only after merge. Removing or renaming fields,
  codes or parameters requires `/api/v2`. Clients must ignore unknown response fields (documented
  in the OpenAPI `info.description`).
- **Performance**: no latency target in this PR, because the stub has no I/O. Targets come with
  the real provider adapter (A-1).
- **Data retention**: none. This feature adds no tables and no Redis keys.

## Out of scope
- A real upstream weather provider adapter (HTTP client, API key, timeouts, retries). The
  provider interface and its unavailable-exception are in scope; real adapters are not.
- Redis caching of weather responses (expected as a follow-up, e.g. `city-forecast-cache`).
- Persistence: saved locations/favourites, user preferences, history.
- Authentication, authorization, rate limiting, API keys.
- Swagger UI or any hosted HTML docs. Only the JSON document is served.
- Generating a TypeScript/JS client, or any UI code.
- Lookup by coordinates, postcode or city id; city disambiguation (e.g. "Paris, US"); search
  or autocomplete endpoints.
- Hourly forecasts, alerts, air quality, sunrise/sunset, localisation of `condition.description`.
- Content negotiation beyond JSON (e.g. XML, 406 handling), ETags and HTTP caching headers.

## Open questions / assumptions
Each item needs a human decision on the PR. Where none is given, the assumption stands.

- **A-1 (data source)**: no weather provider exists yet. I assumed this PR ships a
  `WeatherProvider` port plus a deterministic in-memory stub. The stub knows a fixed set of cities
  that includes at least `London` (GB) and treats every other city, including `Atlantis`, as not
  found. It is the default bean until a real adapter feature replaces it. *Decision needed:*
  is a stub acceptable as the runtime default, or should it be active only in a `stub`/`dev`
  profile? (With a profile, the app would have no provider in prod until the follow-up lands.)
- **A-2 (OpenAPI tooling, possible new dependency)**: AC-19..AC-22 need an OpenAPI document,
  and nothing in `build.gradle.kts` provides one today. The architect must choose, and record
  an ADR if a dependency is added:
  (a) **hand-written** `openapi.json`/`.yaml` under `src/main/resources`, served by a small
      controller. No new dependency; AC-21/AC-22 tests keep it honest.
  (b) **springdoc-openapi** (`org.springdoc:springdoc-openapi-starter-webmvc-api`), generated
      from annotations. This is a new dependency and needs an ADR. Its default path is
      `/v3/api-docs`, so it would have to be configured to serve `/api/v1/openapi.json`.
  AC-22 (schema conformance) can be done with Jackson tree checks against the spec without a
  JSON-schema validator library. Adding a validator library is also a dependency decision.
  The PM preference is (a) for a spec-first contract, but this is the architect's call.
- **A-3 (error format)**: RFC 9457 Problem Details, using Spring 6's built-in `ProblemDetail`
  (no new dependency), extended with `code`, `requestId` and `errors`.
- **A-4 (auth)**: v1 is public and read-only. Authentication is a separate feature.
- **A-5 (versioning)**: version in the URL path (`/api/v1`), not in a header or media type.
  The OpenAPI `info.version` starts at `1.0.0`.
- **A-6 (units)**: `metric` default. Values are case-sensitive lowercase (`METRIC` is a 400, AC-7).
  Kelvin/"standard" is not supported.
- **A-7 (forecast length)**: `days` 1..7, default 5. The forecast starts at today's UTC date,
  not the city's local date. Local-timezone handling is deferred.
- **A-8 (CORS)**: configured by the env var `WEATHER_API_CORS_ALLOWED_ORIGINS` (comma-separated,
  mapped through `application.yml`), empty by default. Allowed methods are `GET` and `OPTIONS`.
  The developer should add the variable to `.env.example` with a dev example such as
  `http://localhost:5173`. The UI dev-server origin is unknown; please confirm.
- **A-9 (unknown query parameters)** are ignored, not rejected.
- **A-10 (404 scope)**: the Problem-format 404/405 applies to paths under `/api/`. Other paths
  keep Spring Boot's default behaviour.
- **A-11 (test environment)**: AC-1..AC-23 need the Spring context and HTTP, so they will be
  integration tests (`*IT.java`, `IntegrationTestBase`). Pure helpers such as unit conversion
  and request-id validation can also have unit tests. Note: `status.md` records that the
  Docker daemon was not running at run start, and the integration tests need
  `scripts/test-env.sh up`.
