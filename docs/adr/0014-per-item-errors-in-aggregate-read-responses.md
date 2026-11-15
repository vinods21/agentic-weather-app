# 0014. Aggregate read endpoints report per-item upstream failures inside a 200 (amends 0003)
Date: 2026-11-15 · Status: Accepted · Feature: weather-for-all-favourites

Amends [0003](0003-http-api-conventions.md) decision 2. ADR 0003 stays Accepted, and
everything it says that is not changed here still applies.

## Context
ADR 0003 §2 says every failure under `/api/` is a Problem Details body. That fits single-resource
requests. `GET /api/v1/favourites/weather` aggregates up to `max-per-client` independent upstream
lookups, and one bad city must not blank the whole dashboard (requirements A-3, AC-11, AC-12, AC-14).
The response still needs a stable, machine-readable error per item that a generated client can
handle, without new top-level `ErrorCode` values (A-9) and without leaking exception text (NFR
Security). Later aggregate endpoints will need the same convention, so it is recorded here.

## Decision
1. **Scope**: this applies to read endpoints that return a list of items, where each item needs
   its own upstream lookup. The request-level rules of ADR 0003 are unchanged. Validation,
   client-id, routing, database and unexpected failures still give a Problem Details response
   for the whole request (`400`, `404`, `405`, `500`).
2. **Per-item failure**: an *expected* per-item failure (a provider failure from the ADR 0005 §2
   contract: `WeatherProviderUnavailableException`, `LocationNotFoundException`, or the item
   missing the request's time budget) becomes an `error` object on that item instead of its
   data property. Each item carries **exactly one** of the data property or `error`. The
   item's identifying properties are always present.
3. **Status**: the response is `200` whenever the request itself is valid and the item list was
   loaded, **even when every item failed**. There is no `207 Multi-Status` and no `503` for "all
   items failed".
4. **Shape**: `error` is `{ "code": <string>, "detail": <string> }`, not a Problem Details
   object.
   - `code` uses the names of existing `ErrorCode` constants (here `UPSTREAM_UNAVAILABLE` and
     `LOCATION_NOT_FOUND`), and its schema enum is a subset of `Problem.code`. A per-item code
     never needs a new top-level `ErrorCode`.
   - `detail` is a fixed text per code. It never contains exception messages, URLs, class
     names or user input such as city names. `UPSTREAM_UNAVAILABLE` reuses the request-level
     text `The weather provider is temporarily unavailable.`
   - There is no per-item `requestId`. The response's `X-Request-Id` header identifies the request.
   - Timeouts are reported as `UPSTREAM_UNAVAILABLE`. There is no separate code.
5. **Evolution**: adding a value to a per-item `code` enum is additive within v1. The spec says
   that clients must tolerate unknown codes (treat them as "no data for this item").
6. **Bugs are never masked**: an unexpected exception during any item fails the whole request
   with `500 INTERNAL_ERROR` (existing handler), never a per-item error.
7. **Logging**: an endpoint that returns at least one per-item error writes exactly one WARN line
   per request with counts only (failed, total, timed out). It never includes cities, labels,
   client ids or coordinates. The adapter's per-call WARN lines (ADR 0006 §5) are unchanged.
   A fully successful request writes only the access-log line at INFO or above.
8. **OpenAPI**: the "exactly one of" rule is written as two optional properties plus a
   description, not as `oneOf`, because the conformance checker supports only the ADR 0002
   subset. Conformance ITs assert the exactly-one rule explicitly.

## Consequences
- The UI has one code path: render each item from its data property or its `error`.
- Monitoring that counts `5xx` responses won't see upstream outages on this endpoint. Operators
  use the per-request WARN line and the adapter's `Upstream call failed` lines instead.
- Clients must check every item, and they can't use the HTTP status to detect an upstream outage.
- Future aggregate endpoints reuse this shape, and reviewers check new per-item codes against
  `ErrorCode`.

## Alternatives considered
- **`503 UPSTREAM_UNAVAILABLE` when all items fail** (or when any item fails): gives a clearer
  signal for a total outage, but it adds a second UI code path and hides the items that did load.
  A human may choose it on the PR (requirements A-3).
- **`207 Multi-Status`**: WebDAV semantics, poorly supported by generated clients, and out of scope.
- **A full Problem Details object per item**: repeats `type`, `title`, `status`, `instance` and
  `requestId` per item for no client benefit, and `status` inside a `200` is confusing.
- **Omitting failed items**: the UI could no longer label the place that failed, and the list
  order would no longer match the stored order.
- **`oneOf` in the schema**: more precise for generators, but the conformance checker would have to
  grow or a validator library would be needed (ADR 0002).
