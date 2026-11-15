# 0010. Write-endpoint conventions and CORS for write methods (amends 0003)
Date: 2026-11-15 · Status: Accepted · Feature: saved-favourite-locations

Amends [0003](0003-http-api-conventions.md) decisions 2, 3 and 5. ADR 0003 stays Accepted, and
everything it says that is not changed here still applies.

## Context
Until now `/api/v1` was read-only: query parameters only, `GET`/`OPTIONS` in CORS (ADR 0003 §5).
`/api/v1/favourites` adds `POST`, `PATCH`, `PUT` and `DELETE` with JSON request bodies. Today an
unreadable body (`HttpMessageNotReadableException`) is not mapped and gives a `500`
(requirements A-10). The UI is on another origin, so the CORS allow-list must also allow the new
methods and the `Content-Type`/`X-Client-Id` request headers. Requirements A-11 asks for this
to be recorded in a new ADR that amends 0003.

## Decision
1. **CORS (replaces 0003 §5's method list)**: `/api/**` allows `GET, POST, PUT, PATCH, DELETE,
   OPTIONS`. Everything else in 0003 §5 is unchanged:
   - the origin allow-list from `WEATHER_API_CORS_ALLOWED_ORIGINS`, where **empty means deny all**;
   - `allowCredentials=false`;
   - `maxAge` 3600 s;
   - Spring MVC `CorsRegistry` only.

   Allowed request headers stay `*`, which echoes the requested headers (so `Content-Type` and
   `X-Client-Id` are included). That is safe without credentials. Exposed headers become
   `X-Request-Id` **and `Location`**, so a cross-origin UI can read the `Location` of a `201`.
   The methods are configured globally, not per path. A write method on a read-only path still
   gets `405` from routing, so the global list grants no new capability.
2. **Request bodies (extends 0003 §2/§3)**:
   - Bodies are `application/json` only (`consumes` on the mapping). Any other content type
     gives Spring's default `415` (0003 Consequences: framework 4xx outside the code set).
   - They bind to `@Valid @RequestBody` records/classes, with explicit Bean Validation messages.
     Violations go through the existing `BindException` handler, so `errors[]` is sorted by
     field, the same as for query parameters. `errors[].field` is the JSON property name (or
     the header name, e.g. `X-Client-Id`).
   - An unreadable body (missing, invalid JSON, wrong JSON type such as `[]` or an array for a
     string) gives `400 VALIDATION_FAILED` with exactly one error
     `{field "body", message "must be a JSON object matching the request schema"}`. The text is
     fixed: it never contains Jackson/Java class names or submitted content.
   - Unknown properties are ignored (Boot's default `FAIL_ON_UNKNOWN_PROPERTIES=false`), which
     mirrors "unknown query parameters are ignored".
   - Strings are stripped before validation, and length limits apply to the stripped value.
   - Controllers must not put constraint annotations directly on handler parameters, so Spring
     6.1's built-in method validation (`HandlerMethodValidationException`) stays inactive and
     all validation errors keep one shape.
3. **Write responses**:
   - Create is `201` with a relative `Location: /api/v1/<collection>/<id>` and the created
     representation.
   - Partial update is `PATCH` with merge semantics for the documented fields. A required-but-
     absent field is a validation error, and an explicit `null` clears an optional field.
   - Whole-collection reorder is `PUT` on a sub-resource with the complete id list.
   - Delete is `204` with an empty body.
   - Optional response properties that have no value are **omitted** (not `null`), so the
     hand-written schemas need no `nullable` (ADR 0002's checker).
4. **Conflicts**: a request that is valid but conflicts with the current state gives `409`,
   with a specific `ErrorCode` per conflict (here `FAVOURITE_ALREADY_EXISTS` and
   `FAVOURITES_LIMIT_REACHED`). The `detail` text is fixed and never echoes user input other
   than configuration values. A missing resource reuses `NOT_FOUND` with a resource-specific
   `detail` that differs from the routing 404's.

## Consequences
- The UI can call the write endpoints from an allowed origin, and every other origin is still
  denied.
- `CorsIT`'s "disallowed method" edge case must use a method that is still not allowed (e.g.
  `TRACE`), because `DELETE` is now allowed.
- Malformed bodies anywhere under `/api/` become `400` instead of `500`. That includes future
  endpoints, which is the intended convention.
- 415/406 still have no `code` (unchanged from 0003).
- A future auth feature must revisit `allowCredentials` and the `*` allowed-headers setting
  together (0003 Consequences).

## Alternatives considered
- **Per-path CORS mappings (writes only on `/api/v1/favourites/**`)**: tighter on paper, but
  routing already rejects write methods on read-only paths with `405`. Two mappings would
  need to stay in sync as paths are added.
- **An explicit allowed-headers list (`Content-Type`, `X-Client-Id`, `X-Request-Id`)**: narrower,
  but it would reject any other header an existing client sends today. That is a behaviour change
  not required here, and with no credentials the benefit is small.
- **A dedicated `MALFORMED_BODY` error code**: it adds to the closed code set for a case clients
  handle like any validation failure. The requirements fix `VALIDATION_FAILED` with field `body`.
- **`null` properties in responses instead of omission**: it would need `nullable` support in the
  conformance checker (ADR 0002) and differs from the requirement (AC-3).
