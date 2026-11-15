# 0004. WeatherProvider port with a deterministic in-process stub as the default adapter
Date: 2026-11-15 · Status: Accepted · Feature: ui-backend-api-contracts

## Context
The UI contract has to be built and tested end to end before any real upstream weather API has
been chosen (no vendor, API key or HTTP client yet). Tests need deterministic data and a way to
simulate upstream failure.

## Decision
- The domain depends on a port `com.example.weather.provider.WeatherProvider`:
  `current(city)` and `forecast(city, startDate, days)`. It always returns metric values
  (C, m/s). Unit conversion is done in `WeatherService`, never in adapters.
- The failure contract for every adapter:
  - an unknown location throws `LocationNotFoundException` (mapped to 404 `LOCATION_NOT_FOUND`);
  - any I/O error, timeout or upstream 5xx throws `WeatherProviderUnavailableException`
    (mapped to 503 `UPSTREAM_UNAVAILABLE`). Its message is for logs only.
  - Anything else is a bug and becomes 500 `INTERNAL_ERROR`.
- `provider.stub.StubWeatherProvider` is a `@Component` and, for now, the only and default bean.
  It holds fixed data for a small set of cities (London GB, Paris FR, New York US, Tokyo JP,
  Sydney AU), matched case-insensitively. Every other city is "not found".
- The service owns time (`Clock` bean). Adapters receive the forecast `startDate` rather than
  reading the clock.
- Tests simulate provider failures by replacing the bean (`@MockitoBean`). No test-only code goes
  into the stub.

## Consequences
- The contract, validation and error mapping are fully testable without network access.
- Until a real adapter lands, the running service returns **fake data** for five cities in every
  environment. This is acceptable only while the API is pre-production. The decision is flagged
  on the PR (requirement A-1).
- The real-adapter feature must replace the bean (e.g. via `@ConditionalOnProperty` on both
  adapters). It must also decide on timeouts, retries and caching (expected to be Redis via
  `CacheKeys`) in its own ADR.

## Alternatives considered
- **Stub only in a `stub`/`dev` profile**: production would have no `WeatherProvider` bean, so
  the context would fail to start until the follow-up lands. Rejected for v1.
- **WireMock or another fake HTTP upstream**: a new dependency, and pointless without a chosen
  upstream API.
- **Calling a real free weather API now**: out of scope (requirements), and it would make tests
  non-deterministic.
