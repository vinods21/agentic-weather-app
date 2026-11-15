## Summary
Follow-up to #2. PR #2 was merged before its round-1 review fixes were pushed, so this PR delivers
those fixes plus one minor finding from the pre-review of them. It addresses all six points from
the [round-1 review](https://github.com/vinods21/agentic-weather-app/pull/2#pullrequestreview-5909139300).
The API contract (endpoints, response shapes, error codes) is unchanged except where noted below.
There are no new dependencies, migrations or Redis keys.

| Round-1 point | Fix | Commit |
|---|---|---|
| [minor] `days` accepted non-ASCII digits | Only `[+-]?[0-9]+` after trimming is accepted; the OpenAPI `days` description states the accepted form | 0fabbfd |
| [nit] `DaysParam` min/max vs hard-coded "1 and 7" | The range is fixed in `DaysParam.MIN`/`MAX` and the messages are built from it | d93f1af |
| [nit] Duplicate `DAYS_TYPE_MISMATCH` and dead branch | Removed | 7e1b6d7 |
| [minor] The 503 WARN line logged the provider's raw message | Only class names are logged; the provider contract says messages must not carry secrets | bf8dc0e, 1348a0c |
| [minor] `TypeMismatchException` returned 500 | Mapped to 400 `VALIDATION_FAILED` with a fixed message | 9bff0de |
| [nit] `@MockitoSpyBean` vs `@MockitoBean` | Recorded as a deviation; ADR 0004 and design.md updated | 267206f |
| [minor, from pre-review] `ConversionNotSupportedException` (a server-side error) returned 400 | Kept on the 500 `INTERNAL_ERROR` path | 8ea2431 |

**Behaviour changes for the UI:**
- `days` given in non-ASCII digits (for example fullwidth `３`) now returns 400.
- An unconvertible typed parameter returns 400 `VALIDATION_FAILED` with the generic message "has
  an invalid value". No current endpoint has a typed parameter, so this can't happen today.

## Docs
- Requirements: [requirements.md](docs/sdlc/ui-backend-api-contracts/requirements.md)
- Design (updated): [design.md](docs/sdlc/ui-backend-api-contracts/design.md)
- Test report: [test-report.md](docs/sdlc/ui-backend-api-contracts/test-report.md)
- ADR (one sentence widened): [0004](docs/adr/0004-weather-provider-port-with-stub-default.md)
- Status and deviations: [status.md](docs/sdlc/ui-backend-api-contracts/status.md)

## Acceptance criteria (unchanged from #2; all re-verified)
- [x] AC-1: current weather for London returns 200 (metric).
- [x] AC-2: `units=imperial` returns values converted from metric.
- [x] AC-3: the same request made twice returns the same data.
- [x] AC-4: whitespace around `city` is trimmed.
- [x] AC-5: `days=3` returns 3 consecutive UTC days.
- [x] AC-6: without `days`, the forecast has 5 days in metric.
- [x] AC-7: each invalid parameter returns 400 `VALIDATION_FAILED` with field errors.
- [x] AC-8: every invalid field is reported together.
- [x] AC-9: the 100-character limit on `city` is inclusive and is checked after trimming.
- [x] AC-10: an unknown city returns 404 `LOCATION_NOT_FOUND`.
- [x] AC-11: an unknown `/api/v1` path returns 404 `NOT_FOUND`.
- [x] AC-12: `POST` returns 405 with `Allow: GET`.
- [x] AC-13: when the provider is unavailable, the API returns 503 `UPSTREAM_UNAVAILABLE`, and
  the WARN line contains no secrets.
- [x] AC-14: an unexpected error returns a sanitised 500 `INTERNAL_ERROR`.
- [x] AC-15: a valid `X-Request-Id` is sent back.
- [x] AC-16: a missing or invalid id is replaced with a new UUID.
- [x] AC-17: a preflight from an allowed origin gets the CORS headers.
- [x] AC-18: a preflight from any other origin is rejected.
- [x] AC-19: the OpenAPI document is served.
- [x] AC-20: each weather operation documents its parameters, its 200 schema, and its
  400/404/500/503 responses.
- [x] AC-21: drift guard: the served endpoints match the spec's operations.
- [x] AC-22: real responses match the spec's schemas.
- [x] AC-23: `/actuator/health` is unaffected.

## Still needs a human decision (carried over from #2)
- **A-1:** the fixed-data provider is the runtime default in every environment.
- **A-8:** the UI dev-server origin for CORS.
- **Defaults to confirm:**
  - no authentication in v1;
  - the version goes in the URL;
  - `metric` is the default unit, and unit values are case-sensitive;
  - `days` is 1 to 7, default 5;
  - city matching ignores case.

## How to test locally
```bash
scripts/test-env.sh all
```
Last run: 150 unit tests and 107 integration tests, 0 failures, 0 skipped (Docker Compose
services, no Testcontainers).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
