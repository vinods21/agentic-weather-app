# 0002. Spec-first, hand-written OpenAPI document verified by tests
Date: 2026-11-15 · Status: Accepted · Feature: ui-backend-api-contracts

## Context
The UI team needs a machine-readable OpenAPI 3 document for `/api/v1` so they can generate
clients and mock servers. Nothing in the build produces one today. We had two options: hand-write
the document, or generate it from annotations with springdoc-openapi, which would be a new
dependency. We also need a way to check that real responses match the documented schemas,
which could pull in a JSON-schema validator library.

## Decision
- The OpenAPI document is **hand-written** (OpenAPI 3.0.3, JSON) at
  `src/main/resources/openapi/openapi-v1.json`. `OpenApiController` serves it at
  `GET /api/v1/openapi.json` with `application/json`. It is the source of truth for the contract
  (spec-first). One file per major API version, so a future `/api/v2` gets `openapi-v2.json`.
- Two integration tests keep it honest, and both are mandatory for any change under `/api/v1`:
  - **Drift test**: the set of `(method, path)` pairs from `RequestMappingHandlerMapping`
    under `/api/v1` (excluding the document itself) must equal the spec's operations.
  - **Conformance test**: real response bodies are checked against the referenced schemas
    (required properties present and non-null, no undocumented properties, enums, types) using
    Jackson `JsonNode` traversal in a small test helper.
- A unit test checks that the `Problem.code` enum in the spec equals the server's `ErrorCode` set.
- No springdoc, no Swagger UI, no JSON-schema validator library.
- Schemas don't use `additionalProperties: false`, so generated clients tolerate additive fields.
  The server-side conformance test enforces "no undocumented properties" instead.

## Consequences
- No new dependency and no runtime reflection or scanning cost.
- The spec can describe the contract precisely (Problem schema, shared responses, descriptions)
  without annotation clutter in controllers.
- Developers must edit the JSON by hand when the API changes. The drift test catches missing or
  extra operations, and the conformance test catches field drift in tested responses. Field
  drift in responses that no test exercises is not caught automatically, so reviewers must check
  it.
- The schema checker covers a subset of JSON Schema (required, properties, type, enum, items,
  `$ref`). If the spec starts using `oneOf`/`allOf`, the helper must grow or a validator library
  must be adopted via a new ADR.

## Alternatives considered
- **springdoc-openapi** (`springdoc-openapi-starter-webmvc-api`): it generates the spec from
  code, so drift is impossible by construction. Rejected for now because it adds a dependency
  and needs path remapping from `/v3/api-docs`. Expressing the shared Problem schema and exact
  required sets also needs heavy annotation, and the generated output is code-first, whereas the
  UI team wants a contract it can rely on before implementation changes.
- **JSON-schema validator library** (e.g. networknt): it gives fuller validation. Rejected because
  it is a new dependency, and Jackson tree checks cover what the acceptance criteria require.
- **YAML spec**: easier to read, but serving `openapi.json` would mean converting it at runtime or
  at build time. JSON can be served as is.
