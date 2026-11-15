# Real weather provider: Requirements
Feature slug: real-weather-provider · Status: draft

## Problem / goal
`/api/v1/weather/current` and `/api/v1/weather/forecast` return fixed data for five cities in
every environment. `StubWeatherProvider` is the only `WeatherProvider` bean (ADR 0004, decision
A-1 in `docs/sdlc/ui-backend-api-contracts/requirements.md`). Any other city returns 404, and
the values never change.

Goal: add a real upstream adapter behind the existing `WeatherProvider` port, make it the
runtime default, and keep the stub as an opt-in adapter for local dev and tests. This settles
A-1: **the real provider is the default and the stub has to be selected explicitly** (see A-1
below).

**The `/api/v1` contract does not change.** That covers paths, parameters, response shapes,
field types and ranges, the six error codes (including 404 `LOCATION_NOT_FOUND` and 503
`UPSTREAM_UNAVAILABLE`), and `openapi-v1.json`. Every AC in `ui-backend-api-contracts` still
holds when the stub is selected.

### Assumed upstream (A-2, needs confirmation)
The upstream is assumed to be **Open-Meteo** (free, no API key). It has two APIs:
- **Geocoding**: `GET {geocodingBaseUrl}/v1/search?name=<city>&count=1&language=en&format=json`.
  It returns `results[0]` with `name`, `country_code`, `latitude` and `longitude`. If no city
  matches, `results` is missing or empty.
- **Forecast**: `GET {forecastBaseUrl}/v1/forecast?latitude=..&longitude=..` with
  `current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,weather_code`
  or `daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code`,
  plus `start_date`/`end_date`, `timezone=UTC`, `temperature_unit=celsius` and `wind_speed_unit=ms`.
  Open-Meteo's default wind unit is km/h, so `wind_speed_unit=ms` must be sent.

The ACs below talk about "the upstream" and "the fake upstream", and use Open-Meteo field names
only where a concrete fixture is needed. If a different vendor is chosen, the Open-Meteo fixtures
in AC-5..AC-9 and AC-12 change, but the observable API outcomes stay the same.

## User stories
- As a UI user, I want real current weather and forecasts for any city the upstream knows, so
  that the app is useful outside five demo cities.
- As an operator, I want to choose the provider (`stub` or real) and set upstream base URLs and
  timeouts through environment variables, so that I can configure each environment without a rebuild.
- As a UI developer, I want upstream outages and slow responses to come back quickly as the
  existing 503 `UPSTREAM_UNAVAILABLE`, so that the UI's existing error handling keeps working.
- As a developer, I want to keep using the deterministic stub locally and in tests, so that I
  can work offline and tests stay reproducible.
- As a security reviewer, I want upstream URLs with query strings and any credentials kept out of
  logs and response bodies, so that nothing sensitive leaks.

## Acceptance criteria
"Fake upstream" means an HTTP server started by the integration test on `127.0.0.1` with a
random port. The test points the geocoding and forecast base URLs at it (A-9). Unless an AC
says otherwise, the real provider is selected and the fake geocoding returns London
(`name "London"`, `country_code "GB"`, `latitude 51.50853`, `longitude -0.12574`).

### Provider selection and configuration
- **AC-1**: Given `weather.provider.type` is not set (no `WEATHER_PROVIDER` env var), when the
  application context starts, then it contains exactly one `WeatherProvider` bean and that bean
  is the real (Open-Meteo) adapter, not `StubWeatherProvider`.
- **AC-2**: Given `WEATHER_PROVIDER=stub`, when the context starts, then it contains exactly one
  `WeatherProvider` bean, `StubWeatherProvider`. `GET /api/v1/weather/current?city=London`
  returns the stub's fixed values (`temperature 11.2`, `humidity 81`), and no request reaches
  the fake upstream.
- **AC-3**: Given `WEATHER_PROVIDER=bogus`, when the context starts, then startup fails. The
  failure message names the property `weather.provider.type` and lists the allowed values
  `stub` and `open-meteo`.
- **AC-4**: Given `application.yml`, then the provider type, geocoding base URL, forecast base
  URL, connect timeout and read timeout are each bound from an env-var placeholder with a
  default: `WEATHER_PROVIDER` (default `open-meteo`), `WEATHER_PROVIDER_GEOCODING_BASE_URL`
  (default `https://geocoding-api.open-meteo.com`), `WEATHER_PROVIDER_FORECAST_BASE_URL`
  (default `https://api.open-meteo.com`), `WEATHER_PROVIDER_CONNECT_TIMEOUT` (default `2s`) and
  `WEATHER_PROVIDER_READ_TIMEOUT` (default `3s`). Each variable is documented (commented out) in
  `.env.example` and passed through to the `app` service in `compose.yaml`. The architect may
  rename them; if so, the design records the final names.
- **AC-5**: Given the `it` profile with no test-specific overrides, then `weather.provider.type`
  resolves to `stub`, and both upstream base URLs resolve to a loopback address that refuses
  connections (e.g. `http://127.0.0.1:9`), never an Open-Meteo host. These values are literals
  in `application-it.yml`, not placeholders, so variables that `build.gradle.kts` exports from
  `.env`/`.env.example` cannot override them. All existing ITs (ui-backend-api-contracts AC-1..AC-23)
  pass unchanged against the stub.

### Successful responses (real adapter against the fake upstream)
- **AC-6**: Given fake forecast `current` = `temperature_2m 11.2`, `apparent_temperature 9.8`,
  `relative_humidity_2m 81`, `wind_speed_10m 4.1`, `wind_direction_10m 230`, `weather_code 3`,
  when `GET /api/v1/weather/current?city=London` is called, then the response is 200 with
  `location = {name "London", country "GB", latitude 51.50853, longitude -0.12574}`,
  `units "metric"`, `temperature 11.2`, `feelsLike 9.8`, `humidity 81`, `windSpeed 4.1`,
  `windDirection 230`, `condition.code "CLOUDY"`, and `observedAt` an ISO-8601 UTC timestamp
  ending in `Z` taken from the service clock. The body passes the same OpenAPI schema check as
  ui-backend-api-contracts AC-22.
- **AC-7**: Given the same fixture, when `...&units=imperial` is requested, then
  `temperature = 52.2` (F = C x 9/5 + 32, within 0.1) and `windSpeed = 9.2` (mph, within 0.1).
  The request the fake upstream receives asks for Celsius and metres per second
  (`temperature_unit=celsius`, `wind_speed_unit=ms`) in both cases, so the adapter always
  returns metric and conversion stays in `WeatherService` (ADR 0004).
- **AC-8**: Given the service clock is fixed at `2026-11-15T10:00:00Z` and a fake daily fixture
  of 3 days (`2026-11-15..2026-11-17`) with `temperature_2m_min [6.0, 5.5, 4.0]`,
  `temperature_2m_max [12.5, 11.0, 9.5]`, `precipitation_probability_max [40, 20, 10]` and
  `weather_code [61, 2, 45]`, when `GET /api/v1/weather/forecast?city=London&days=3` is called,
  then the response is 200 with 3 days in date order whose values match the fixture and whose
  conditions are `RAIN`, `PARTLY_CLOUDY` and `FOG`. The fake upstream received
  `start_date=2026-11-15`, `end_date=2026-11-17` and `timezone=UTC`.
- **AC-9**: Given the fake upstream, when the city sent is `london` (lower case) or
  `%20%20London%20`, then the geocoding request's `name` parameter is `london` or `London`
  respectively (already stripped by the API layer), and the response's `location.name` is the
  upstream's canonical `"London"`.
- **AC-10**: Given the city `São Paulo&count=50` (URL-encoded by the client), when current
  weather is requested, then the fake geocoding receives exactly one `name` parameter whose
  decoded value is `São Paulo&count=50`, and `count` is `1`. User input is encoded as a single
  query value and can't inject parameters.

### Mapping and normalisation (unit tests, no Spring)
- **AC-11**: Given each upstream weather code, then the adapter maps it to `ConditionCode` as
  follows, with a non-empty English `description` (e.g. 0 → "Clear sky"):
  `0` → CLEAR; `1, 2` → PARTLY_CLOUDY; `3` → CLOUDY; `45, 48` → FOG;
  `51, 53, 55, 56, 57` → DRIZZLE; `61, 63, 65, 66, 67, 80, 81, 82` → RAIN;
  `71, 73, 75, 77, 85, 86` → SNOW; `95, 96, 99` → THUNDERSTORM. A table-driven unit test
  covers every listed code.
- **AC-12**: Given upstream values that are valid but need normalising, then the adapter returns
  values that satisfy the v1 contract: `relative_humidity_2m 80.6` → `humidity 81` (rounded
  half-up, clamped to 0..100); `wind_direction_10m 360` → `windDirection 0` (mod 360, rounded);
  `precipitation_probability_max 37.5` → `38` (rounded, clamped to 0..100); `country_code "gb"`
  → `country "GB"`.

### Not found (404)
- **AC-13**: Given the fake geocoding returns 200 with no `results` property, or with
  `"results": []`, when `GET /api/v1/weather/current?city=Atlantis` or
  `/api/v1/weather/forecast?city=Atlantis` is called, then the response is 404
  `application/problem+json` with `code "LOCATION_NOT_FOUND"` and a `detail` containing `Atlantis`,
  and no forecast request is sent to the fake upstream.
- **AC-14**: Given the fake geocoding's first result has no `country_code` (or a blank one),
  when current weather is requested, then the response is 404 `LOCATION_NOT_FOUND` (A-6).

### Upstream failure (503)
For each case below the response is 503 `application/problem+json` with
`code "UPSTREAM_UNAVAILABLE"` and the fixed `detail` "The weather provider is temporarily
unavailable." It is the body ui-backend-api-contracts AC-13 already specifies, and the existing
WARN line with the request id is written.
- **AC-15**: Given the read timeout is set to `1s` and the fake upstream waits 5 s before answering
  the geocoding request (and, in a second case, the forecast request), when current weather is
  requested, then the response is 503 and arrives less than 3 s after the request was sent.
- **AC-16**: Given the geocoding base URL points at a port where nothing is listening, when
  current weather or forecast is requested, then the response is 503.
- **AC-17**: Given the fake upstream answers the geocoding or forecast call with HTTP `500`,
  `502`, `503`, `429` or `400` (body `{"error":true,"reason":"x"}`), when current weather is
  requested, then the response is 503 (A-5).
- **AC-18**: Given the fake forecast answers 200 with a body that is not valid JSON, or is missing
  a field the contract needs (e.g. no `current.temperature_2m`, or `current.temperature_2m: null`),
  or has an unmapped weather code (e.g. `4`), when current weather is requested, then the
  response is 503 (A-7).
- **AC-19**: Given the fake daily fixture has fewer days than requested, or dates that don't
  start at the requested `start_date`, when `forecast?city=London&days=3` is called, then the
  response is 503. A short or shifted `days` array is never returned to the client.
- **AC-20**: Given the fake daily fixture has `precipitation_probability_max` null for a day, when
  forecast is requested, then the response is 200 and that day's `precipitationProbability` is
  `0` (A-8). A null in any other daily field (min/max temperature, weather code) gives 503, as in AC-18.

### Logging and secrets
- **AC-21**: Given any failure from AC-15..AC-19 with request header `X-Request-Id: it-rwp-21`,
  then the adapter writes exactly one WARN line per failed upstream call. The line contains
  the request id, which upstream call failed (`geocoding` or `forecast`), the HTTP status or
  exception class, and the elapsed time in ms. The captured log output for that request does
  not contain `?`-query strings of upstream URLs (`name=`, `latitude=`, `longitude=`), and
  neither does the message of any `WeatherProviderUnavailableException` thrown. The response
  body contains no upstream host, URL, status or exception text.
- **AC-22**: Given a successful real-provider request, then no log line at INFO or above
  contains an upstream URL with a query string. (Logging the path, such as `/v1/forecast`, and
  the host is allowed.)

### Contract regression
- **AC-23**: Given the real provider against the fake upstream, when the existing OpenAPI drift
  and conformance checks (ui-backend-api-contracts AC-21, AC-22) run, then they pass, and
  `openapi-v1.json` is byte-for-byte unchanged by this feature.

## Non-functional requirements
- **Latency bound**: each upstream call is bounded by the connect timeout plus the read timeout.
  One API request makes at most 2 upstream calls (geocoding, then forecast) and no retries
  (A-4). With the defaults (2 s / 3 s), an unresponsive upstream gives a 503 within 10 s. AC-15
  verifies this with a short timeout.
- **Security**: HTTPS default base URLs. City input only goes into an encoded query value
  (AC-10). Upstream URLs with query strings, response bodies and any credential never go into
  logs, exception messages or API responses (AC-21, AC-22). The free Open-Meteo tier needs no
  credential. If a credential (e.g. an Open-Meteo commercial `apikey`) is ever added, it must
  come from an env var with no default and be covered by the same AC-21 assertion.
- **Dependencies**: no new production dependency. Use the HTTP client already in
  `spring-boot-starter-web` (`RestClient`/JDK `HttpClient`). A new **test** dependency for the fake
  upstream is the architect's decision (A-9).
- **Observability**: the existing WARN line in `ApiExceptionHandler` stays as it is, and the
  adapter's WARN from AC-21 adds the sanitised detail.
- **Data retention**: none. No tables, no Redis keys.
- **Upstream quota**: Open-Meteo's free tier is limited (about 10,000 calls/day, non-commercial
  only; A-2). Each API request costs 2 calls, so the limit is about 5,000 API requests/day until
  caching lands.

## Out of scope
- Caching of geocoding or forecast results in Redis (separate feature, e.g. `city-forecast-cache`).
  When it lands, it removes most of the per-request upstream calls.
- Retries, circuit breakers, bulkheads and rate limiting towards the upstream.
- An actuator health indicator for the upstream. Readiness must not depend on the upstream.
- Exposing `/actuator/metrics` or adding custom upstream metrics.
- Supporting a second real vendor, vendor failover, or an API-key/commercial Open-Meteo plan.
- Using the upstream's observation time for `observedAt` (the service clock stays the source, A-10),
  local-timezone forecast days, or more than 7 days.
- City disambiguation ("Paris, US"), lookup by coordinates, and localised names or descriptions.
- Any change to the `/api/v1` contract or `openapi-v1.json`.
- Removing `StubWeatherProvider`.

## Open questions / assumptions
Each item needs a human decision on the PR. If none is given, the assumption stands.

- **A-1 (settles ui-backend-api-contracts A-1)**: the real provider is the default
  (`WEATHER_PROVIDER` unset → `open-meteo`) in every environment, including `./gradlew bootRun`
  and the compose `app` service, which therefore need outbound internet. `stub` is opt-in for
  offline dev and is fixed for the `it` profile (AC-5). The alternative is to keep the stub as
  default and opt into the real provider per environment. I rejected it because prod would keep
  silently serving fake data. ADR 0004's "stub is the default" bullet is superseded; the
  architect should record this in a new ADR.
- **A-2 (vendor)**: Open-Meteo, chosen because it is free and needs no API key. **Confirm.**
  Its free API is licensed for **non-commercial use only** (CC BY 4.0 data, attribution
  required) with daily call limits. If this service is commercial, a paid plan (API key) or
  another vendor is needed. That would add a secret and change the fixtures, but not the
  API-facing ACs.
- **A-3 (geocoding)**: the first geocoding result (`count=1`, `language=en`) is used, so the
  upstream's relevance ranking decides ambiguous names ("Paris" → Paris, FR). No
  disambiguation.
- **A-4 (no retries)**: a failed or slow upstream call is not retried, so latency stays bounded and
  quota isn't amplified. Defaults are 2 s connect and 3 s read per call. **Confirm** the timeout
  defaults.
- **A-5 (upstream 4xx)**: any non-2xx upstream response, including 400 and 429, gives 503
  `UPSTREAM_UNAVAILABLE`, not 500. The UI can't fix it, and the closed error-code set has
  nothing better. The adapter's WARN line records the status, so a 400 caused by our own bug
  is still visible to operators.
- **A-6 (unusable geocoding result)**: a first result without `country_code` (required in the
  contract) is treated as "location not found" (404), not as an upstream failure.
- **A-7 (unmapped weather code / missing fields)**: the upstream's code set is fully mapped in
  AC-11. An unknown code or a missing required value counts as a malformed upstream response
  (503), not a guessed condition.
- **A-8 (missing precipitation probability)**: Open-Meteo can return null
  `precipitation_probability_max` for some days or locations. The v1 contract requires a non-null
  integer, so null becomes `0`. This understates the uncertainty. The alternative, 503 for the
  whole forecast, seemed worse. **Confirm.**
- **A-9 (fake upstream for ITs, possible new test dependency)**: integration tests must not reach
  the internet and must not use Testcontainers (ADR 0001). They need an in-process fake HTTP
  server on `127.0.0.1` with a random port, wired in through `@DynamicPropertySource` (or
  similar). The architect chooses between:
  (a) the JDK's built-in `com.sun.net.httpserver.HttpServer`: no new dependency, but delays,
      request capture and fixtures are hand-written;
  (b) **WireMock** (e.g. `org.wiremock:wiremock-standalone`, test scope only): richer stubbing
      and request verification, but a new dependency that needs an ADR.
  Either option has to support AC-7/AC-8/AC-10 (inspecting the received query parameters) and
  AC-15 (delayed responses). Mapping logic (AC-11, AC-12) can also be unit-tested without HTTP.
- **A-10 (time)**: `observedAt` and the forecast start date keep coming from the service clock in
  UTC (ADR 0004). The upstream's `current.time` is ignored, and the forecast is requested with
  `timezone=UTC`.
- **A-11 (two calls per request)**: without caching, every API request geocodes the city again.
  This is acceptable for this feature, since caching is a separate feature (out of scope).
- **A-12 (env-var names)**: the names in AC-4 are proposals. The architect may rename them and
  record the final names in design.md. The selection values are `stub` and `open-meteo`.
