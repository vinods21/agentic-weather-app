# Real weather provider: Design
Requirements: ./requirements.md · ADRs: 0001, 0003, 0005 (supersedes 0004), 0006

## Summary
We add an Open-Meteo adapter (`provider.openmeteo.OpenMeteoWeatherProvider`) behind the existing
`WeatherProvider` port and make it the default. `StubWeatherProvider` stays, but becomes opt-in
(`WEATHER_PROVIDER=stub`), and the `it` profile pins it as a literal (ADR 0005). A single
`@Bean` factory in `provider.WeatherProviderConfiguration` creates exactly one provider from the
validated `WeatherProviderProperties`. An invalid type fails startup with a message naming
`weather.provider.type` and the allowed values.

The adapter makes at most two calls per API request (geocoding, then forecast). It uses Spring
`RestClient` over the JDK `HttpClient`, with connect/read timeouts, no retries and no redirects.
It turns every I/O error, non-2xx status and malformed body into
`WeatherProviderUnavailableException`, with no cause and a fixed sanitised message, and writes
exactly one sanitised WARN line per failed call (ADR 0006). Mapping and normalisation live in a
pure `OpenMeteoMapper`, unit-tested without Spring.

The `/api/v1` contract, `openapi-v1.json`, `WeatherService`, `ApiExceptionHandler` and the DTOs
do not change. There are no new dependencies, tables, migrations or Redis keys. Integration
tests use an in-process JDK `HttpServer` fake (`support.FakeOpenMeteo`, A-9 option (a)).

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `WeatherProviderConfiguration#weatherProvider`, `WeatherProviderProperties`, `application.yml` | `type` defaults to `open-meteo` via `${WEATHER_PROVIDER:open-meteo}`. The factory returns `OpenMeteoWeatherProvider`. Neither adapter is a `@Component`, so there is exactly one `WeatherProvider` bean. |
| AC-2 | same, `StubWeatherProvider` | `stub` → the factory returns `new StubWeatherProvider()` and builds no HTTP client. The stub's London data is unchanged (11.2 / 81). |
| AC-3 | `WeatherProviderProperties` compact constructor | Any value other than `stub`/`open-meteo` (exact match; empty included) throws `IllegalArgumentException("weather.provider.type must be one of [stub, open-meteo] (env WEATHER_PROVIDER)")`. Binding fails, the properties bean fails, and the factory depends on it, so startup always fails with this root cause. |
| AC-4 | `application.yml`, `.env.example`, `compose.yaml` | Five placeholders with defaults (final names under "Data model / configuration"). They are commented out in `.env.example` and passed through in `compose.yaml` as `${VAR:-default}`, so an empty value never overrides the yml default. |
| AC-5 | `application-it.yml` | Literals: `type: stub`, both base URLs `http://127.0.0.1:9`, timeouts `1s`. The URL properties are nested under `weather.provider.open-meteo.*`, so relaxed binding of the exported `WEATHER_PROVIDER_*_BASE_URL` env vars can't target them, and the profile's literals win over the `${...}` in `application.yml`. Existing ITs run against the stub unchanged. |
| AC-6 | `OpenMeteoWeatherProvider#current`, `OpenMeteoMapper#toLocation/#toCurrent`, `OpenMeteoWeatherCodes` | Location comes from geocoding (`name`, upper-cased `country_code`, `latitude`, `longitude`). Values come from `current.*`. `observedAt` is still `WeatherService` + `Clock`. |
| AC-7 | `OpenMeteoWeatherProvider` request templates, `WeatherService` | Every forecast request carries `temperature_unit=celsius&wind_speed_unit=ms`. Imperial conversion stays in `WeatherService`/`UnitConverter` (11.2 → 52.2, 4.1 → 9.2). |
| AC-8 | `OpenMeteoWeatherProvider#forecast`, `OpenMeteoMapper#toDaily` | `start_date=startDate`, `end_date=startDate+days-1`, `timezone=UTC`. The mapper builds `days` entries in array order after checking dates (see AC-19). Codes 61/2/45 → RAIN/PARTLY_CLOUDY/FOG. |
| AC-9 | `CurrentWeatherQuery`/`ForecastQuery` (existing `strip()`), adapter | The adapter sends the already-stripped city unchanged as `name`. The response uses `results[0].name`. |
| AC-10 | adapter URI templates (ADR 0006 §6) | `"/v1/search?name={name}&count=1&language=en&format=json"` with `name` as a URI variable. `TEMPLATE_AND_VALUES` encoding turns `&`/`=` into `%26`/`%3D`, so `count` stays the literal `1`. |
| AC-11 | `OpenMeteoWeatherCodes` | Immutable `Map<Integer, Condition>` of exactly the 28 listed codes, each with an English description (table below). Unknown code → `Optional.empty()`. |
| AC-12 | `OpenMeteoMapper` | humidity `clamp(Math.round(v), 0, 100)`; direction `floorMod(Math.round(v), 360)`; precipitation `clamp(Math.round(v), 0, 100)`; country `strip().toUpperCase(Locale.ROOT)`. |
| AC-13 | `OpenMeteoMapper#toLocation` → `LocationNotFoundException(city)` | `results` missing, null, not an array, or empty → not found. The forecast call is never made. The existing handler builds the 404 with `detail` containing the city. |
| AC-14 | same | `country_code` missing, null, blank, or not `^[A-Z]{2}$` after upper-casing → `LocationNotFoundException` (A-6). |
| AC-15 | `OpenMeteoHttp` (`JdkClientHttpRequestFactory#setReadTimeout`) | The read timeout covers the wait for the response. A 5 s stall with `read-timeout=1s` fails after about 1 s → `ResourceAccessException` → 503. |
| AC-16 | same (`HttpClient.connectTimeout`) | Connection refused → `ResourceAccessException(ConnectException)` → 503. |
| AC-17 | `OpenMeteoWeatherProvider#call` | Uses `exchange(...)`: any non-2xx status → 503, logged as `outcome=status=<n>`. The upstream body is not read for non-2xx. |
| AC-18 | `OpenMeteoMapper` + `#call` | Invalid JSON (`JsonProcessingException`) → 503. A missing/null/non-numeric required field or an unmapped/non-integral code → `InvalidUpstreamResponseException` → 503. |
| AC-19 | `OpenMeteoMapper#toDaily` | `daily.time` and the four value arrays must all have length `== days`, and `time[i]` must equal `startDate.plusDays(i)` for every `i`. Otherwise → invalid response → 503. |
| AC-20 | `OpenMeteoMapper#toDaily` | A null `precipitation_probability_max[i]` → `0`. A null in min/max/weather_code → invalid → 503. |
| AC-21 | `OpenMeteoWeatherProvider#call` + `#unavailable` | One `log.warn` in the single catch path per call (format in ADR 0006 §5, request id via the MDC log pattern). The exception message is `"Open-Meteo <call> call failed: <outcome>"`, with no cause. Bodies are built by the existing handler from fixed text. |
| AC-22 | adapter | No INFO+ logging on success. `RestClient`/JDK client logs URLs only at DEBUG/TRACE, which are off by default. |
| AC-23 | no contract change; `RealProviderContractConformanceIT`, `RealProviderOpenApiDriftIT` | Subclasses of the existing `ContractConformanceIT`/`OpenApiDriftIT` run the same checks with the real adapter against the fake. `openapi-v1.json` is not touched (checked in review: `git diff main -- src/main/resources/openapi/` is empty). |

### Weather code table (AC-11)
| Code(s) | ConditionCode | description |
|---------|---------------|-------------|
| 0 | CLEAR | Clear sky |
| 1 / 2 | PARTLY_CLOUDY | Mainly clear / Partly cloudy |
| 3 | CLOUDY | Overcast |
| 45 / 48 | FOG | Fog / Depositing rime fog |
| 51 / 53 / 55 | DRIZZLE | Light drizzle / Moderate drizzle / Dense drizzle |
| 56 / 57 | DRIZZLE | Light freezing drizzle / Dense freezing drizzle |
| 61 / 63 / 65 | RAIN | Slight rain / Moderate rain / Heavy rain |
| 66 / 67 | RAIN | Light freezing rain / Heavy freezing rain |
| 80 / 81 / 82 | RAIN | Slight rain showers / Moderate rain showers / Violent rain showers |
| 71 / 73 / 75 | SNOW | Slight snowfall / Moderate snowfall / Heavy snowfall |
| 77 | SNOW | Snow grains |
| 85 / 86 | SNOW | Slight snow showers / Heavy snow showers |
| 95 | THUNDERSTORM | Thunderstorm |
| 96 / 99 | THUNDERSTORM | Thunderstorm with slight hail / Thunderstorm with heavy hail |

## Components & packages
All under `com.example.weather`.

### `provider` (changed / new)
| Class | Change | Responsibility |
|-------|--------|----------------|
| `provider.WeatherProvider` | changed (Javadoc only) | Point to ADR 0005. The failure contract now reads "any I/O error, timeout, non-2xx upstream status or malformed upstream response". |
| `provider.WeatherProviderProperties` | **new** | `@ConfigurationProperties("weather.provider") record WeatherProviderProperties(String type, Duration connectTimeout, Duration readTimeout, OpenMeteo openMeteo)` with nested `record OpenMeteo(URI geocodingBaseUrl, URI forecastBaseUrl)`. Constants `STUB = "stub"`, `OPEN_METEO = "open-meteo"`, `ALLOWED_TYPES = List.of(STUB, OPEN_METEO)`. Compact constructor: `type == null` → `OPEN_METEO`; type not in `ALLOWED_TYPES` (exact, case-sensitive) → `IllegalArgumentException` naming `weather.provider.type` and listing `[stub, open-meteo]` (the bad value is not echoed). Null timeouts → defaults (2 s / 3 s); zero/negative → IAE naming the property. `OpenMeteo` compact constructor: null URLs → defaults; each URL must be absolute, scheme `http`/`https`, have a host, no user-info, no query, no fragment, and a path that is empty or `/`. Otherwise IAE naming the property, **without echoing the value** (it could contain user-info). A trailing `/` is removed. |
| `provider.WeatherProviderConfiguration` | **new** | `@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties(WeatherProviderProperties.class)` (it coexists with `@ConfigurationPropertiesScan`; the registrar skips existing definitions). One `@Bean WeatherProvider weatherProvider(WeatherProviderProperties p, ObjectProvider<RestClient.Builder> builder, ObjectMapper mapper)`: `STUB` → `new StubWeatherProvider()`; `OPEN_METEO` → `OpenMeteoWeatherProvider.create(p, builder.getObject(), mapper)`. |
| `provider.stub.StubWeatherProvider` | changed | Remove `@Component`. Update the Javadoc (opt-in, ADR 0005). No behaviour change. |

### `provider.openmeteo` (new)
| Class | Responsibility |
|-------|----------------|
| `OpenMeteoWeatherProvider` (public, `implements WeatherProvider`) | Holds two `RestClient`s (geocoding, forecast), the `ObjectMapper`, and the host names for logging. `static create(props, builder, mapper)` uses `OpenMeteoHttp` to build the clients. **`current(city)`**: `loc = call(GEOCODING, geocodingClient, GEOCODING_URI, vars(city), json -> mapper.toLocation(city, json))`, then `call(FORECAST, forecastClient, CURRENT_URI, vars(lat, lon), json -> mapper.toCurrent(loc, json))`. **`forecast(city, startDate, days)`**: `days` outside 1..7 → `IllegalArgumentException` (bug → 500, as in the stub). Geocode, then the daily call with `start_date`, `end_date = startDate.plusDays(days-1)` → `mapper.toDaily(loc, json, startDate, days)`. **`call(...)`** (private, generic): times the call with `System.nanoTime()`; `client.get().uri(template, vars).accept(APPLICATION_JSON).exchange((req, res) -> ...)`. Non-2xx → outcome `status=<n>`. Otherwise reads at most 1 MiB + 1 byte from the body (oversize → `invalid-response reason=body too large`), `objectMapper.readTree(bytes)`, then applies the mapping function. Catches **only** `RestClientException` (outcome `exception=<class> cause=<root cause class>`), `JsonProcessingException`/empty body (`invalid-response reason=invalid JSON`) and `InvalidUpstreamResponseException` (`invalid-response reason=<its fixed reason>`). Each catch logs the one WARN line and throws `unavailable(call, outcome)`. `LocationNotFoundException` passes through without a log. Other `RuntimeException`s propagate (500). Coordinates are formatted with `BigDecimal.valueOf(d).toPlainString()`, so values never appear in scientific notation. |
| `OpenMeteoHttp` (package-private, static factory) | `HttpClient.newBuilder().connectTimeout(connect).followRedirects(Redirect.NEVER).build()`. Then `JdkClientHttpRequestFactory(httpClient)` with `setReadTimeout(read)`, and `builder.clone().baseUrl(url).requestFactory(factory).build()` for each base URL. Uses the default `DefaultUriBuilderFactory` (`TEMPLATE_AND_VALUES`). |
| `UpstreamCall` (package-private enum) | `GEOCODING("geocoding", "/v1/search")`, `FORECAST("forecast", "/v1/forecast")`: log name and path (for the WARN line; never a query). |
| `OpenMeteoMapper` (public final, stateless, pure) | `Location toLocation(String city, JsonNode body)`: AC-13/14 → `LocationNotFoundException(city)`. Missing/blank `name` or a non-numeric/out-of-range (±90/±180) lat/lon → `InvalidUpstreamResponseException`. `CurrentConditions toCurrent(Location, JsonNode)`: requires `current` object with numeric, finite `temperature_2m`, `apparent_temperature`, `relative_humidity_2m`, `wind_speed_10m` (≥ 0), `wind_direction_10m`, and an integral `weather_code` known to `OpenMeteoWeatherCodes`. Normalises per AC-12. `LocationForecast toDaily(Location, JsonNode, LocalDate start, int days)`: AC-19/20 rules, `time[i]` parsed with `LocalDate.parse` (parse error → invalid). Temperatures are passed through unrounded (rounding for display stays in `WeatherService` for imperial, unchanged). |
| `OpenMeteoWeatherCodes` (public final) | `static Optional<Condition> condition(int code)` from the table above. `static Set<Integer> codes()` for the test. |
| `InvalidUpstreamResponseException` (package-private, `RuntimeException`) | Fixed `reason` from code only, e.g. `"missing current.temperature_2m"`, `"unmapped weather_code"`, `"daily length mismatch"`, `"daily dates mismatch"`. The reason is never built from upstream text. |

`WeatherService`, `api.*`, DTOs and `openapi-v1.json` stay unchanged.

## API
No change. `/api/v1/weather/current` and `/api/v1/weather/forecast` keep their parameters,
response schemas, status codes and the six error codes. Mapping to the existing behaviour:

| Situation | Status / code |
|-----------|---------------|
| Geocoding has no result, or the first result has no/blank/invalid `country_code` | 404 `LOCATION_NOT_FOUND`, existing `detail` "No weather data is available for city '<city>'." |
| Connect/read timeout, connection refused, any non-2xx upstream (incl. 400, 429), invalid JSON, missing/null required field, unmapped code, wrong day count/dates, body > 1 MiB | 503 `UPSTREAM_UNAVAILABLE`, fixed `detail` "The weather provider is temporarily unavailable." |
| Bug in adapter (unexpected `RuntimeException`), `days` outside 1..7 reaching the adapter | 500 `INTERNAL_ERROR` (unchanged) |

Upstream requests (Open-Meteo, A-2). User/geocoded values are template variables, and every
other part is literal:
- `GET {geocoding}/v1/search?name={name}&count=1&language=en&format=json`
- `GET {forecast}/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,weather_code&timezone=UTC&temperature_unit=celsius&wind_speed_unit=ms`
- `GET {forecast}/v1/forecast?latitude={lat}&longitude={lon}&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code&start_date={start}&end_date={end}&timezone=UTC&temperature_unit=celsius&wind_speed_unit=ms`

`Accept: application/json`. No credentials are sent.

## Data model
No tables, no Flyway migration, no Redis keys (caching is out of scope).

### Configuration (final names, AC-4 / A-12)
| Property | Env var | Default (`application.yml`) | `application-it.yml` (literal) |
|----------|---------|-----------------------------|--------------------------------|
| `weather.provider.type` | `WEATHER_PROVIDER` | `open-meteo` | `stub` |
| `weather.provider.connect-timeout` | `WEATHER_PROVIDER_CONNECT_TIMEOUT` | `2s` | `1s` |
| `weather.provider.read-timeout` | `WEATHER_PROVIDER_READ_TIMEOUT` | `3s` | `1s` |
| `weather.provider.open-meteo.geocoding-base-url` | `WEATHER_PROVIDER_GEOCODING_BASE_URL` | `https://geocoding-api.open-meteo.com` | `http://127.0.0.1:9` |
| `weather.provider.open-meteo.forecast-base-url` | `WEATHER_PROVIDER_FORECAST_BASE_URL` | `https://api.open-meteo.com` | `http://127.0.0.1:9` |

The env var names are the ones proposed in the requirements. The base URLs are nested under
`open-meteo` (vendor-specific), which also keeps them out of reach of relaxed env binding, as
AC-5 needs. The timeout env vars match their properties by relaxed binding too, so a value
exported from `.env` can override the `it` literals. That is harmless: AC-5 only pins type and
URLs, and real-provider ITs set their timeouts via `@DynamicPropertySource`, which wins.

`application.yml` gets:
```yaml
weather:
  provider:
    type: ${WEATHER_PROVIDER:open-meteo}            # stub | open-meteo (ADR 0005)
    connect-timeout: ${WEATHER_PROVIDER_CONNECT_TIMEOUT:2s}
    read-timeout: ${WEATHER_PROVIDER_READ_TIMEOUT:3s}
    open-meteo:
      geocoding-base-url: ${WEATHER_PROVIDER_GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}
      forecast-base-url: ${WEATHER_PROVIDER_FORECAST_BASE_URL:https://api.open-meteo.com}
```
`.env.example`: the five variables, commented out with a one-line explanation each, plus a note
that they stay commented because `build.gradle.kts` exports this file into ITs (same as CORS).
`compose.yaml` `app.environment`: `WEATHER_PROVIDER: ${WEATHER_PROVIDER:-open-meteo}`, and the
other four the same way with their defaults (`:-`, so an empty `.env` entry never becomes an empty value).

## External integrations
**Open-Meteo** (free tier, no key; non-commercial, CC BY 4.0, about 10k calls/day, A-2):
- Timeouts: connect 2 s, read 3 s per call (env-configurable). Worst case is 2 calls, about 10 s.
- Retries: none (A-4). Redirects: not followed. Body cap: 1 MiB.
- Failure behaviour: see the API table. Geocoding failure → no forecast call. The geocoding
  result is not cached (A-11), so there are 2 calls per API request.
- TLS: JDK default trust store (the `eclipse-temurin` JRE image includes CA certificates).
- No health indicator. Readiness does not depend on the upstream (out of scope).

## Error handling
- All translation happens in `OpenMeteoWeatherProvider#call`. One try block per upstream call
  covers request, body read, JSON parse and mapping, so there is exactly one WARN per failed call.
- `WeatherProviderUnavailableException` is created only through `unavailable(call, outcome)`:
  message `"Open-Meteo " + call.logName() + " call failed: " + outcome`, **cause `null`**.
  `ApiExceptionHandler`'s existing WARN therefore logs `cause=none`, which is expected and unchanged.
- `outcome` is built only from: an HTTP status integer, exception **class names**
  (`e.getClass().getName()` and `NestedExceptionUtils.getMostSpecificCause(e).getClass().getName()`),
  or a fixed reason string. It never uses `getMessage()`.
- `LocationNotFoundException(city)` → existing 404 path. Not logged by the adapter.
- `days` out of range → `IllegalArgumentException` → 500 (the controller already validates 1..7,
  so this only signals a bug).

## Security considerations
- **Injection**: the city is only a URI-template variable, encoded strictly (AC-10). Base URLs
  come only from validated configuration (no SSRF from user input). Redirects are not followed.
- **Leakage**: no query strings, user input, upstream bodies, exception messages or credentials
  in logs, exception messages or responses (AC-21/22). Spring exceptions are never attached as
  cause (their messages contain the full URL). The property validation errors don't echo URL values.
- **Transport**: HTTPS default base URLs. `http` is allowed only so loopback fakes/dev overrides
  work. Operators must keep HTTPS in prod (documented in `.env.example`).
- **Resource limits**: per-call timeouts and a 1 MiB body cap.
- **Credentials**: none today. A future `apikey` must come from an env var with no default, be
  sent as a template variable, and be added to the AC-21 log/exception assertions (NFR).
- **Licence**: CC BY 4.0 attribution is a product/UI obligation. The API contract can't carry it
  (contract frozen), see Risks.

## Test strategy
Unit tests (`src/test`, `*Test`, no Docker) and integration tests (`src/integrationTest`, `*IT`,
profile `it`, compose Postgres/Redis running, `IntegrationTestBase`). No Testcontainers, no
internet: every real-adapter IT talks to `FakeOpenMeteo` on `127.0.0.1`. Each test uses
`@DisplayName("AC-n: ...")`.

### Test support (integration)
- `support.FakeOpenMeteo`: a JVM singleton wrapping `com.sun.net.httpserver.HttpServer` on
  `127.0.0.1:0`, with a cached-thread executor so a delayed handler doesn't block others. It
  has `baseUrl()` and `reset()` (restores the happy defaults and clears recorded requests).
  `stub(path, status, body, delay)` and `stubGeocoding(...)`/`stubCurrent(...)`/`stubDaily(...)`
  helpers set responses, and `requests(path)` returns `RecordedRequest(path, rawQuery,
  Map<String, List<String>> params)` (decoded with `URLDecoder` UTF-8). Defaults: geocoding
  `name` equal to `london` ignoring case → London fixture, otherwise `{}` (no results); `current`
  → the AC-6 fixture; `daily` → 7-day fixture generated from the received `start_date`/`end_date`
  (first 3 days = AC-8 values). The server is stopped by a JVM shutdown hook. Daemon threads.
- `support.RealProviderIntegrationTestBase extends ApiIntegrationTestBase`: a `@DynamicPropertySource`
  sets `weather.provider.type=open-meteo`, both base URLs = `FakeOpenMeteo.instance().baseUrl()`,
  `connect-timeout=1s` and `read-timeout=1s`. `@BeforeEach` calls `reset()`. The properties are
  identical across subclasses, so the Spring context is cached and shared.
- A fixed clock for AC-8: `@TestBean Clock clock` with `static Clock clock() { return
  Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC); }` (Spring 6.2 bean override).

### Per AC
| AC | Level | Test |
|----|-------|------|
| AC-1, AC-2 (bean), AC-3 | unit | `provider.WeatherProviderSelectionTest`: `ApplicationContextRunner` with `ConfigDataApplicationContextInitializer` (loads the real `application.yml`, no profile), `AutoConfigurations.of(JacksonAutoConfiguration, HttpMessageConvertersAutoConfiguration, RestClientAutoConfiguration)` and `WeatherProviderConfiguration`. An initializer **replaces** the `systemEnvironment` property source with a `SystemEnvironmentPropertySource("systemEnvironment", map)`: an empty map for AC-1 (host env can't leak), `WEATHER_PROVIDER=stub` (AC-2) and `=bogus` (AC-3). Asserts `getBeansOfType(WeatherProvider.class)` has size 1 and the instance type. For AC-3: `hasFailed()`, and the root cause message contains `weather.provider.type`, `stub` and `open-meteo`. This also covers the `WEATHER_PROVIDER` vs `weather.provider` relaxed-binding interplay (ADR 0005). Plus `WeatherProviderPropertiesTest` (pure): allowed and invalid types, empty type, URL validation, non-positive timeouts. |
| AC-2 (HTTP) | IT | `StubSelectionIT extends ApiIntegrationTestBase`: the `it` defaults plus `@DynamicPropertySource` pointing both URLs at the fake, with type left as `stub`. `current?city=London` → 11.2 / 81, and `FakeOpenMeteo.requests()` is empty. |
| AC-4 | unit | `ProviderConfigFilesTest`: reads `src/main/resources/application.yml` (SnakeYAML, already on the classpath via Boot) and asserts the five exact placeholder strings. Reads `.env.example` (each var present on a line starting with `# `) and `compose.yaml` (`app.environment` contains each var as `${VAR:-...}`). Files are resolved from the Gradle project dir (`user.dir`). |
| AC-5 | IT | `ItProfileProviderDefaultsIT extends ApiIntegrationTestBase`: `Environment` → `weather.provider.type == "stub"`, both URLs have host `127.0.0.1` and contain no `open-meteo`. The context's `WeatherProvider` is a `StubWeatherProvider`. Reads `application-it.yml` from the classpath and asserts the `weather.provider` block has no `${`. All existing ITs run unchanged. |
| AC-6, AC-7, AC-9, AC-10, AC-22 | IT | `RealProviderCurrentIT`: AC-6 values plus the `OpenApiSchemaAssert` check. AC-7 imperial plus both received queries have `temperature_unit=celsius` and `wind_speed_unit=ms`. AC-9 `london` / `%20%20London%20` → received `name`. AC-10: one `name` param = `São Paulo&count=50`, `count` = `[1]` (the fake returns London for it via an explicit stub). AC-22: `CapturedOutput`, lines at INFO/WARN/ERROR contain none of `name=`, `latitude=`, `longitude=`. |
| AC-8, AC-19, AC-20 | IT + unit | `RealProviderForecastIT` (fixed clock): AC-8 values, conditions and received `start_date`/`end_date`/`timezone`. AC-19 short array and shifted start → 503. AC-20 null precipitation → 0, null min → 503. `OpenMeteoMapperTest` covers the same rules plus length mismatch between arrays and an unparsable date. |
| AC-11 | unit | `OpenMeteoWeatherCodesTest`: `@ParameterizedTest @CsvSource` over all 28 codes → expected code and a non-blank description. `codes()` equals exactly that set. 4 and 100 → empty. |
| AC-12 | unit | `OpenMeteoMapperTest`: 80.6 → 81, 80.5 → 81, 101 → 100, -1 → 0; direction 360 → 0, 359.6 → 0, 229.5 → 230; precipitation 37.5 → 38; `"gb"` → `"GB"`. |
| AC-13, AC-14 | IT + unit | `RealProviderNotFoundIT`: both endpoints with `{}` and `{"results":[]}` → 404 `LOCATION_NOT_FOUND`, detail contains `Atlantis`, `requests("/v1/forecast")` empty. AC-14 with no and blank `country_code`. The mapper test also covers `"GBR"` → not found. |
| AC-15, AC-16, AC-17, AC-18, AC-21 | IT | `RealProviderFailureIT` (`OutputCaptureExtension`): AC-15 5 s delay on geocoding and on forecast → 503 in < 3 s (measured around the request). AC-17 `@ParameterizedTest` over 500/502/503/429/400 × geocoding/forecast. AC-18 invalid JSON, missing/null `temperature_2m`, `weather_code 4`. AC-21: header `X-Request-Id: it-rwp-21` (unique suffix per case is fine). Exactly one WARN line containing the id and `Upstream call failed` with `call=geocoding|forecast`, `status=` or `exception=`, and `elapsedMs=`. No `name=`/`latitude=`/`longitude=` in the captured output. The body contains neither `127.0.0.1` nor `/v1/` nor `Exception`. The bean is also called directly: `@Autowired WeatherProvider`, `assertThatThrownBy(() -> provider.current("London"))` → message free of `?`, `name=`, `latitude=`, and `hasNoCause()`. `UpstreamUnreachableIT` (AC-16): its own `@DynamicPropertySource` with type `open-meteo` and both URLs on a port obtained from `new ServerSocket(0)` and then closed, current and forecast → 503. |
| AC-23 | IT | `api.RealProviderContractConformanceIT extends ContractConformanceIT` and `api.RealProviderOpenApiDriftIT extends OpenApiDriftIT`, each with the same `@DynamicPropertySource` + `reset()` as `RealProviderIntegrationTestBase` (via a shared static helper `FakeOpenMeteo.register(registry)`, since they extend the existing classes). The fake's defaults satisfy London/Atlantis. The originals keep running on the stub. |

`StubWeatherProviderTest`, `WeatherServiceTest` and the existing ITs stay as they are.
`ProviderFailureIT`'s `@MockitoSpyBean WeatherProvider` keeps working with the factory bean (the
stub is selected in `it`).

## Implementation plan
Each step is one small commit that builds (`./gradlew build`). Integration steps also pass
`scripts/test-env.sh it`.
1. `feat: add Open-Meteo weather code mapping` with `OpenMeteoWeatherCodes` + `OpenMeteoWeatherCodesTest` (AC-11).
2. `feat: add Open-Meteo response mapper` with `InvalidUpstreamResponseException`, `OpenMeteoMapper` + `OpenMeteoMapperTest` (AC-12..14, AC-18..20 unit parts).
3. `feat: add WeatherProviderProperties` + `WeatherProviderPropertiesTest`, plus the `application.yml` block. `application-it.yml` gets its literal block in the same commit, so ITs never resolve to Open-Meteo hosts (AC-3 pure part, AC-4, AC-5).
4. `feat: add Open-Meteo adapter` with `UpstreamCall`, `OpenMeteoHttp`, `OpenMeteoWeatherProvider` (not wired yet).
5. `feat: select weather provider via weather.provider.type` with `WeatherProviderConfiguration`, removing `@Component` from the stub and updating the Javadoc of `WeatherProvider`/the stub, + `WeatherProviderSelectionTest` (AC-1..3). Run the existing ITs (stub via `it`).
6. `chore: document provider env vars` in `.env.example` and `compose.yaml` + `ProviderConfigFilesTest` (AC-4).
7. `test: add FakeOpenMeteo and real-provider IT base` + `StubSelectionIT`, `ItProfileProviderDefaultsIT` (AC-2, AC-5).
8. `test: real provider success paths` with `RealProviderCurrentIT`, `RealProviderForecastIT` (AC-6..10, 19, 20, 22).
9. `test: real provider not-found and failure paths` with `RealProviderNotFoundIT`, `RealProviderFailureIT`, `UpstreamUnreachableIT` (AC-13..18, 21).
10. `test: contract regression with the real provider` with the two subclasses (AC-23).

## Risks / open questions
- **A-2 licence/vendor**: the Open-Meteo free tier is non-commercial and CC BY 4.0 needs
  attribution. The contract can't carry it, so the UI or docs must. A human must confirm on the PR.
- **A-4 timeouts**: worst case about 10 s per API request with the defaults. Confirm, or lower
  (e.g. 1 s / 2 s).
- **A-8**: null precipitation → 0 understates uncertainty. Confirm.
- **Read-timeout semantics**: `JdkClientHttpRequestFactory`'s read timeout is the JDK request
  timeout (until response headers). Spring 6.2 also applies it to body reading. A slowly
  trickled body is additionally bounded by the 1 MiB cap. Acceptable.
- **Outbound access**: the compose `app` service and `bootRun` now need internet access, or
  `WEATHER_PROVIDER=stub`. Corporate proxies are not supported (no proxy configuration, out of scope).
- **`WEATHER_PROVIDER` vs relaxed binding**: the env var also appears as a scalar for the
  `weather.provider` prefix (ADR 0005). The binder falls back to field binding, and
  `WeatherProviderSelectionTest` verifies this with a real `SystemEnvironmentPropertySource`.
  If a future Boot version changes this, the test fails rather than prod.
- **Unit test using `ApplicationContextRunner`**: CLAUDE.md says unit tests use "no Spring
  infra". `WeatherProviderSelectionTest` uses a lightweight context runner (no web server, no
  Docker, no network). It has to live in `src/test`: there it sees the real `application.yml`
  without the `it` profile and without the `.env` export that `build.gradle.kts` applies to
  `integrationTest`. The reviewer should accept this exception or say where it should go.
- **Ambiguity, AC-14**: A-6 covers a missing or blank `country_code`. This design also treats a
  code that is not two letters (the contract pattern `^[A-Z]{2}$`) as not found, rather than 503.
- **Ambiguity, AC-19**: "fewer days than requested" → 503. This design also rejects *more* days
  than requested (exact length required) and mismatched array lengths.
- **Ambiguity, AC-21 "exactly one WARN line per failed upstream call"**: read as exactly one
  *adapter* line. `ApiExceptionHandler`'s existing WARN line is still written as well (it is
  required to stay).
- **Context count in ITs**: real-provider ITs share one cached context. `RealProviderForecastIT`
  (`@TestBean` clock), `StubSelectionIT`, `UpstreamUnreachableIT` and the two AC-23 subclasses add
  contexts. Each opens its own Postgres pool, which is fine against the compose Postgres defaults.
