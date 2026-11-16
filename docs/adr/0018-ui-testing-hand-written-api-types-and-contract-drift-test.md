# 0018. UI testing: Vitest + jsdom + Testing Library with a fetch-boundary network guard, hand-written API types checked against the OpenAPI spec, and a manual e2e smoke check
Date: 2026-11-16 · Status: Accepted · Feature: web-ui

## Context
The UI (ADR 0017) needs tests that run with `npm test` on a laptop, with no backend, no Docker and
no network (requirements AC-4). ADR 0001 forbids Testcontainers and any test code that starts or
stops containers. ADR 0002 makes the hand-written `src/main/resources/openapi/openapi-v1.json` the
source of truth for the API. The UI's TypeScript types must fail a test when they drift from that
file (AC-37), and the test must read it from its location in the repo, not from a copy. A browser
e2e suite (Playwright/Cypress) is out of scope. The test approach is the convention for every
later UI change, so it is recorded here.

## Decision
1. **Runner and environment.** Vitest (configured in `ui/vite.config.ts`, so it uses the same
   transform as the build) with the `jsdom` environment and React Testing Library. Tests are
   `ui/src/**/*.test.{ts,tsx}` files next to the code they test. `npm test` runs
   `npm run typecheck && vitest run`, so type errors also fail the test command. Vitest globals
   are off. The setup file calls RTL `cleanup()` and installs the network guard. `TZ` is set to
   `America/Los_Angeles` through `test.env`, so date handling is tested away from UTC.
2. **Network boundary.** HTTP is mocked only at `globalThis.fetch`, through a small hand-written
   helper (`ui/src/test/fetchMock.ts`) that matches method + URL, records each request (URL,
   method, headers, body, `credentials`) and returns real `Response` objects or deferred ones. Before every
   test, the setup file replaces `fetch` and `XMLHttpRequest` with a guard. The guard records
   any call that no test handler matched and rejects it. After every test, the setup fails the
   test if the record isn't empty. A swallowed rejection (e.g. shown as "Can't reach…") therefore
   still fails the test. No mock-server library (msw, nock) is used.
3. **Queries.** Component tests find elements only by role, label or text (`getByRole`,
   `getByLabelText`, `getByText`), never by CSS selector or test id (AC-34). They interact
   through `@testing-library/user-event`.
4. **Hand-written API types plus a contract manifest.** The UI's types are hand-written in
   `ui/src/api/types.ts`. No OpenAPI generator is added. `ui/src/api/contract.ts` declares, as
   `const` data:
   - for every schema the UI uses: its required and optional properties, its nullable
     properties and the `$ref` target of each object or array property;
   - every enum the UI uses (`Units`, `Condition.code`, `Problem.code`,
     `FavouriteWeatherError.code`), as `as const` arrays. The TypeScript union types are derived
     from these arrays (`typeof X[number]`), so enum values exist once.
   Two checks tie the parts together:
   - **Compile time** (`npm run typecheck`, which `npm test` runs first): type-level assertions
     prove that each interface's required and optional keys equal the manifest.
   - **Test time** (`contract.test.ts`): the test imports the spec as JSON from
     `../src/main/resources/openapi/openapi-v1.json` (a relative module import, not a copy and
     not Node `fs`). It asserts that, for each manifest schema, the required set, the optional
     set, the nullable set, the `$ref` targets and every listed enum are equal to the spec. It
     also asserts that the manifest covers exactly the schemas listed in AC-37.
   So renaming or removing a required property, or adding or removing an enum value, fails
   `npm test`.
5. **Error-message coverage.** A test reads `Problem.code` and `FavouriteWeatherError.code` from
   the same spec import and fails if any value has no entry in the UI's message tables (AC-20).
6. **Accessibility checks within reach of jsdom.** A unit test computes the WCAG contrast ratio
   of every colour pair declared in `ui/src/theme/contrastPairs.ts`. It reads the colour values
   from `ui/src/styles.css` (imported with `?raw`), so the CSS stays the single source. A second
   test fails if `styles.css` has no `:focus-visible` rule, or removes outlines
   (`outline: none` or `outline: 0`). Layout (320 px) is not testable in jsdom and is checked
   manually.
7. **End to end is a manual checklist.** `ui/README.md` contains a smoke checklist that is run
   against `./gradlew bootRun` with `WEATHER_PROVIDER=stub` (or the compose `app` profile), with
   the services started by `scripts/test-env.sh up`. The results are recorded in the feature's
   `test-report.md`. No test code starts or stops a backend or container (ADR 0001).
8. **Pipeline commands** (run from the repo root, because agent shells reset the working
   directory):
   - `npm --prefix ui ci`
   - `npm --prefix ui test` (includes the typecheck)
   - `npm --prefix ui run build`

   The tester and the pre-PR re-check run these in addition to `./gradlew build` and
   `scripts/test-env.sh it`, for any change under `ui/` or to `openapi-v1.json`.

## Consequences
- `npm test` is fast, deterministic and offline, and an accidental real request is a test
  failure, not a flaky pass.
- A change to a used schema in `openapi-v1.json` fails the UI tests until the UI is updated, even
  an additive optional property. This is intentional: the UI and the API stay in step. A backend
  developer who only runs `./gradlew build` won't see it, so the pipeline re-check (8) and
  reviewers must run the UI tests when the spec changes.
- The drift test checks property names, required-ness, nullability, `$ref` targets and enums.
  It doesn't check primitive types (`number` vs `string`) or formats. A type change in an
  existing property is a breaking v1 change anyway (ADR 0003 §1), and reviewers check it.
- There is no generator step, so there are no generated files to keep in sync and no extra
  dependency. The price is hand-maintaining 17 small interfaces and their manifest entries.
- jsdom has no layout, no real focus ring and no `HTMLDialogElement.showModal`. So confirmations
  are inline (no native modal dialog), and layout and visible focus are checked manually.
- The tester agent has no browser. The manual checklist may need a human to run the steps that
  need eyes on a browser (see the web-ui design, Risks).

## Alternatives considered
- **Generated types (`openapi-typescript`)**: exact by construction. But it adds a dependency.
  The "regenerate and diff" test needs to spawn a CLI from a test, which needs Node `fs` and
  `child_process` and therefore `@types/node`, which isn't in the allowed dependency list. The
  generated output for `oneOf`-free 3.0.3 schemas would also be no richer than the hand-written
  types.
- **Runtime schema validation (zod, ajv)**: a runtime dependency (not allowed) or a test-only
  validator, for a check that the manifest already does in about 100 lines.
- **msw for HTTP mocking**: a good library, but a dependency outside the allowed list. Mocking
  `fetch` directly is enough for one API client module.
- **Playwright/Cypress e2e**: out of scope (browser downloads, extra dependencies). It is the
  natural follow-up, and it would run against the compose services, never Testcontainers.
- **Jest**: needs a separate TypeScript/ESM transform setup. Vitest reuses the Vite config.
- **Copying the spec into `ui/`**: the copy can drift, which is exactly what the test must
  catch. AC-37 forbids it.
