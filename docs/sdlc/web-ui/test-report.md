# Web UI: Test report
Branch: feature/web-ui @ a079efc (+ tester commit) · Date: 2026-11-16 · Env: host Windows 11, node v24.16.0 / npm 11.13.0; docker compose (postgres:17, redis:7) for the backend ITs

UI tests are Vitest 4.1.12 + jsdom + Testing Library, offline (fetch mocked at the boundary, the
network guard fails any unmocked fetch/XHR), `TZ=America/Los_Angeles`. There is no automated E2E
layer (ADR 0018, human decision R-3): the manual smoke checklist in `ui/README.md` is left for the
human (see "NEEDS HUMAN" below).

Test files added by the tester (developer's 17 files left as they were, nothing duplicated):
`ui/src/views/SearchView.ac.test.tsx`, `ui/src/views/FavouritesView.ac.test.tsx`,
`ui/src/views/DashboardView.ac.test.tsx`, `ui/src/App.ac.test.tsx`, `ui/src/main.test.tsx`
(boots the real `main.tsx`), `ui/src/nfr.test.ts` (static checks over production sources).
Prefix `T:` below = tester file, otherwise developer file.

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `viteConfig.test.ts` "AC-1: builds with a relative base"; pipeline: `npm --prefix ui run build` (exit 0, `dist/index.html` + `assets/index-<hash>.js/.css`), `git diff --name-only origin/main...HEAD` lists only `ui/**`, `docs/sdlc/web-ui/**`, `docs/adr/0017-*`, `docs/adr/0018-*`, `docs/architecture/overview.md`; `./gradlew build` green. `npm ci` from a clean checkout not re-run by the tester (it would re-download; see Not covered) | unit + pipeline | PASS (npm ci: NEEDS HUMAN / PR re-check) |
| AC-2 | `viteConfig.test.ts` "AC-2: dev server is fixed to localhost:5173…", "AC-2: preview server uses the same fixed origin". Second `npm run dev` failing on a taken port: manual | unit + manual | PASS (manual part NEEDS HUMAN) |
| AC-3 | `config.test.ts` (4 × AC-3), `client.test.ts` "AC-3: a custom base URL gives no double slash"; T: `main.test.tsx` "AC-6, AC-7, AC-3: first load … default base URL"; T: `SearchView.ac` "AC-19: the message names a custom base URL without /api/v1"; `ui/.env.example` present | unit + component | PASS |
| AC-4 | `networkGuard.test.ts` (3 × AC-4); whole suite runs offline; `npm test` runs `tsc --noEmit` first; T: `nfr.test.ts` "AC-4 / design: only the API client calls fetch, and it omits credentials" | unit | PASS |
| AC-5 | pipeline: `git check-ignore` → `ui/.gitignore:1:node_modules/`, `ui/.gitignore:2:dist/`; `git status --porcelain` after build shows neither; `git ls-files ui/package-lock.json` tracked | pipeline | PASS |
| AC-6 | `clientId.test.ts` (5 × AC-6); T: `main.test.tsx` "AC-6, AC-7, AC-3: first load stores a valid client id…", "AC-6: a reload reuses the stored id" | unit + component | PASS |
| AC-7 | `client.test.ts` "AC-7: weather requests carry no X-Client-Id…", "AC-7: every favourites request carries X-Client-Id, credentials omit and no X-Request-Id"; T: `main.test.tsx` (header = stored id); T: `FavouritesView.ac`/`DashboardView.ac` assert the header on GET/POST/PATCH/PUT/DELETE and both aggregates | unit + component | PASS |
| AC-8 | `clientId.test.ts` (AC-8 × 6 incl. `bad id!`, 65 chars, throwing storage), `App.test.tsx` "AC-8: a notice is shown…", "AC-8: no notice…"; T: `main.test.tsx` "AC-8: an invalid stored id … is replaced before any request" (×2), "AC-8: when localStorage throws, the app still works with an in-memory id and shows the notice" | unit + component | PASS |
| AC-9 | `SearchView.test` "AC-9: shows current weather and one forecast row per day", `format.test.ts` (AC-9 × 4); T: `SearchView.ac` "AC-9: activating the Search button sends both requests and shows every field of the result" (button path, feels-like, observed time in local TZ, forecast row date/min/max/rain/condition) | component + unit | PASS |
| AC-10 | `SearchView.test` "AC-10: an empty city sends nothing…", `validation.test.ts` (3 × AC-10); T: `SearchView.ac` "AC-10: a city longer than 100 characters…", "AC-10: exactly 100 characters (after trimming) are sent", "AC-10: the trimmed value is URL-encoded (São Paulo -> S%C3%A3o%20Paulo)", "AC-10: a corrected input clears the inline error" | component + unit | PASS |
| AC-11 | `SearchView.test` "AC-11, AC-12: units change re-sends both…", `App.test` "AC-11: the Units control defaults to metric and stores the choice", `units.test.ts` (4 × AC-11); T: `SearchView.ac` "AC-11: imperial shows °F and mph; the choice is reused after a reload", T: `main.test` "AC-11: a stored imperial choice is used on load", T: `DashboardView.ac` "AC-28, AC-11: with imperial units…" | component + unit | PASS |
| AC-12 | `SearchView.test` "AC-11, AC-12…" (days=3 → 1 request, 3 rows), `format.test.ts` "AC-12: forecast dates have no timezone shift"; T: `SearchView.ac` "AC-12: Days defaults to 5, offers 1..7, and dates are not shifted by the timezone" (asserts TZ is America/Los_Angeles, row shows 15 Nov, not 14) | component + unit | PASS |
| AC-13 | `errors.test.ts` "AC-13…"; T: `SearchView.ac` "AC-13: LOCATION_NOT_FOUND shows the not-found text and no previous result stays" | unit + component | PASS |
| AC-14 | `errors.test.ts` "AC-14…"; T: `SearchView.ac` "AC-14: UPSTREAM_UNAVAILABLE shows the message with Retry, and Retry sends the same requests" | unit + component | PASS |
| AC-15 | `errors.test.ts` (2 × AC-15); T: `SearchView.ac` "AC-15: a server city error is shown inline; fields without a control are listed" (units, days, X-Client-Id, body), "AC-15: only a city error gives the inline error…" | unit + component | PASS |
| AC-16 | `SearchView.test` "AC-16: a forecast failure keeps the current weather and offers a forecast-only retry" | component | PASS |
| AC-17 | `SearchView.test` "AC-17: only the latest search is displayed…"; T: `SearchView.ac` "AC-17: the loading indicator shows while in flight and disappears when the requests settle", "AC-17: a units change mid-flight shows only the latest (imperial) result" | component | PASS |
| AC-18 | `client.test.ts` (3 × AC-18), `errors.test.ts` (5 × AC-18); T: `SearchView.ac` "AC-18: a 500 INTERNAL_ERROR … Reference", "AC-18: an HTML proxy page is not rendered…X-Request-Id", "AC-18: a 415 JSON body without code…", "AC-18: an unknown 4xx code shows the Problem detail as plain text"; T: `FavouritesView.ac` "AC-27, AC-18: a 500 on delete…"; T: `DashboardView.ac` "AC-30, AC-18…" | unit + component | PASS |
| AC-19 | `client.test.ts` (2 × AC-19), `errors.test.ts` "AC-19…"; T: `SearchView.ac` "AC-19: a rejected fetch shows the base URL message with Retry, and Retry recovers", "AC-19: the message names a custom base URL…"; T: `FavouritesView.ac` "AC-21, AC-19: a failed list load…"; T: `DashboardView.ac` "AC-30: the weather aggregate failing on the network…" | unit + component | PASS |
| AC-20 | `messages.test.ts` "AC-20: every Problem.code value has a message", "AC-20: every FavouriteWeatherError.code value has a message" | unit | PASS |
| AC-21 | `FavouritesView.test` (2 × AC-21); T: `FavouritesView.ac` "AC-21: an item without a label shows its city and location…", "AC-21, AC-19…" | component | PASS |
| AC-22 | `FavouritesView.test` "AC-22: adding appends…", `validation.test.ts` (2 × AC-22), `client.test.ts` "AC-22, AC-25, AC-26: JSON bodies…"; T: `FavouritesView.ac` "AC-22: a whitespace-only label is sent as no label…", "AC-22: a label over 50 characters is rejected inline without a request", "AC-22: the add form applies the AC-10 city rules" (×2) | component + unit | PASS |
| AC-23 | `SearchView.test` "AC-23: Add to favourites posts the typed city and shows Saved"; T: `SearchView.ac` "AC-23, AC-24: Add to favourites errors appear next to the button…", "AC-23: Saved stays after a units change and resets on a new city search" | component | PASS |
| AC-24 | `FavouritesView.test` "AC-24: a duplicate keeps the list and the input", `errors.test.ts` "AC-24…"; T: `FavouritesView.ac` "AC-24: %i %s keeps the list and the input…" (409 limit, 404, 503), "AC-24: 400 VALIDATION_FAILED puts city/label errors on their inputs and lists the others" | component | PASS |
| AC-25 | `FavouritesView.test` (2 × AC-25: 404 + reload, Escape); T: `FavouritesView.ac` "AC-25: Save sends the new label and shows the returned label; focus returns to Rename", "AC-25: saving an empty label sends {"label":null}…", "AC-25: Cancel closes the rename without a request; a label over 50 characters is rejected" | component | PASS |
| AC-26 | `FavouritesView.test` "AC-26: Move down sends the new order…"; T: `FavouritesView.ac` "AC-26: Move up sends the new order; the server order is shown…", "AC-26: move buttons are keyboard-operable and disabled while a reorder is pending", "AC-26: 400 VALIDATION_FAILED on ids shows the changed-elsewhere text and reloads the list" | component | PASS |
| AC-27 | `FavouritesView.test` (2 × AC-27); T: `FavouritesView.ac` "AC-27: the confirmation is keyboard-operable; Escape cancels it without a request" (also last item → previous item's Delete), "AC-27: 404 on delete removes the item and reloads the list without an error", "AC-27, AC-18: a 500 on delete keeps the item…" | component | PASS |
| AC-28 | `DashboardView.test` "AC-28: sends both aggregates in parallel…"; T: `DashboardView.ac` "AC-28: each card shows heading (label or city), location, weather details and 3 compact forecast rows", "AC-28, AC-11…" | component | PASS |
| AC-29 | `DashboardView.test` "AC-29: per-item errors stay in their section…", `messages.test.ts` "AC-29…"; T: `DashboardView.ac` "AC-29: LOCATION_NOT_FOUND per item; every item failing with HTTP 200 still renders all cards", "AC-29: a card whose forecast error is set still shows its weather…" | component | PASS |
| AC-30 | `DashboardView.test` (2 × AC-30); T: `DashboardView.ac` "AC-30: the weather aggregate failing on the network … Retry re-sends only it", "AC-30: both aggregates down shows the message once; Retry re-sends both", "AC-30, AC-18: a 503 … and a 500 …" | component | PASS |
| AC-31 | `DashboardView.test` "AC-31: Refresh and a units change re-send both requests"; T: `DashboardView.ac` "AC-31: Refresh shows the loading indicator…", "AC-31: the dashboard never polls (no interval, no extra requests after 10 minutes)"; T: `nfr.test` "AC-31 / NFR request budget: no setInterval anywhere…" | component + unit | PASS |
| AC-32 | `App.test.tsx` (AC-32 × 6: nav, default view, reload on each hash, focus on navigation); T: `App.ac` "AC-32: browser back/forward (hashchange) switches the view". Reload under `vite preview`: manual | component + manual | PASS (manual part NEEDS HUMAN) |
| AC-33 | `App.test.tsx` "AC-32, AC-33: loading #/search / #/favourites / #/dashboard shows that view and the attribution footer" (both links, role `link`, `href`) | component | PASS |
| AC-34 | `focus.test.ts` (2 × AC-34); T: `SearchView.ac` "AC-34: the Search view is operable by keyboard (Tab, type, Enter; Tab to Search, Space)"; T: `App.ac` "AC-34: on Search/Favourites/Dashboard, every control has an accessible name and every enabled control is reachable with Tab"; keyboard tests for AC-26/AC-27 above; all tester queries are role/label/text only | unit + component | PASS (visible focus in a real browser: NEEDS HUMAN) |
| AC-35 | `contrast.test.ts` (reference values, tokens declared, every pair, every token covered) | unit | PASS |
| AC-36 | 320 px layout: jsdom has no layout | manual | NEEDS HUMAN |
| AC-37 | `contract.test.ts` (17 schemas + 6 enums) + `ContractChecks` typecheck. Tester mutation proof (temporary edits, restored, not committed): (A) manifest `Location.required` `country`→`countryCode`: `tsc` fails (`types.ts(148)` TS2344) and "AC-37: Location matches the spec" fails; (B) `UNITS` + `'kelvin'`: 3 enum tests fail; (C) `types.ts` `Location.country`→`countryCode`: `tsc` fails | unit + typecheck | PASS |
| AC-38 | README reviewed: Node `>=22.12.0` + `engines` + `.nvmrc`; `npm ci`/`dev`/`build`/`test`/`typecheck`; `VITE_API_BASE_URL`; bash, PowerShell and compose backend commands; `.env` → `integrationTest` note; 127.0.0.1 note; 12-step checklist covering every AC-38 item. Running the checklist: manual | review + manual | README PASS; checklist run NEEDS HUMAN |
| NFR | T: `nfr.test.ts` (no `dangerouslySetInnerHTML`/`innerHTML`, no "account"/"login" wording, only `weather.clientId`/`weather.units` keys); T: `main.test` "NFR data retention…"; T: `FavouritesView.ac` "NFR security: API strings are rendered as text"; T: `SearchView.ac` "NFR request budget: typing and changing Days before a search send nothing; a search sends 2"; bundle size below | unit + component + pipeline | PASS |

## Summary
- Vitest (`npm --prefix ui test`, typecheck included): **215 passed / 0 failed** in 23 files
  (developer 146 in 17 files + tester 69 in 6 files). Ran 3 times in a row after the setup fix: all green.
- `npm --prefix ui run build`: OK. Bundle (Vite summary): `index-DDKHkwH5.js` 217.67 kB, **gzip 68.60 kB**
  (limit 150 KB); `index-D4lgG3AX.css` 4.13 kB, gzip 1.28 kB; `index.html` 0.39 kB.
- Gradle unit (`./gradlew build`, includes `test`): **750 passed / 0 failed** (UP-TO-DATE: no backend input changed).
- Gradle integration (`scripts/test-env.sh it`): **517 passed / 0 failed**.
- Static checks: `dangerouslySetInnerHTML` absent from `ui/src`; no testcontainers/playwright/cypress/puppeteer/msw in `ui/package.json`; no new dependencies.

## Failures / defects found
**Product bugs: none found.** Every automated AC test passes against the unchanged production code.

**Test issues (fixed, test code only):**
- Flaky timeout: with the extra test files running in parallel, the first test of each Favourites
  test file (`FavouritesView.test.tsx` "AC-21: lists favourites in response order…" and the tester's
  first AC-21 test) failed in 2 of 2 full runs: `Unable to find role="list" and name "Your favourites"`
  after the Testing Library default 1 s `waitFor` timeout. The first render in a cold worker took
  longer than 1 s on this Windows host. Fix: `configure({ asyncUtilTimeout: 5000 })` in
  `ui/src/test/setup.ts`. No assertion changed; then 3/3 full runs green.
- Own tests: two assertions in the new files were adjusted while writing them (forecast row text is
  concatenated spans; the visually hidden favourite name inside the Move buttons duplicates the
  label text), so the tests read the paragraph/date elements by role/text instead.

**Observations for the reviewer (not AC failures):**
- Rename/Delete/Save/Cancel buttons have the same accessible name on every item ("Rename", "Delete").
  This matches the design, but a screen-reader user who lists buttons can't tell items apart. Only
  the Move buttons include the name (AC-26). Possible follow-up.
- The add-form errors for `503`/network on Favourites show the AC-14/AC-19 texts without a Retry
  button (the user re-submits the form). This matches the AC-24 table, which asks only for the text.

## Not covered / risks
**NEEDS HUMAN: manual smoke checklist (`ui/README.md` "Manual smoke checklist", ADR 0018, R-3).**
The tester didn't start the dev server, `vite preview` or the backend (out of scope for this run). Setup:

1. `scripts/test-env.sh up` (repo root).
2. Start the backend with the CORS origin and stub provider set **in the shell, not in `.env`**
   (values in `.env` are also exported into `./gradlew integrationTest`):
   - bash: `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub ./gradlew bootRun`
   - PowerShell: `$env:WEATHER_API_CORS_ALLOWED_ORIGINS='http://localhost:5173'; $env:WEATHER_PROVIDER='stub'; ./gradlew bootRun`
3. `npm --prefix ui ci`, then `npm --prefix ui run dev` and open `http://localhost:5173` (not `127.0.0.1`).

| # | README step | Covers | Status |
|---|-------------|--------|--------|
| 1 | Search `London`: current weather + 5-day forecast | AC-9 | NEEDS HUMAN |
| 2 | Units → imperial (`°F`, `mph`); reload keeps imperial | AC-11 | NEEDS HUMAN |
| 3 | Days → 3: 3 rows | AC-12 | NEEDS HUMAN |
| 4 | Unknown city: not-found message, no old result | AC-13 | NEEDS HUMAN |
| 5 | Favourites: add `London` label `Home`, then `Paris` | AC-22 | NEEDS HUMAN |
| 6 | Add `London` again: "London is already in your favourites." | AC-24 | NEEDS HUMAN |
| 7 | Rename `Paris` → `Work`, move it up, delete one (confirm) | AC-25–AC-27 | NEEDS HUMAN |
| 8 | Re-add, open Dashboard: both cards, current weather + 3 forecast rows | AC-28 | NEEDS HUMAN |
| 9 | Reload on `#/favourites` and `#/dashboard`, also with `npm --prefix ui run build` + `npm --prefix ui run preview` | AC-32 | NEEDS HUMAN |
| 10 | Browser console: no CORS errors | AC-38 | NEEDS HUMAN |
| 11 | Devtools viewport 320 px, 5 favourites, 7-day forecast: no horizontal scroll, controls usable | AC-36 | NEEDS HUMAN |
| 12 | Stop the backend, search: "Can't reach the weather service at http://localhost:8080." with Retry | AC-19 | NEEDS HUMAN |

Also manual: AC-2 (with `npm run dev` running, a second `npm --prefix ui run dev` must exit with a
port-in-use error instead of picking another port), visible focus ring while tabbing in a real
browser (AC-34), and AC-1's `npm --prefix ui ci` from a clean checkout. The tester didn't re-run
`npm ci` because it deletes `node_modules` and downloads again (registry access). The build and
tests ran on the developer's install from the committed lock.

Other risks:
- jsdom has no layout or real focus styles, so AC-34 visible focus and AC-36 rely on the CSS
  static test plus the manual check.
- The drift test compares exact property sets (R-9): an additive optional property in
  `openapi-v1.json` fails `npm --prefix ui test`. Backend changes to the spec must run the UI tests.
  The repo has no CI for the UI yet.
- Node on this host is v24.16.0 (inside `engines` `>=22.12.0`); not verified on Node 22.
