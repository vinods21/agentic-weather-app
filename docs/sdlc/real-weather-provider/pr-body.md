## Summary
Replaces the fixed weather data with a real upstream,
[Open-Meteo](https://open-meteo.com/) (geocoding API plus forecast API, no API key), behind the
existing `WeatherProvider` interface. This settles decision A-1 from
`ui-backend-api-contracts`: Open-Meteo is now the default provider, and the fixed data is opt-in
with `WEATHER_PROVIDER=stub`. The `it` profile always uses the stub. The provider is chosen by a
validated `weather.provider.type` setting, and an unknown value stops startup with a clear
message.

Calls go through Spring's `RestClient` on the JDK `HttpClient`, with connect and read timeouts,
no retries, no redirects and a 1 MiB body cap. Failures map to the existing v1 errors:
- an unknown city returns 404 `LOCATION_NOT_FOUND`;
- a timeout, a refused connection, a non-2xx status or a malformed response returns 503
  `UPSTREAM_UNAVAILABLE`, with one sanitised WARN line and no upstream URLs or query strings in
  logs or exceptions.

The `/api/v1` contract and `openapi-v1.json` are unchanged. There are no new dependencies,
migrations or Redis keys. Integration tests run against a local fake Open-Meteo built on the
JDK's `HttpServer` (127.0.0.1, random port); there is no real internet and no Testcontainers.

## Docs
- Requirements: [requirements.md](docs/sdlc/real-weather-provider/requirements.md)
- Design: [design.md](docs/sdlc/real-weather-provider/design.md)
- Test report: [test-report.md](docs/sdlc/real-weather-provider/test-report.md)
- Status and deviations: [status.md](docs/sdlc/real-weather-provider/status.md)
- ADRs:
  - [0005 Real weather provider by default, stub opt-in](docs/adr/0005-real-weather-provider-default-stub-opt-in.md)
    (supersedes [0004](docs/adr/0004-weather-provider-port-with-stub-default.md))
  - [0006 Outbound HTTP to the weather upstream](docs/adr/0006-outbound-http-to-weather-upstream.md)
- Architecture overview (updated): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Provider selection and config
- [x] AC-1: with no `WEATHER_PROVIDER` set, there is exactly one `WeatherProvider` bean and it
  is the Open-Meteo adapter.
- [x] AC-2: `WEATHER_PROVIDER=stub` gives exactly one bean, the stub, and it answers without an
  upstream.
- [x] AC-3: an unknown provider type fails startup, naming `weather.provider.type` and the
  allowed values.
- [x] AC-4: the provider type, both base URLs and both timeouts are bound from env-var
  placeholders, and are documented in `.env.example` and `compose.yaml`.
- [x] AC-5: the `it` profile resolves to the stub with loopback base URLs, and the existing ITs
  pass.

Real provider, success (fake upstream)
- [x] AC-6: current weather maps the upstream fields, and the response matches the v1 schema.
- [x] AC-7: imperial values are converted locally; the upstream is always asked for metric
  (`celsius`, wind in `ms`).
- [x] AC-8: a 3-day forecast is requested with UTC `start_date`/`end_date` and mapped day by
  day.
- [x] AC-9: city case and padding are handled, and the upstream's spelling of the name is
  returned.
- [x] AC-10: the city is URL-encoded, so it can't inject query parameters.
- [x] AC-11: every Open-Meteo weather code maps to a `ConditionCode` with a description.
- [x] AC-12: values are rounded and clamped to the contract's ranges.

Not found (404)
- [x] AC-13: no geocoding results returns 404 `LOCATION_NOT_FOUND`, and no forecast call is
  made.
- [x] AC-14: a result with no country code returns 404.

Upstream failure (503)
- [x] AC-15: a read timeout gives a 503 quickly.
- [x] AC-16: a refused connection gives a 503.
- [x] AC-17: an upstream 500, 502, 503, 429 or 400 gives a 503.
- [x] AC-18: malformed JSON, a missing field or a null required field gives a 503.
- [x] AC-19: too few days, too many days or shifted forecast dates give a 503.
- [x] AC-20: a null precipitation probability becomes 0; any other null daily field gives a 503.

Logging and contract
- [x] AC-21: the adapter writes exactly one sanitised WARN line per failed upstream call, with
  the request id, which call failed and the elapsed ms.
- [x] AC-22: no INFO-or-above log line contains an upstream URL with a query string.
- [x] AC-23: the OpenAPI drift and conformance checks pass against the real provider, and
  `openapi-v1.json` is unchanged.

## Needs a human decision
- **A-2, Open-Meteo licence:** the free tier is non-commercial only and allows about 10k calls a
  day. Each API request makes 2 calls, so that's about 5k requests a day until caching lands.
  The data is CC BY 4.0, so attribution must be shown by the UI or the docs; the frozen v1
  contract can't carry it.
- **Outbound internet:** `bootRun` and the compose `app` service now need outbound internet
  unless `WEATHER_PROVIDER=stub` is set.
- **A-4, timeouts:** connect timeout 2s, read timeout 3s, no retries. The worst case is about
  10s per API request.
- **A-5:** every non-2xx upstream response, including 4xx, maps to 503.
- **A-6, A-8:** a result with no country code (or one that isn't two letters) gives 404, and a
  null precipitation probability becomes 0.
- **Test placement:** `WeatherProviderSelectionIT` uses an `ApplicationContextRunner` with the
  environment isolated. It is in `src/integrationTest` rather than `src/test`, to keep the
  CLAUDE.md rule "unit tests: no Spring infra". `OpenMeteoWeatherProviderTest` in `src/test`
  uses `MockRestServiceServer`, which has no Spring context; please confirm that fits the rule.
- **Not verified against the real API:** CI never calls the real Open-Meteo, so field names, TLS
  and quota behaviour are unverified. A manual smoke test with `./gradlew bootRun` and
  `curl "localhost:8080/api/v1/weather/current?city=London"` is advisable before relying on it.

## How to test locally
```bash
scripts/test-env.sh all   # starts postgres + redis, runs ./gradlew build + integrationTest
```
Last run: 289 unit tests and 188 integration tests, 0 failures, 0 skipped (Docker Compose
services, fake upstream, no Testcontainers).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
