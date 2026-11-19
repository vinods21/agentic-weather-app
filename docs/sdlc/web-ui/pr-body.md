## Summary
Adds the first web UI: a React 19 + Vite 7 + strict-TypeScript single-page app in a new `ui/`
directory. It is built and served separately from Spring Boot: the Vite dev server or
`vite preview` runs on `http://localhost:5173`, and the build produces static files in `ui/dist`.
It calls the existing `/api/v1` backend over CORS (ADR 0017).

**Views** (hash routing, so a reload works on a plain static host):
- **Search**: current weather and a 1–7 day forecast for a city, with a metric/imperial toggle.
- **Favourites**: add (from a form or from a search result), rename, reorder with Move up/down,
  and delete with confirmation.
- **Dashboard**: two aggregate requests, `/favourites/weather` and `/favourites/forecast?days=3`,
  with per-item errors.

**Client id:** generated once, kept in `localStorage` and sent as `X-Client-Id` on
`/favourites*` only.

**Errors:** every Problem `code` maps to a user-facing message, including field errors on the
matching input. Unexpected errors show a "Reference: <requestId>" line, and a network or CORS
failure has its own message.

**Attribution and accessibility:** Open-Meteo and CC BY 4.0 attribution is on every view. The UI
covers labels, keyboard use, visible focus and announced errors, and a test checks the palette's
contrast against WCAG thresholds.

**Dependencies:** runtime is only `react` and `react-dom`. There are 11 exact-pinned dev
dependencies, all published before 2026-11-02 and each justified in design.md. The JS bundle is
68.6 KB gzipped against a 150 KB budget.

**Tests:** API types are hand-written. A contract drift test reads
`src/main/resources/openapi/openapi-v1.json` in place, so a spec change that affects the UI fails
`npm --prefix ui test` (ADR 0018). The tests are Vitest, jsdom and Testing Library, with `fetch`
mocked and a guard that fails any test making a real network call.

**No backend change.** The Gradle build, `src/`, `compose.yaml`, `.env.example` and
`application.yml` are untouched. Only `ui/**` and docs change.

## Docs
- Requirements: [requirements.md](docs/sdlc/web-ui/requirements.md)
- Design: [design.md](docs/sdlc/web-ui/design.md)
- Test report: [test-report.md](docs/sdlc/web-ui/test-report.md)
- Status, decisions and deviations: [status.md](docs/sdlc/web-ui/status.md)
- UI README (setup, backend command, manual checklist): [ui/README.md](ui/README.md)
- ADRs:
  - [0017 Web UI: React + Vite SPA hosted separately](docs/adr/0017-web-ui-react-vite-spa-hosted-separately.md)
  - [0018 UI testing: hand-written API types and contract drift test](docs/adr/0018-ui-testing-hand-written-api-types-and-contract-drift-test.md)
- Architecture overview (updated, new "Web UI" section): [overview.md](docs/architecture/overview.md)

## Acceptance criteria
Project setup
- [x] AC-1: `npm ci` and `npm run build` in `ui/` produce `ui/dist`; only `ui/**` and docs
  change. (Re-running `npm ci` from a clean checkout: **NEEDS HUMAN**.)
- [x] AC-2: the dev server is pinned to port 5173 with `strictPort`. (The "second `npm run dev`
  fails" check: **NEEDS HUMAN**.)
- [x] AC-3: `VITE_API_BASE_URL` defaults to `http://localhost:8080`, with trailing slashes
  stripped and http(s) only.
- [x] AC-4: `npm test` needs no backend or network; any real network call fails the test.
- [x] AC-5: `node_modules` and `dist` are ignored, and `package-lock.json` is tracked.

Client id
- [x] AC-6: a client id is generated once and stored as `weather.clientId`.
- [x] AC-7: `X-Client-Id` is sent only on `/api/v1/favourites*`.
- [x] AC-8: an invalid stored id is replaced; without storage, an in-memory id is used and a
  notice is shown.

Search
- [x] AC-9: a search sends the exact current and forecast requests and renders the result.
- [x] AC-10: a blank city or one over 100 characters is rejected client-side, with no request.
- [x] AC-11: the units toggle refetches, and the choice is kept in `localStorage`.
- [x] AC-12: the Days control (1–7, default 5) refetches the forecast, and dates show with no
  timezone shift.
- [x] AC-13: `LOCATION_NOT_FOUND` shows a not-found message.
- [x] AC-14: `UPSTREAM_UNAVAILABLE` shows an unavailable message with Retry.
- [x] AC-15: `VALIDATION_FAILED` field errors are shown on the matching input.
- [x] AC-16: a forecast-only failure keeps current weather and offers a forecast-only Retry.
- [x] AC-17: a loading indicator shows; the Search button stays enabled (decision R-1), and only
  the latest search's result is shown.

Error mapping
- [x] AC-18: 5xx, unknown codes and non-Problem bodies show a generic message with
  "Reference: <requestId>".
- [x] AC-19: a network or CORS failure shows a "can't reach the weather service" message.
- [x] AC-20: a test fails if the spec's Problem `code` enum gains a code the UI has no message for.

Favourites
- [x] AC-21: opening Favourites lists them in stored order.
- [x] AC-22: the add form validates and creates a favourite with an optional label.
- [x] AC-23: "Add to favourites" from a search result.
- [x] AC-24: create errors (duplicate, limit reached, not found, unavailable, validation) show
  specific messages.
- [x] AC-25: rename works; an empty label clears it; a 404 is handled.
- [x] AC-26: Move up/down reorders via `PUT /favourites/order`, and a rejected reorder reloads the
  list.
- [x] AC-27: delete asks for confirmation; a 404 is handled.

Dashboard
- [x] AC-28: exactly two parallel aggregate requests (weather, and forecast with `days=3`).
- [x] AC-29: per-item errors render on their card, and unknown item codes are tolerated.
- [x] AC-30: an empty state links to Favourites.
- [x] AC-31: Refresh and units changes refetch; there is no automatic polling.

Navigation, attribution, accessibility, layout
- [x] AC-32: a `<nav>` offers the three views, and the view survives a reload. (Reload under
  `vite preview`: **NEEDS HUMAN**.)
- [x] AC-33: Open-Meteo and CC BY 4.0 attribution is shown on every view.
- [x] AC-34: labelled controls, keyboard operation, announced errors and focus styles. (The
  visible focus ring in a real browser: **NEEDS HUMAN**.)
- [x] AC-35: the palette passes WCAG contrast thresholds (unit test).
- [ ] AC-36: the 320 px layout has no horizontal scrolling. **NEEDS HUMAN**.

Contract and docs
- [x] AC-37: the hand-written types are checked against `openapi-v1.json`, and drift fails
  `npm test`.
- [ ] AC-38: the README documents Node, scripts, the backend command and the smoke checklist.
  (The README content passes; **running the checklist NEEDS HUMAN**.)

## Needs a human (before merging)
Run the README smoke checklist once, as decided in R-3; the agents have no browser.
```bash
scripts/test-env.sh up
# bash (set in the shell, NOT in .env: Gradle passes .env into the integration tests)
WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub ./gradlew bootRun
# PowerShell:
# $env:WEATHER_API_CORS_ALLOWED_ORIGINS='http://localhost:5173'; $env:WEATHER_PROVIDER='stub'; ./gradlew bootRun
npm --prefix ui ci
npm --prefix ui run dev     # open http://localhost:5173 (not 127.0.0.1)
```
Then tick off:
- the 12 checklist steps;
- the 320 px layout (AC-36);
- a reload on each view (AC-32);
- the visible focus ring (AC-34);
- a clean browser console;
- a second `npm run dev` failing on the taken port (AC-2).

**Decisions to confirm:**
- **No framework extras:** no router, state or CSS library (A-2..A-4).
- **Dashboard forecast:** the dashboard forecast is fixed at 3 days (A-5).
- **Adding from search:** saves the city as typed, not the canonical name (A-6).
- **Delete:** confirmed, with no undo (A-7).
- **No automated browser tests:** no Playwright or Cypress (A-11).

**Reviewer observations:**
- **Screen-reader labels:** the Rename, Delete, Save and Cancel buttons have the same accessible
  name on every favourite; only the Move buttons include the favourite's name.
- **Missing Retry:** the add form shows 503 and network errors without a Retry button.

**Follow-up chore (R-6, not in this PR):** add the npm commands to CLAUDE.md, to `/sdlc` step 5
and to the `.claude/settings.json` allow-list. Every npm command currently asks for approval.

## How to test locally
```bash
scripts/test-env.sh all      # backend: build + unit + integration tests (unchanged by this PR)
npm --prefix ui ci
npm --prefix ui test         # typecheck + 215 Vitest tests, no backend or network needed
npm --prefix ui run build    # static build into ui/dist
```
Last run: UI 215/215 tests passing and a 68.6 KB gzipped bundle. The backend's 750 unit and 517
integration tests also pass (Docker Compose services, no Testcontainers). Node v24.16.0 and npm
11.13.0; `engines` requires Node >= 22.12.0.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
