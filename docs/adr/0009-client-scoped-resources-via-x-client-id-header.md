# 0009. Client-scoped resources via an opaque X-Client-Id header (no authentication)
Date: 2026-11-15 · Status: Accepted · Feature: saved-favourite-locations

## Context
`/api/v1/favourites` is the first stateful API. v1 has no authentication, and adding it is out
of scope (requirements A-1). Separate users of one deployment must still not overwrite each
other's lists, ITs need isolation, and a later auth feature should be able to bind data to a
user without an `/api/v2`. Requirements A-1 proposes an opaque, client-generated id in a
required header, and marks this as needing a human decision on the PR.

This becomes the public convention for every future per-user resource until authentication
exists, so it is recorded once here.

## Decision
1. **Header**: `X-Client-Id`, required on every operation of a client-scoped resource
   (currently all of `/api/v1/favourites/**`). The value must match `^[A-Za-z0-9._-]{1,64}$`,
   the same character set and length as `X-Request-Id`. It is case-sensitive and compared exactly.
   The UI generates a random UUID once per browser/device and keeps it in local storage.
2. **Validation**: a missing, empty, too long or ill-formed value gives `400 VALIDATION_FAILED`
   with `errors` `[{field "X-Client-Id", message "must match ^[A-Za-z0-9._-]{1,64}$"}]`. Unlike
   `X-Request-Id`, an invalid value is **not** replaced, because that would silently create a new,
   empty list.
3. **Mechanism**: controllers take a `api.ClientId` value object as their **first** handler
   parameter. A `HandlerMethodArgumentResolver` registered in `ApiWebConfig` reads and validates
   the header and throws `InvalidClientIdException` on failure. Because arguments are resolved
   in declaration order, the header check runs before path-variable conversion and body reading.
   So the header error is always reported, and no data is read or written and no provider is
   called with an invalid id. A unit test asserts the first-parameter rule for every
   client-scoped controller method.
4. **Semantics**: the id is a **partition key, not a credential**. Anyone who knows or guesses
   an id can read and change that list. Every query filters by `client_id`. Access to another
   client's resource is indistinguishable from a missing one (`404 NOT_FOUND` with the same
   `detail`; in a reorder, the same `400` as an unknown id).
5. **Privacy**: client ids are never logged at INFO or above, never put into Problem bodies, and
   never used as Redis key material. `ClientId#toString()` is redacted, so a careless log
   statement can't leak one.
6. **Storage**: a `client_id varchar(64)` column on each client-scoped table, part of the
   relevant unique constraints and indexes. A future authentication feature can map an
   authenticated principal to this column (or migrate it) without changing the v1 resource
   shapes.

## Consequences
- Users of a shared deployment are separated by convention, and ITs isolate by using a unique
  id per test.
- It is not security. Clearing browser storage "loses" the list. A later auth feature must
  decide how existing client ids are claimed. The number of client ids is not bounded
  (requirements A-9).
- Every new client-scoped endpoint must take `ClientId` as its first parameter. The
  signature unit test and the drift test catch an omission in review.
- CORS must allow the `X-Client-Id` request header (ADR 0010).

## Alternatives considered
- **A single global list (no header)**: simpler, but every caller shares and can edit one list,
  and adding owners later is a breaking change (a new required header) or needs a default-owner
  hack. This is the alternative a human may pick on the PR (A-1).
- **Client id in the path (`/api/v1/clients/{clientId}/favourites`)**: puts the id into access
  logs (paths are logged at INFO, ADR 0003) and browser history, and makes a later move to
  authenticated `/me`-style resources a path change.
- **Cookie**: needs CORS credentials (ADR 0003 keeps `allowCredentials=false`) and CSRF
  protection.
- **Bean Validation on a `@RequestHeader` parameter (Spring 6.1 method validation)**: header
  and body violations would only be combined when the body is readable. A malformed body
  would hide the header error, and errors arrive as a different exception type
  (`HandlerMethodValidationException`).
- **A `HandlerInterceptor`**: it would also run before argument resolution, but it would still
  need a resolver or a request attribute to hand the id to the controller. The resolver alone
  does both.
