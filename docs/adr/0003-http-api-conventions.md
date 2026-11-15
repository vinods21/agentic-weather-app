# 0003. HTTP API conventions: path versioning, Problem Details errors, request ids, CORS allow-list
Date: 2026-11-15 · Status: Accepted · Feature: ui-backend-api-contracts

## Context
The first public HTTP API (`/api/v1`) is being built for a UI on a different origin. The UI must
be able to handle every error in one place, correlate errors with server logs, and rely on a
stable contract. These conventions apply to every future endpoint, so they need to be recorded
once.

## Decision
1. **Versioning**: the major version is in the URL path (`/api/v1`). v1 is additive-only after
   release. Removing or renaming fields, codes or parameters requires `/api/v2`, and clients
   must ignore unknown fields. JSON uses camelCase, timestamps are ISO-8601 UTC with `Z`, and
   dates are `YYYY-MM-DD`.
2. **Errors**: every failure under `/api/` is an RFC 9457 Problem Details body
   (`application/problem+json`) built from Spring's `ProblemDetail`. It is extended with:
   - `code`: a stable machine-readable code from a closed enum (`ErrorCode`). New codes may be
     added, but existing ones are never changed.
   - `requestId`: the request's correlation id.
   - `errors[]` (`field`, `message`): only for `VALIDATION_FAILED`.

   `type` is `https://weather.example.com/problems/<kebab-code>` (an identifier that doesn't need
   to resolve). All mapping happens in one global `@RestControllerAdvice`, which only acts on
   `/api/` paths and otherwise rethrows so that Spring Boot's defaults apply (actuator, `/error`).
   Bodies never contain exception messages from unexpected errors, class names or stack traces.
   Spring's built-in `spring.mvc.problemdetails.enabled` stays off.
3. **Validation**: query parameters bind to `@Valid @ModelAttribute` records. Required-ness is
   expressed as Bean Validation constraints, so all violations are reported together. Messages
   are explicit, and binding-failure messages are replaced with fixed text.
4. **Request id**: the `X-Request-Id` header. A client value matching `^[A-Za-z0-9._-]{1,64}$` is
   echoed, and anything else is replaced by a UUID. A highest-precedence servlet filter on
   `/api/**` sets the response header, the MDC key `requestId` and a request attribute, and
   writes one INFO access-log line per request. The log pattern includes `%X{requestId}`.
5. **CORS**: an allow-list from `weather.api.cors.allowed-origins`
   (`WEATHER_API_CORS_ALLOWED_ORIGINS`, comma-separated), **empty = deny all**. It applies to
   `/api/**` with `GET` and `OPTIONS`, `X-Request-Id` exposed, and no credentials. It uses Spring
   MVC's `CorsRegistry`, not a library or a custom filter.
6. `spring.web.resources.add-mappings=false`: the service serves no static content, so unknown
   paths raise `NoHandlerFoundException` and give a clean 404 rather than a misleading 405.

## Consequences
- Clients get one error-handling path keyed on `code`, and operators get a request id in every
  response and log line.
- Adding a new failure type means adding an `ErrorCode` constant and updating the OpenAPI
  `Problem.code` enum (a unit test enforces this, see ADR 0002).
- Framework 4xx errors outside the code set (e.g. 406, 415) fall back to Spring defaults until
  codes are added for them.
- Static resources can't be added without revisiting decision 6.
- A future auth feature must keep CORS deny-by-default and revisit `allowCredentials`.

## Alternatives considered
- **Header or media-type versioning**: harder to use from browsers and generated clients, and
  harder to see in logs.
- **Custom error envelope**: rejected in favour of the RFC 9457 standard, which Spring already
  supports (no new dependency).
- **Spring Boot's built-in Problem Details (`spring.mvc.problemdetails.enabled`)**: it would apply
  to all paths and doesn't add `code`/`requestId`, so we'd still need an advice.
- **Micrometer Tracing for correlation ids**: a new dependency, and W3C trace ids don't satisfy
  "echo the client-supplied `X-Request-Id`". It can be added later alongside this filter.
- **Permissive CORS by default**: rejected for security.
