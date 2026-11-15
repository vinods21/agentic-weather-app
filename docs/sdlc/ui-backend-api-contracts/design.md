# UI-backend API contracts: Design
Requirements: ./requirements.md · ADRs: 0001, 0002, 0003, 0004

## Summary
We add a versioned, read-only REST API under `/api/v1` with two weather endpoints (current
conditions, daily forecast) and a hand-written OpenAPI 3.0.3 document served at
`/api/v1/openapi.json`. The pieces are:

- **Web layer** (`com.example.weather.api`): controllers, request "query" records validated with
  Bean Validation, response DTOs (Java records), one `@RestControllerAdvice` that turns every
  failure under `/api/` into an RFC 9457 `ProblemDetail` with `code`, `requestId` and (for
  validation) `errors`. It also holds a request-id/access-log filter and the CORS configuration.
- **Domain/service** (`com.example.weather.weather`): `WeatherService` calls the provider port
  and converts units. `UnitConverter` is a pure helper.
- **Provider port** (`com.example.weather.provider`): `WeatherProvider` interface, its two
  exceptions, and a deterministic in-process `StubWeatherProvider` that is the default bean (A-1).

There are **no new dependencies** (ADR 0002). Everything uses `spring-boot-starter-web`,
`-validation` and Jackson, which are already on the classpath. The OpenAPI document is
spec-first. Integration tests keep it honest: a drift test (AC-21) compares mapped endpoints
with spec operations, and a conformance test (AC-22) checks real bodies against spec schemas
using Jackson trees. This feature adds no tables, migrations or Redis keys.

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `WeatherController#current`, `WeatherService`, `StubWeatherProvider`, `CurrentWeatherResponse` | The stub has a fixed London record (GB, 51.5074/-0.1278). `observedAt` = `Instant.now(clock)` truncated to seconds, serialized by Jackson as ISO-8601 with `Z` (Boot default `WRITE_DATES_AS_TIMESTAMPS=false`). `produces = application/json`. |
| AC-2 | `WeatherService`, `UnitConverter` | The provider always returns metric. For `imperial` the service converts: F = C x 9/5 + 32, mph = m/s x 2.2369363, rounded half-up to 1 decimal. The max error vs the exact value is 0.05, which is within 0.1. |
| AC-3 | `StubWeatherProvider` | Values come from static per-city tables with no randomness. Only `observedAt` depends on the clock. |
| AC-4 | `CurrentWeatherQuery` compact constructor | `city` is `strip()`ped before validation and lookup. The response uses the stub's canonical name. |
| AC-5 | `WeatherService#forecast`, `StubWeatherProvider` | Day i (0-based) has `date = LocalDate.now(clock /*UTC*/).plusDays(i)` for i in `0..days-1`. Per-city 7-entry tables guarantee min <= max and precipitation 0..100. |
| AC-6 | `ForecastQuery` | `days == null` becomes 5 and `units == null` becomes `metric` in the compact constructor. |
| AC-7 | `CurrentWeatherQuery`/`ForecastQuery` constraints, `ApiExceptionHandler#handleBind` | `@Valid @ModelAttribute` record binding: a `BindException`/`MethodArgumentNotValidException` is mapped to `VALIDATION_FAILED`, with one `errors[]` entry per `FieldError` (`field` = parameter name). `days=abc` is a binding failure (`typeMismatch`) on field `days`, with a fixed message. |
| AC-8 | same | All parameters are bound as optional (`required` is enforced by `@NotBlank`, not `@RequestParam(required=true)`), so Bean Validation collects every violation in one `BindingResult`. |
| AC-9 | `@Size(max = 100)` on the stripped city; `StubWeatherProvider` | 100 characters passes validation. The stub doesn't know the city and throws `LocationNotFoundException`, which becomes 404. |
| AC-10 | `LocationNotFoundException`, `ApiExceptionHandler` | 404 `LOCATION_NOT_FOUND`, `detail = "No weather data is available for city 'Atlantis'."` |
| AC-11 | `spring.web.resources.add-mappings=false`, `ApiExceptionHandler#handleNotFound` | An unknown path raises `NoHandlerFoundException` (Boot 3.2+ default). It is also handled defensively with `NoResourceFoundException`. Under `/api/` the result is 404 `NOT_FOUND`, `instance` = request path. |
| AC-12 | `ApiExceptionHandler#handleMethodNotAllowed` | `HttpRequestMethodNotSupportedException` becomes 405 `METHOD_NOT_ALLOWED`, with an `Allow` header from `ex.getSupportedHttpMethods()` (contains `GET`). |
| AC-13 | `WeatherProviderUnavailableException`, `ApiExceptionHandler` | 503 `UPSTREAM_UNAVAILABLE`. `log.warn("Weather provider unavailable requestId={} path={}", ...)`. |
| AC-14 | `ApiExceptionHandler#handleUnexpected` | 500 `INTERNAL_ERROR` with a fixed `detail`. The exception message and class are never put in the body. `log.error("Unhandled error requestId={} path={}", id, path, ex)` writes the stack trace. |
| AC-15 | `RequestIdFilter`, `RequestIds` | A valid incoming id is echoed in the header (set before the chain runs, so it is present on every status). It is stored as a request attribute and in MDC, and the advice copies it into `requestId`. |
| AC-16 | `RequestIds#resolve` | A missing id, or one that fails `^[A-Za-z0-9._-]{1,64}$`, is replaced by `UUID.randomUUID()`. |
| AC-17 | `ApiWebConfig#addCorsMappings`, `ApiCorsProperties` | `/api/**`: `allowedOrigins` from config, methods `GET, OPTIONS`, `exposedHeaders("X-Request-Id")`, `allowedHeaders("*")`, `maxAge(3600)`, no credentials. |
| AC-18 | same | Spring's `DefaultCorsProcessor` rejects an origin that isn't listed with 403 and no ACAO header. When the list is empty (the default), every origin is rejected. |
| AC-19 | `OpenApiController`, `src/main/resources/openapi/openapi-v1.json` | The bytes are loaded once at startup (startup fails if the file is missing) and served with `application/json`. `openapi: "3.0.3"`, `info.version: "1.0.0"`. |
| AC-20 | `openapi-v1.json` | Parameters have `required`, `enum`/`minimum`/`maximum`, and `default`. 200 schemas list every field in `required`. 400/404/500/503 use `application/problem+json` and `$ref: '#/components/schemas/Problem'`, whose `code` enum is the six codes. |
| AC-21 | `OpenApiDriftIT` | Compares `RequestMappingHandlerMapping#getHandlerMethods()` filtered to `/api/v1/**` (minus `openapi.json`) with the spec's `paths` x operation keys. |
| AC-22 | `ContractConformanceIT` + `OpenApiSchemaAssert` (test helper) | Resolves `$ref`. Checks that required properties are present and non-null, that top-level keys are a subset of the schema `properties`, and recurses into nested objects and array items (type and `enum` too). |
| AC-23 | `RequestIdFilter#shouldNotFilter`, `ApiExceptionHandler` path guard | Filter and advice act only on `/api/`. `/actuator/health` is served by the actuator handler mapping and stays untouched. |

## Components & packages
All new classes go under `com.example.weather`.

### `api` (cross-cutting web concerns for `/api/**`)
| Class | Responsibility |
|-------|----------------|
| `api.ApiPaths` | Constants: `API_PREFIX = "/api/"`, `V1 = "/api/v1"`, plus `static boolean isApi(HttpServletRequest)` (checks `getRequestURI()` minus context path starts with `/api/`). |
| `api.RequestIds` | Pure helper: `HEADER = "X-Request-Id"`, `ATTRIBUTE = RequestIds.class.getName()`, `MDC_KEY = "requestId"`, `Pattern VALID = ^[A-Za-z0-9._-]{1,64}$`, `String resolve(String incoming)` (echo if valid, else UUID), `String current(HttpServletRequest)` (attribute, or a fresh UUID as fallback). Unit-tested. |
| `api.RequestIdFilter` | `OncePerRequestFilter`, registered at `Ordered.HIGHEST_PRECEDENCE` (so it runs before Spring's CORS handling and the 403 rejection still carries the header). `shouldNotFilter` returns true when the path is not under `/api/`. It resolves the id, sets the response header **before** `chain.doFilter`, sets the request attribute and `MDC.put("requestId", id)`, and times the request. In `finally` it logs once at INFO: `"{} {} -> {} in {} ms requestId={}"` (method, path without query string, `response.getStatus()`, duration, id), then `MDC.remove`. |
| `api.ApiCorsProperties` | `@ConfigurationProperties("weather.api.cors") record ApiCorsProperties(List<String> allowedOrigins)`. The compact constructor trims entries, drops blanks, and turns null into an empty list (picked up by the existing `@ConfigurationPropertiesScan`). |
| `api.ApiWebConfig` | `WebMvcConfigurer` that registers the CORS mapping for `/api/**` (see AC-17). If the list is empty it still registers the mapping with `allowedOrigins()` empty, which is an explicit deny. |

### `api.error`
| Class | Responsibility |
|-------|----------------|
| `api.error.ErrorCode` | Enum of the closed set: `VALIDATION_FAILED(400, "validation-failed", "Validation failed")`, `LOCATION_NOT_FOUND(404, "location-not-found", "Location not found")`, `NOT_FOUND(404, "not-found", "Not found")`, `METHOD_NOT_ALLOWED(405, "method-not-allowed", "Method not allowed")`, `UPSTREAM_UNAVAILABLE(503, "upstream-unavailable", "Upstream unavailable")`, `INTERNAL_ERROR(500, "internal-error", "Internal error")`. `type()` = `https://weather.example.com/problems/<slug>`. |
| `api.error.FieldErrorDto` | `record FieldErrorDto(String field, String message)`. |
| `api.error.ProblemFactory` | `ResponseEntity<ProblemDetail> build(ErrorCode, String detail, HttpServletRequest, List<FieldErrorDto> errors)`. Sets `type`, `title`, `status`, `detail`, `instance` = `URI` of the request path (no query string), and the properties `code` (enum name) and `requestId`, plus `errors` only when non-null. Content type is `application/problem+json`. |
| `api.error.ApiExceptionHandler` | Global `@RestControllerAdvice` (it must be global: `NoHandlerFoundException` isn't tied to a controller). Every handler first checks `ApiPaths.isApi(request)` and **rethrows the same exception** if the path is not under `/api/`. `ExceptionHandlerExceptionResolver` then passes the original exception on to Spring's default resolvers, which keeps A-10/AC-23 behaviour. Handlers: `BindException` (covers `MethodArgumentNotValidException`) → 400; `LocationNotFoundException` → 404; `NoHandlerFoundException`, `NoResourceFoundException` → 404 `NOT_FOUND`; `HttpRequestMethodNotSupportedException` → 405 + `Allow`; `WeatherProviderUnavailableException` → 503 + WARN; `Exception` → 500 + ERROR, except that a Spring `ErrorResponse` with a 4xx status (e.g. 406, 415, out of scope) is rethrown so Spring's defaults apply instead of a misleading 500. |

Validation message rules (in `handleBind`):
- A `FieldError` with `isBindingFailure()` (type mismatch) gets the fixed message `"must be an integer between 1 and 7"` for `days`, and `"has an invalid value"` otherwise. The Spring default message contains Java class names and is never used. The rejected value is never echoed.
- Otherwise it uses the constraint's message (defined explicitly on the annotations, below).
- `errors` is sorted by `field`, then `message`, so the output is deterministic.
- `detail` = `"One or more request parameters are invalid."`

Fixed `detail` texts: `NOT_FOUND` "No endpoint exists at this path.", `METHOD_NOT_ALLOWED`
"HTTP method <M> is not supported for this path." (`<M>` is from the `HttpMethod` enum, never
raw input), `UPSTREAM_UNAVAILABLE` "The weather provider is temporarily unavailable.",
`INTERNAL_ERROR` "An unexpected error occurred.", `LOCATION_NOT_FOUND` "No weather data is
available for city '<city>'." (the stripped city that was validated as 1..100 characters;
Jackson escapes it).

### `api.v1`
| Class | Responsibility |
|-------|----------------|
| `api.v1.WeatherController` | `@RestController @RequestMapping(value = "/api/v1/weather", produces = APPLICATION_JSON_VALUE)`. `@GetMapping("/current") CurrentWeatherResponse current(@Valid @ModelAttribute CurrentWeatherQuery q)` and `@GetMapping("/forecast") ForecastResponse forecast(@Valid @ModelAttribute ForecastQuery q)`. Contains no logic beyond delegating to `WeatherService` and mapping to DTOs. |
| `api.v1.OpenApiController` | `@GetMapping(value = "/api/v1/openapi.json", produces = APPLICATION_JSON_VALUE) ResponseEntity<byte[]>`. Loads `classpath:openapi/openapi-v1.json` in its constructor and fails fast if it is missing. |
| `api.v1.CurrentWeatherQuery` | `record (@NotBlank(message="must not be blank") @Size(max=100, message="must be at most 100 characters") String city, @Pattern(regexp="metric\|imperial", message="must be one of: metric, imperial") String units)`. The compact constructor strips `city` and defaults `units` from null to `"metric"`. `Units resolvedUnits()`. |
| `api.v1.ForecastQuery` | Same `city`/`units`, plus `@Min(value=1, message="must be between 1 and 7") @Max(value=7, message="must be between 1 and 7") Integer days`. The compact constructor defaults null to 5. |
| `api.v1.dto.LocationDto` | `(String name, String country, double latitude, double longitude)` |
| `api.v1.dto.ConditionDto` | `(ConditionCode code, String description)` |
| `api.v1.dto.CurrentWeatherResponse` | `(LocationDto location, Instant observedAt, Units units, double temperature, double feelsLike, int humidity, double windSpeed, int windDirection, ConditionDto condition)`. Component order follows the contract. |
| `api.v1.dto.ForecastDayDto` | `(LocalDate date, double minTemperature, double maxTemperature, int precipitationProbability, ConditionDto condition)` |
| `api.v1.dto.ForecastResponse` | `(LocationDto location, Units units, List<ForecastDayDto> days)` |

`units` is bound as a `String` and checked with `@Pattern`, not as an enum. Spring's
String-to-Enum converter would match `METRIC` against constant names and would report a
`typeMismatch` instead of a constraint violation. Primitive DTO fields guarantee non-null
numbers.

### `weather` (domain + service)
| Class | Responsibility |
|-------|----------------|
| `weather.Units` | `METRIC("metric")`, `IMPERIAL("imperial")`. `@JsonValue String value()`, `static Units fromValue(String)`. |
| `weather.ConditionCode` | `CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDERSTORM` (serialized by name). |
| `weather.Location`, `weather.Condition`, `weather.CurrentConditions`, `weather.DailyForecast` | Immutable domain records, always metric (C, m/s), as returned by the provider. |
| `weather.CurrentWeather`, `weather.Forecast` | Service results: location + `Units` + values already converted (+ `observedAt` / list of dated days). The controller maps them 1:1 to DTOs. |
| `weather.UnitConverter` | Pure static helpers: `celsiusToFahrenheit`, `msToMph`, `round1` (HALF_UP via `BigDecimal`). Unit-tested. |
| `weather.WeatherService` | `@Service`, independent of the web DTOs. It returns domain results already converted for the requested `Units` (`CurrentWeather current(String city, Units)`, `Forecast forecast(String city, Units, int days)`). It owns `observedAt` and the forecast start date from an injected `java.time.Clock`. |
| `weather.ClockConfig` | `@Bean Clock clock()` returning `Clock.systemUTC()`, so tests can swap it if needed. |

### `provider` (port + stub adapter)
| Class | Responsibility |
|-------|----------------|
| `provider.WeatherProvider` | `CurrentConditions current(String city)`, `List<DailyForecast> forecast(String city, LocalDate startDate, int days)`. Contract: throws `LocationNotFoundException` for an unknown city and `WeatherProviderUnavailableException` when the source fails or times out. Values are always metric. |
| `provider.LocationNotFoundException` | `RuntimeException` carrying `city()`. |
| `provider.WeatherProviderUnavailableException` | `RuntimeException(String message, Throwable cause)`. Its message is for logs only and never goes into a body. |
| `provider.stub.StubWeatherProvider` | `@Component`, the default and only bean (A-1, ADR 0004). City lookup is case-insensitive (`toLowerCase(Locale.ROOT)`) against a fixed map and returns the canonical name: London GB (51.5074, -0.1278), Paris FR (48.8566, 2.3522), New York US (40.7128, -74.0060), Tokyo JP (35.6762, 139.6503), Sydney AU (-33.8688, 151.2093). Each city has one fixed current record and a 7-entry day table (`min <= max`, precipitation 0..100, valid codes). `forecast` returns the first `days` entries dated `startDate + i`. Any other city, including `Atlantis`, throws `LocationNotFoundException`. |

## API
All endpoints are `GET`, unauthenticated, under `/api/v1`. Every response under `/api/` carries
`X-Request-Id`. Unknown query parameters are ignored (A-9).

### `GET /api/v1/weather/current`
| Param | Required | Rule | Default |
|-------|----------|------|---------|
| `city` | yes | stripped, 1..100 chars | none |
| `units` | no | `metric` \| `imperial` (case-sensitive) | `metric` |

200 `application/json`: the exact body from the requirements' contract summary.
Errors: 400 `VALIDATION_FAILED`, 404 `LOCATION_NOT_FOUND`, 405 `METHOD_NOT_ALLOWED` (non-GET),
500 `INTERNAL_ERROR`, 503 `UPSTREAM_UNAVAILABLE`.

### `GET /api/v1/weather/forecast`
Same `city`/`units` as above, plus `days` (optional integer 1..7, default 5).
200 `application/json`: the contract-summary body. `days.length == days`, starting at today's UTC
date, ascending, no gaps. Errors are the same as for current.

### `GET /api/v1/openapi.json`
200 `application/json`: the OpenAPI 3.0.3 document. It isn't listed as an operation inside
itself and is excluded from the drift check (AC-21).

### Error body (all failures under `/api/`)
`Content-Type: application/problem+json`:
```json
{
  "type": "https://weather.example.com/problems/location-not-found",
  "title": "Location not found",
  "status": 404,
  "detail": "No weather data is available for city 'Atlantis'.",
  "instance": "/api/v1/weather/current",
  "code": "LOCATION_NOT_FOUND",
  "requestId": "ui-abc-123"
}
```
`errors: [{ "field", "message" }]` is present only for `VALIDATION_FAILED`. Spring's
`ProblemDetailJacksonMixin` flattens `ProblemDetail.properties` to top level, so no custom
serializer is needed. CORS rejections (403) use Spring's default plain response, as AC-18
allows.

### OpenAPI document (`src/main/resources/openapi/openapi-v1.json`)
- `openapi: "3.0.3"`. This version has the widest client/mock-generator support, and nothing in
  the contract needs 3.1.
- `info`: `title "agentic-weather-app API"`, `version "1.0.0"`. `description` states the
  compatibility policy: v1 is additive-only, clients must ignore unknown fields, and breaking
  changes go to `/api/v2`. It also documents `X-Request-Id` and CORS.
- `paths`: `/api/v1/weather/current` and `/api/v1/weather/forecast`, each with only `get`, an
  `operationId` (`getCurrentWeather`, `getForecast`), parameters (`city`: `required: true`,
  `minLength 1`, `maxLength 100`; `units`: `enum [metric, imperial]`, `default metric`; `days`:
  `integer`, `minimum 1`, `maximum 7`, `default 5`), and an optional `X-Request-Id` header
  parameter (`pattern`, `maxLength 64`) via `components.parameters.RequestId`.
- Responses: `200` → `CurrentWeather` / `Forecast`. `400`, `404`, `500` and `503` →
  `application/problem+json` with `$ref: '#/components/responses/<…>'`, all pointing to the single
  `components.schemas.Problem`. Every response declares the `X-Request-Id` response header.
  (405 is documented in the `info.description` rather than per operation, because every path
  supports only GET. AC-20 doesn't require it.)
- `components.schemas`: `Location`, `Condition` (`code` enum of the eight values),
  `CurrentWeather`, `ForecastDay`, `Forecast`, `Problem`, `FieldError`. Every contract field is
  listed in `required`. Ranges: `humidity`/`precipitationProbability` 0..100, `windDirection`
  0..359, `observedAt` `format: date-time`, `date` `format: date`, `country` `pattern ^[A-Z]{2}$`.
  `days.minItems 1`, `maxItems 7`.
- `Problem`: properties `type` (uri), `title`, `status` (integer), `detail`, `instance`,
  `code` (enum of exactly the six `ErrorCode` names), `requestId`, `errors` (array of
  `FieldError`). Required: all of these except `errors`.
- **No `additionalProperties: false`.** That would make generated clients reject additive fields,
  which contradicts the compatibility policy. The "no undocumented properties" rule is enforced
  by the AC-22 test on the server side instead.

## Data model
None. No tables, no Flyway migration (the next free number stays `V2`), and no Redis keys or
`CacheKeys` usage. Caching is a follow-up feature.

Configuration added to `src/main/resources/application.yml`:
```yaml
spring:
  web:
    resources:
      add-mappings: false          # no static content; unknown paths -> NoHandlerFoundException
logging:
  pattern:
    level: "%5p [requestId=%X{requestId:-}]"   # every log line carries the MDC request id
weather:
  api:
    cors:
      allowed-origins: ${WEATHER_API_CORS_ALLOWED_ORIGINS:}   # comma-separated; empty = deny all
```
- `.env.example`: add a **commented** example `# WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173`.
  It is commented because `build.gradle.kts` (`composeEnv()`) exports `.env`/`.env.example` into
  the integration-test JVM. An active value would silently change the deny-by-default
  behaviour under test and in every fresh environment. A developer who wants the UI dev server
  uncomments it in `.env`.
- `compose.yaml` `app.environment`: add `WEATHER_API_CORS_ALLOWED_ORIGINS: ${WEATHER_API_CORS_ALLOWED_ORIGINS:-}`
  so the containerised app can be configured too.

## External integrations
None at runtime. `StubWeatherProvider` is in-process with no I/O, so it needs no timeouts or
retries. For future adapters, the `WeatherProvider` contract (ADR 0004) says that any I/O
failure, timeout or 5xx from the upstream must be wrapped in
`WeatherProviderUnavailableException` (giving 503 `UPSTREAM_UNAVAILABLE`), and an "unknown
location" answer must become `LocationNotFoundException` (giving 404). Timeouts, retries and
caching are decided in that feature's design.

## Error handling
- There is one place for it: `ApiExceptionHandler`, which builds every body through
  `ProblemFactory`. No controller catches exceptions.
- Path guard: only `/api/**` gets Problem responses. Other paths, including `/actuator/**` and
  `/error`, keep Spring Boot defaults. The guard rethrows the original exception so that
  Spring's default resolvers handle it.
- Logging levels: 4xx is not logged beyond the access log line. 503 is logged at WARN without
  a stack trace (just cause class + message, for operators only). 500 is logged at ERROR with
  the stack trace. Every line has `requestId` via MDC, and the WARN/ERROR messages also contain
  `requestId=<id>` explicitly so AC-13/AC-14 don't depend on the log pattern.
- Raw user input (city, query string, invalid request id) is never logged: it could contain
  CR/LF after decoding. The access log uses the request path only.
- Spring defaults that stay off: `spring.mvc.problemdetails.enabled` (false, because our advice
  does this job), and `server.error.include-*` (never).

## Security considerations
- **Information leakage**: error bodies use fixed `detail` texts. Binding-failure messages are
  replaced (Spring's default `typeMismatch` text contains class names). 500 bodies never include
  the exception message, class or stack trace (AC-14). The only echoed input is the validated
  city in `LOCATION_NOT_FOUND`, which is JSON-escaped and served as `application/problem+json`,
  so it isn't rendered as HTML.
- **Request id**: a client value is accepted only if it matches `^[A-Za-z0-9._-]{1,64}$`. This
  prevents header and log injection. Anything else is replaced, not sanitized.
- **CORS**: deny-by-default allow-list, `GET`/`OPTIONS` only, no credentials, and only
  `X-Request-Id` exposed. We don't add special handling for `*`. Operators shouldn't configure
  it, and the design doesn't enable `allowCredentials`, so `*` would at least not leak
  credentials (noted for the security review).
- **Surface**: the API is read-only and unauthenticated (A-4). There are no new dependencies,
  and disabling static resource mappings slightly reduces the attack surface.

## Test strategy
Unit tests (`src/test/java`, no Spring context, no Docker):
| Test | Covers |
|------|--------|
| `UnitConverterTest` | C to F, m/s to mph, rounding (supports AC-2) |
| `RequestIdsTest` | valid ids echoed, `bad id!`/65 characters/empty/null replaced by a UUID (AC-15/16 logic) |
| `StubWeatherProviderTest` | determinism, case-insensitive lookup, `Atlantis` throws, `forecast` length/dates/min<=max/ranges for 1..7 (AC-3, AC-5 logic, AC-10) |
| `WeatherServiceTest` | imperial conversion vs metric, default start date from a fixed `Clock` (AC-2, AC-5) |
| `ApiCorsPropertiesTest` | trimming, blanks dropped, null becomes empty |
| `OpenApiDocumentTest` | the classpath `openapi/openapi-v1.json` parses, and `Problem.code` enum equals `ErrorCode.values()` names (fast drift guard for AC-20) |

Integration tests (`src/integrationTest/java`, `*IT.java`, extend `IntegrationTestBase`,
profile `it`, against the Docker Compose services started with `scripts/test-env.sh up`; **no
Testcontainers**, ADR 0001). Each test has `@DisplayName("AC-n: ...")`. The tests create no rows
or Redis keys, so no `@AfterEach` cleanup is needed beyond the base class.
| IT class | ACs | Notes |
|----------|-----|-------|
| `CurrentWeatherIT` | 1, 2, 3, 4, 9, 10 (current) | |
| `ForecastIT` | 5, 6, 10 (forecast) | For AC-5, compute today's UTC date before and after the call and accept either (midnight race) |
| `ValidationIT` | 7 (parameterized over the 8 cases), 8 | |
| `RoutingErrorsIT` | 11, 12 | |
| `ProviderFailureIT` | 13, 14 | `@MockitoBean WeatherProvider` (Spring Framework 6.2, already in `spring-boot-starter-test`); `@ExtendWith(OutputCaptureExtension.class)` to assert the WARN/ERROR lines contain the request id (send a fixed `X-Request-Id`), and the stack trace for 500 |
| `RequestIdIT` | 15, 16 | One success and one error per case. For 16, assert the header parses as `UUID` and equals the body `requestId` |
| `CorsIT` | 17, 18 (evil origin) | `@TestPropertySource(properties = "weather.api.cors.allowed-origins=http://localhost:5173")` |
| `CorsDefaultDenyIT` | 18 (unset) | `@TestPropertySource(properties = "weather.api.cors.allowed-origins=")`. This forces "unset" even if the env var is exported from `.env` |
| `OpenApiIT` | 19, 20 | Jackson `JsonNode` assertions on the served document |
| `OpenApiDriftIT` | 21 | Inject `@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping`. A mapping under `/api/v1` with an empty methods condition fails the test (all methods must be explicit) |
| `ContractConformanceIT` | 22 | Uses the test helper `OpenApiSchemaAssert` (`src/integrationTest/java/com/example/weather/support/`), Jackson only, no JSON-schema library |
| `InfrastructureIT` (extend) or `HealthRegressionIT` | 23 | Assert 200, `status = UP`, `Content-Type` is not `application/problem+json`, and no `X-Request-Id` header |

HTTP client notes: use `TestRestTemplate`, whose Boot 3.5 default factory is the JDK
`HttpClient`, which allows the `Origin` header. Pass **`java.net.URI`** objects (e.g.
`URI.create("/api/v1/weather/current?city=%20%20London%20")`) rather than String templates. A
String template would re-encode `%` and break AC-4 and AC-7. Use `exchange` with
`HttpMethod.OPTIONS`/`POST` for AC-12, 17 and 18.

## Implementation plan
Each step is one commit that builds (`./gradlew build`). Run `scripts/test-env.sh it` after
steps 3 to 8.
1. `feat:` domain types (`Units`, `ConditionCode`, records), `UnitConverter`, provider port and
   exceptions, `StubWeatherProvider`, plus unit tests (`UnitConverterTest`, `StubWeatherProviderTest`).
2. `feat:` `ClockConfig`, `WeatherService`, plus `WeatherServiceTest`.
3. `feat:` DTOs, query records, `WeatherController` (happy path), plus `CurrentWeatherIT` (AC-1..4)
   and `ForecastIT` (AC-5, 6).
4. `feat:` `ErrorCode`, `FieldErrorDto`, `ProblemFactory`, `ApiExceptionHandler`, `ApiPaths`, and
   `spring.web.resources.add-mappings=false`, plus `ValidationIT` (AC-7, 8), AC-9/AC-10 tests,
   `RoutingErrorsIT` (AC-11, 12).
5. `feat:` `RequestIds`, `RequestIdFilter` (access log + MDC), `logging.pattern.level`, plus
   `RequestIdsTest`, `RequestIdIT` (AC-15, 16), `ProviderFailureIT` (AC-13, 14), AC-23 regression.
6. `feat:` `ApiCorsProperties`, `ApiWebConfig`, the `application.yml` property, a commented
   `.env.example` line, the `compose.yaml` app env passthrough, plus `ApiCorsPropertiesTest`,
   `CorsIT`, `CorsDefaultDenyIT` (AC-17, 18).
7. `feat:` `openapi/openapi-v1.json`, `OpenApiController`, plus `OpenApiDocumentTest`, `OpenApiIT`
   (AC-19, 20).
8. `test:` `OpenApiSchemaAssert`, `OpenApiDriftIT` (AC-21), `ContractConformanceIT` (AC-22).

## Risks / open questions
- **A-1 (needs a human decision)**: the stub is the unconditional runtime default (ADR 0004). This
  means prod would serve fake data for five cities until a real adapter lands. The alternative
  (stub only in a profile) leaves prod with no provider bean and a failing startup. The
  follow-up adapter feature replaces the bean (e.g. via `@ConditionalOnProperty`).
- **`days=abc` combined with other errors**: when constructor binding fails a type conversion,
  Spring 6.1+ validates the remaining constructor arguments through `SmartValidator#validateValue`.
  If that doesn't report the other fields in some edge case, only the `days` error would
  appear. The ACs don't combine `abc` with other violations, so AC-7/AC-8 are unaffected.
- **Empty values**: `units=` (empty) gives 400 (it isn't a valid value), but `days=` (empty)
  converts to null and becomes the default 5. This is Spring's standard number conversion, is
  not covered by any AC, and is documented in the spec description.
- **Case-insensitive city matching** (`london` finds London) is a design choice. The
  requirements don't specify it.
- **`HEAD`** is implicitly supported by Spring for `GET` mappings, and `OPTIONS` without CORS
  headers returns 200 with `Allow`. Neither is a documented operation, and the drift test only
  compares explicit mapping methods.
- **Framework 4xx outside the code set** (406, 415) under `/api/` fall back to Spring defaults,
  not Problem format, because content negotiation is out of scope. Adding codes later is
  additive.
- **Midnight UTC race** in AC-5 tests is handled by the tolerant assertion described above.
- **UI dev-server origin** (A-8) is still unconfirmed. `http://localhost:5173` is only an example
  in `.env.example`.
