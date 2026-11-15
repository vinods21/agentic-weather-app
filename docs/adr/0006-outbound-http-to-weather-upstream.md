# 0006. Outbound HTTP to the weather upstream: RestClient on the JDK HttpClient, bounded timeouts, no retries, sanitised logging, in-process fake for ITs
Date: 2026-11-15 · Status: Accepted · Feature: real-weather-provider

## Context
The Open-Meteo adapter (ADR 0005) is the service's first outbound HTTP integration. Several
cross-cutting choices need recording because later integrations will copy them: which HTTP
client to use, how latency is bounded, whether to retry, what may be logged, and how
integration tests fake an HTTP upstream without the internet and without Testcontainers
(ADR 0001). The requirements forbid new production dependencies, leave out retries and circuit
breakers, and need delayed responses and inspection of received query parameters in ITs.

## Decision
1. **Client**: Spring `RestClient`, built from Boot's auto-configured `RestClient.Builder`, on
   `JdkClientHttpRequestFactory` over a `java.net.http.HttpClient`. That is all
   `spring-boot-starter-web` / the JDK, so no new dependency. Each upstream (geocoding, forecast)
   gets its own `RestClient` with its own `baseUrl`. One shared `HttpClient` instance per adapter.
2. **Timeouts**: `HttpClient.connectTimeout = weather.provider.connect-timeout` (default 2 s) and
   request-factory `readTimeout = weather.provider.read-timeout` (default 3 s). Each upstream
   call is therefore bounded by about connect + read. At most 2 calls per API request.
3. **No retries, no redirects**: failed calls are not retried, so latency stays bounded and quota
   is not amplified. `HttpClient.Redirect.NEVER`: a 3xx is a non-2xx and becomes 503, and the
   client never follows a redirect to a host that isn't configured.
4. **Failure translation**: the adapter reads the response with `exchange(...)`, so status
   handling is explicit. No default status handler throws, and the body is capped at 1 MiB.
   It translates `RestClientException` (I/O, timeout), non-2xx status, JSON parse errors and
   mapping/validation failures into `WeatherProviderUnavailableException` **without a cause and
   with a fixed, sanitised message**. Spring's exception messages embed the full request URL
   with its query string, and response exceptions embed the upstream body, so neither is ever
   attached, logged or rethrown. Unexpected `RuntimeException`s (bugs) are not caught and
   become 500.
5. **Logging**: on each failed upstream call the adapter writes exactly one WARN line:
   `Upstream call failed provider=open-meteo call=<geocoding|forecast> host=<host> path=<path>
   outcome=<status=N | exception=<class> cause=<root class> | invalid-response reason=<fixed text>> elapsedMs=<n>`.
   The request id comes from the log pattern's `%X{requestId}` (ADR 0003). Never logged:
   query strings, user input, upstream bodies, exception messages, credentials. Successful
   calls are not logged at INFO or above.
6. **Encoding**: user input only enters a request as a URI-template variable. The
   `DefaultUriBuilderFactory` behind `RestClient.Builder.baseUrl` (`TEMPLATE_AND_VALUES`) encodes
   it strictly, so `&`, `=` and `+` can't inject parameters. Base URLs come only from configuration
   and are validated at startup: absolute `http`/`https`, a host, no user-info, no query or fragment.
7. **Fake upstream in integration tests**: an in-process JDK `com.sun.net.httpserver.HttpServer`
   bound to `127.0.0.1:0` (module `jdk.httpserver`, part of the JDK, so no new test dependency),
   wrapped in a small test helper (`support.FakeOpenMeteo`). It records received requests (path,
   raw query, decoded parameters), serves programmable status/body/delay per path, and has
   happy-path defaults. It is one JVM-wide instance started on first use, and `reset()` runs
   before every test. ITs point the adapter at it via `@DynamicPropertySource`. This is allowed
   under ADR 0001 because it starts no containers.

## Consequences
- No new dependencies. Behaviour is plain Spring Framework / JDK and easy to replace.
- An unresponsive upstream gives a 503 within about 2 x (connect + read). That is 10 s with the
  defaults, which is above typical browser expectations but bounded. Operators can lower it via env.
- Operators get one WARN line per failed call with status/exception class and timing. The
  API-layer WARN in `ApiExceptionHandler` now reports `cause=none` for adapter failures, since
  the detail is in the adapter's line.
- No resilience beyond timeouts: no retries, circuit breaker or rate limiting. Under an upstream
  outage every request still pays the timeout. Caching (separate feature) and a circuit breaker
  (would need its own ADR) are the follow-ups.
- The fake server's request matching, delays and fixtures are hand-written (about 150 lines of test code).

## Alternatives considered
- **WireMock (`org.wiremock:wiremock-standalone`, test scope)**: richer stubbing and
  verification, but a new dependency (with Jetty) for something the JDK server covers in a
  small helper. Can be adopted later via a new ADR if fakes grow.
- **`MockRestServiceServer`**: no real socket, so it can't exercise connect/read timeouts,
  connection refusal or the real request factory (AC-15, AC-16).
- **Apache HttpClient 5 / OkHttp / Reactor Netty `WebClient`**: new dependencies with no
  benefit at this scale.
- **Resilience4j retries/circuit breaker**: out of scope, and it adds a dependency.
- **Attach the Spring exception as cause**: keeps stack traces, but its message contains the URL
  with query string (city, coordinates) and possibly upstream bodies. Any later `log.error(..., ex)`
  would leak them.
