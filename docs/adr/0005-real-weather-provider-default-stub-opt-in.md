# 0005. Open-Meteo adapter is the default WeatherProvider; the stub is opt-in
Date: 2026-11-15 · Status: Accepted · Feature: real-weather-provider

Supersedes [0004](0004-weather-provider-port-with-stub-default.md).

## Context
ADR 0004 introduced the `WeatherProvider` port and made the deterministic `StubWeatherProvider`
the only, and therefore default, adapter. The running service returns fake data for five cities
in every environment. The `real-weather-provider` requirements settle the open decision A-1: a
real upstream (Open-Meteo, A-2) becomes the runtime default, and the stub must be selected
explicitly. Integration tests must stay deterministic and offline (ADR 0001).

Most of ADR 0004 still holds: the port, metric-only values, the service owning the clock and
the failure contract. Because ADRs are not rewritten, this ADR restates those parts so that it
is complete on its own.

## Decision
1. **Port (unchanged from 0004)**: the domain depends on `com.example.weather.provider.WeatherProvider`
   (`current(city)`, `forecast(city, startDate, days)`). Adapters always return metric values
   (deg C, m/s). Unit conversion happens only in `WeatherService`. The service owns time (`Clock`
   bean, UTC). Adapters receive `startDate` and never read the clock.
2. **Failure contract (made more precise)**:
   - unknown location, or a location the upstream returns without the data the v1 contract
     requires to identify it (e.g. no country code) → `LocationNotFoundException` → 404
     `LOCATION_NOT_FOUND`;
   - any I/O error, timeout, **any non-2xx upstream status (including 4xx such as 400/429)**,
     or a malformed 2xx body (invalid JSON, missing/null required value, unmapped code, wrong
     number of days or dates) → `WeatherProviderUnavailableException` → 503 `UPSTREAM_UNAVAILABLE`;
   - anything else is a bug → 500 `INTERNAL_ERROR`.
   - Exception messages carry no URL with a query string, no upstream body and no credential.
     Adapters log their own sanitised WARN line (see ADR 0006).
3. **Selection**: one property, `weather.provider.type`, bound from env var `WEATHER_PROVIDER`
   with default `open-meteo`. The allowed values are `stub` and `open-meteo` (exact, lower case).
   Any other value, including an empty one, fails startup with a message that names
   `weather.provider.type` and lists the allowed values.
4. **Exactly one bean**: a single `@Bean WeatherProvider` factory method in
   `provider.WeatherProviderConfiguration` switches on the validated type and returns either
   `new StubWeatherProvider()` or the `OpenMeteoWeatherProvider`. Neither adapter is a
   `@Component`. Because the bean is created from the validated `WeatherProviderProperties`,
   an invalid type always fails with the properties error and never with a misleading "no
   bean of type WeatherProvider".
5. **Stub**: `StubWeatherProvider` keeps its data and behaviour, loses `@Component`, and is used
   for offline local development (`WEATHER_PROVIDER=stub`) and tests.
6. **`it` profile**: `application-it.yml` sets `weather.provider.type: stub` and both upstream
   base URLs to the refusing loopback address `http://127.0.0.1:9` as **literals** (not
   placeholders). Tests that exercise the real adapter opt in explicitly and point it at an
   in-process fake upstream (ADR 0006).
7. Tests still simulate provider failures by overriding the bean (`@MockitoSpyBean`), with no
   test-only code in adapters.

## Consequences
- Every environment, including `./gradlew bootRun` and the compose `app` service, needs
  outbound HTTPS to `geocoding-api.open-meteo.com` and `api.open-meteo.com` unless
  `WEATHER_PROVIDER=stub` is set.
- Production can no longer silently serve fake data: the stub must be chosen on purpose.
- Open-Meteo's free tier is non-commercial and quota-limited, and its data is CC BY 4.0
  (attribution required). This is a business/licensing decision recorded as open on the PR (A-2).
- The env var `WEATHER_PROVIDER` resolves the `${...}` placeholder. Through relaxed binding it
  also appears as a scalar for the `weather.provider` prefix. The binder ignores that, because it
  cannot convert a string into the properties record and falls back to binding the record's
  fields. A unit test with a `systemEnvironment` property source covers this.
- Adding another vendor means adding a value to the allowed set and a branch to the factory.

## Alternatives considered
- **Keep the stub as the default and opt into the real provider**: rejected (A-1). Prod would
  keep serving fake data whenever someone forgets the setting.
- **`@ConditionalOnProperty` on both adapters** (suggested in 0004): with an invalid value no
  bean exists and startup fails, but the failure depends on bean creation order. It may be the
  "no qualifying bean" error, which does not name the property or the allowed values (AC-3).
  It also spreads the allowed values over two annotations.
- **Bind the type to a Java enum**: Spring's error lists `OPEN_METEO, STUB` (constant names),
  not the configuration values `open-meteo` and `stub`.
- **Spring profiles (`stub` profile)**: mixes environment selection with adapter selection and
  makes "exactly one bean" depend on profile combinations.
