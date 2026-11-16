# Web UI (React + Vite + TypeScript SPA): Design
Requirements: ./requirements.md · ADRs: 0001, 0002, 0003, 0005, 0009, 0010, 0014, 0016, 0017 (new), 0018 (new)

## Summary
A new, self-contained npm project in `ui/`: a React 19 + TypeScript (`strict`) single-page app
built with Vite 7. The only runtime dependencies are `react` and `react-dom`. It has three views
on hash URLs (`#/search` (the default), `#/favourites`, `#/dashboard`), a global units control,
and an Open-Meteo attribution footer. It calls the unchanged `/api/v1` backend through one
hand-written `fetch` client, cross-origin from `http://localhost:5173`.

- **Stack and hosting** (ADR 0017): static build in `ui/dist/` (`base: './'`); dev and preview
  servers on the fixed port 5173; `VITE_API_BASE_URL` (default `http://localhost:8080`);
  hash routing, React built-ins for state, one CSS file. No `<StrictMode>`.
- **API types** (ADR 0018): hand-written in `ui/src/api/types.ts`. A `const` contract manifest in
  `ui/src/api/contract.ts` is tied to them at compile time (type-level assertions) and to
  `../src/main/resources/openapi/openapi-v1.json` at test time (the spec is imported as JSON by
  relative path, not copied). No generator.
- **Tests** (ADR 0018): Vitest + jsdom + Testing Library, with HTTP mocked only at `fetch`. A
  guard fails any test that makes an unmocked `fetch`/XHR call. `npm test` = typecheck + tests,
  with no backend and no Docker. E2E is the manual smoke checklist in `ui/README.md`.

Nothing outside `ui/` and `docs/` changes: no Gradle, `src/**`, `compose.yaml`, `.env.example`,
`application.yml`, root `.gitignore`, `CLAUDE.md` or `.claude/` edit.

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `ui/package.json` scripts, `ui/vite.config.ts` (`base: './'`), `ui/index.html` | `npm ci` installs from the committed lock. `npm run build` = `npm run typecheck && vite build` → `dist/index.html` + `dist/assets/*-<hash>.js/.css`. Every file the feature adds is under `ui/` or `docs/sdlc/web-ui/`, plus `docs/adr/0017-*`, `docs/adr/0018-*` and `docs/architecture/overview.md`. A tester/PR check (see Test strategy) runs `git diff --name-only origin/main...HEAD` and `./gradlew build`. |
| AC-2 | `vite.config.ts` `server: {host: 'localhost', port: 5173, strictPort: true}`, `preview: {host: 'localhost', port: 5173, strictPort: true}` | `strictPort` makes Vite exit with an error if the port is in use. `viteConfig.test.ts` asserts the values. The tester also checks it manually (starts a second `npm run dev`). |
| AC-3 | `src/config.ts` `resolveApiBaseUrl(raw)`, `api/client.ts` `url(path)` | `undefined`/blank → `http://localhost:8080`. Otherwise trims, strips all trailing `/` and requires an absolute `http:`/`https:` URL (else throws). The URL is `base + '/api/v1' + path`, so there is no double slash. `main.tsx` passes `import.meta.env.VITE_API_BASE_URL`. `ui/.env.example` documents it. |
| AC-4 | `src/test/setup.ts`, `src/test/fetchMock.ts`, `npm test` script, `tsconfig.json` (`strict`) | Setup replaces `fetch`/`XMLHttpRequest` with a recording, rejecting guard before each test. `afterEach` fails the test if there were unmocked calls. `npm test` runs `tsc --noEmit` first. |
| AC-5 | `ui/.gitignore` (`node_modules/`, `dist/`, `coverage/`), committed `ui/package-lock.json` | The ignore file is inside `ui/`, and the root `.gitignore` is untouched (A-14). |
| AC-6 | `src/storage/clientId.ts` `loadClientId(getStorage)`, `randomUuid()` | Reads `weather.clientId`. If it is missing, generates a UUID v4 (`crypto.randomUUID`, or a `crypto.getRandomValues` fallback), checks it against `CLIENT_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/` and stores it. Called once in `main.tsx`. |
| AC-7 | `api/client.ts` | Only the favourites functions add `X-Client-Id`. The weather functions build headers without it. |
| AC-8 | `loadClientId`, `AppShell` notice | Invalid stored value → regenerate and overwrite. Any exception from `getStorage()`, `getItem` or `setItem` → `{id: randomUuid(), persistent: false}`. The shell then shows a `role="note"` notice: "Your browser storage is unavailable, so this browser will forget your favourites when the tab closes." |
| AC-9 | `views/SearchView.tsx`, `components/CurrentWeatherPanel.tsx`, `components/ForecastList.tsx`, `format/format.ts` | Submitting the form runs `getCurrentWeather` and `getForecast` in parallel. The view renders the location, `formatTemperature` (`Math.round` + ` °C`/` °F`), feels-like, `humidity %`, `formatWind` (`toFixed(1)` + ` m/s`/` mph`, plus direction `230°`), the description, and `formatObservedAt` (local time). The forecast is a `<ul>` with one `<li>` per day. |
| AC-10 | `src/validation.ts` `validateCity`, `SearchView` | Trim; empty → "Enter a city name."; `> 100` UTF-16 code units → "City name must be at most 100 characters." The error is a `<p id=… role="alert">`, referenced by the input's `aria-describedby`, with `aria-invalid="true"`. Query values are encoded with `encodeURIComponent` (not `URLSearchParams`, which writes spaces as `+`). |
| AC-11 | `state/AppContext.tsx` (`units`, `setUnits`), `storage/units.ts`, header `<select>` labelled "Units" | The choice is stored under `weather.units` (invalid or missing → `metric`). When a city has been submitted, SearchView re-runs both requests on a units change, and DashboardView re-runs both aggregates. The unit symbols come from the response's `units`. |
| AC-12 | `SearchView` "Days" `<select>` (1..7, default 5), `formatForecastDate` | A days change with a submitted city re-runs the forecast only (1 request). Dates are parsed as `YYYY-MM-DD` → `Date.UTC(y, m-1, d)` and formatted with `timeZone: 'UTC'`, so there is no local shift. The test runs with `TZ=America/Los_Angeles` (ADR 0018). |
| AC-13 | `api/errors.ts` `describeApiError`, `SearchView` | `LOCATION_NOT_FOUND` → `No weather data found for "<city>".`. Starting a search clears both result slots, so no previous result can stay on screen. |
| AC-14 | `describeApiError` (`retryable: true`), `components/ErrorMessage.tsx` | The AC-14 text plus a "Retry" button that re-runs both requests with the same city, units and days. |
| AC-15 | `describeApiError` (`fieldErrors` split by `knownFields`), `SearchView` (`knownFields: ['city']`) | `city` errors go inline, as for AC-10. Other fields are listed in the general alert as "<field>: <message>". |
| AC-16 | `hooks/useRequestSlot.ts` (two independent slots: `current`, `forecast`) | Each slot settles on its own. A forecast error renders inside the forecast section with its own "Retry", which re-runs only the forecast slot. |
| AC-17 | `useRequestSlot` (sequence guard + `AbortController`), `components/Loading.tsx` | While either slot is loading: `<p role="status">Loading weather…</p>` and the Search button is `disabled`. Every `run` increments a per-slot sequence number and aborts the previous request. A result whose sequence number isn't the latest is dropped, and an `AbortError` is ignored. See Risks R-1 for how a second search can be submitted while Search is disabled. |
| AC-18 | `api/client.ts` (`ApiError`), `api/errors.ts` | See Error handling. 5xx, `INTERNAL_ERROR`, `METHOD_NOT_ALLOWED`, a non-Problem body and an unknown code on a 5xx → "Something went wrong. Please try again." An unknown code on a 4xx → the Problem `detail` if it is a non-empty string, else the generic text. "Reference: <id>" comes from the body's `requestId`, else the `X-Request-Id` header. The id is shown only if it matches `^[A-Za-z0-9._-]{1,64}$`. Everything is rendered as text. |
| AC-19 | `api/client.ts` (fetch rejection or the 15 s timeout → `{kind: 'network'}`), `describeApiError` | "Can't reach the weather service at <base URL>." with Retry. `<base URL>` is the resolved base without `/api/v1`. |
| AC-20 | `api/messages.ts` `PROBLEM_MESSAGES`, `ITEM_ERROR_MESSAGES`; `messages.test.ts` | Both are typed `Record<Code, …>` over the manifest's `as const` arrays. The test reads both enums from the imported spec and asserts `Object.hasOwn(table, code)` and a non-empty message for each value. |
| AC-21 | `views/FavouritesView.tsx` | On mount, `listFavourites()` → a `<ul>` in response order. Each item shows the label (if any), the `city`, and `location.name, location.country`. `items: []` → "No favourites yet." plus the add form (always rendered). |
| AC-22 | `FavouritesView` add form, `validateCity`, `validateLabel` | Labels "City" and "Label (optional)". The body is built as `{city}` plus `label` only if the trimmed label is non-empty. Label `> 50` → "Label must be at most 50 characters.". On `201`, the returned item is appended and both inputs are cleared. |
| AC-23 | `SearchView` "Add to favourites" button | Posts `{city: <submitted trimmed city>}`. On `201`, a disabled button "Saved" replaces it. The state resets on the next new-city search, but not on a units or days change. Errors appear next to the button with the AC-24 texts. |
| AC-24 | `describeApiError` + `FavouritesView` (`knownFields: ['city', 'label']`) | The table texts come from `PROBLEM_MESSAGES`, with `<city>` = the submitted trimmed city. The list and the inputs are untouched on error. |
| AC-25 | `views/FavouriteItem.tsx` (inline rename) | "Rename" swaps in an input labelled "Label for <name>" plus "Save" and "Cancel". Escape (keydown on the input) or Cancel closes it without a request. Save sends `PATCH {label: trimmed}` or `{label: null}` when empty. On `200`, the item is replaced with the response. `404 NOT_FOUND` → "This favourite no longer exists." plus `listFavourites()`. Focus returns to the item's Rename button. |
| AC-26 | `FavouriteItem` move buttons, `FavouritesView#move` | The new id order is computed locally and sent as `PUT /favourites/order {ids}`. On `200`, the list = `response.items`. Move up is disabled on the first item and Move down on the last. All move buttons are disabled while a reorder is pending. Accessible names: `Move <span class="visually-hidden">{name}</span> up`. `400 VALIDATION_FAILED` with an error on `ids` → "Your favourites changed elsewhere. The list has been refreshed." plus a reload. |
| AC-27 | `FavouriteItem` inline confirm (A-7: no `<dialog>`, see ADR 0018 Consequences) | "Delete" → the item's actions become the text "Delete <name>?" plus the buttons "Yes, delete" and "Cancel". Focus moves to "Cancel", and Escape cancels. Cancel → no request, focus back to "Delete". `204` or `404` → the item is removed (`404` also reloads, with no message). Focus then goes to the next item's Delete, else the previous item's, else the add form's City input. |
| AC-28 | `views/DashboardView.tsx`, `DashboardCard.tsx` | On mount, `listFavouritesWeather(units)` and `listFavouritesForecast(units, 3)` start in the same tick, each in its own `useRequestSlot`, and neither awaits the other. Cards follow the weather response order, joined to forecast items by `id` in a `Map`. The `<h2>` heading is `label ?? city`. |
| AC-29 | `DashboardCard`, `ITEM_ERROR_MESSAGES` | Each section independently renders its data or `ITEM_ERROR_MESSAGES[code] ?? "No data available."`. A missing data property and a missing `error` are also treated as "No data available.". Forecast items whose `id` isn't among the weather ids are never looked up. |
| AC-30 | `DashboardView` | Weather `items: []` → "No favourites yet." plus `<a href="#/favourites">Go to Favourites</a>`. A whole-request failure renders `ErrorMessage` per failed slot. One dashboard "Retry" re-runs only the failed slots. If only the forecast failed, the cards render weather and the forecast sections are left out. |
| AC-31 | `DashboardView` "Refresh" button, units effect | Both re-run both slots with the current units. `<p role="status">Loading dashboard…</p>` shows while either slot is loading. There is no timer or interval anywhere in `ui/src`. |
| AC-32 | `hooks/useHashRoute.ts`, `components/Nav.tsx`, `App.tsx` | `<nav aria-label="Main">` with links `#/search`, `#/favourites`, `#/dashboard` (the current one has `aria-current="page"`). The route comes from `location.hash` on load and on `hashchange`. Unknown or empty → Search. Hash URLs work after a reload on any static server. On a route change, focus moves to the view's `<h1 tabindex="-1">`. |
| AC-33 | `components/Footer.tsx` in `App` | `<footer>` with "Weather data by <a href="https://open-meteo.com/">Open-Meteo.com</a>" and "<a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>". `App.test.tsx` checks both links on each of the 3 routes. |
| AC-34 | All components, `styles.css` (`:focus-visible` outline), `focus.test.ts` | Native elements only, and every control has a `<label for>` (or a `fieldset`/`legend`). Errors use `role="alert"`, loading uses `role="status"`. The CSS test asserts a `:focus-visible` rule and no `outline: none` or `outline: 0`. A keyboard test tabs through the Search view and operates it with Enter/Space. |
| AC-35 | `src/styles.css` (`--color-*` hex tokens), `src/theme/contrastPairs.ts`, `src/theme/contrast.ts`, `contrast.test.ts` | The test parses the tokens from the raw CSS. Every pair must be ≥ 4.5 (`text`) or ≥ 3 (`large-text`, `ui`), and every `--color-*` token must appear in at least one pair. |
| AC-36 | `styles.css` (mobile first, `overflow-wrap: anywhere`, `flex-wrap`, no fixed widths > 320 px) | Checked manually (smoke checklist). |
| AC-37 | `src/api/types.ts`, `src/api/contract.ts`, `contract.test.ts` | ADR 0018 §4. See Data model › API types. |
| AC-38 | `ui/README.md`, `ui/.nvmrc`, `package.json` `engines` | See Implementation plan step 11 for the content. The tester records the smoke run in `test-report.md` (see Risks R-3). |

## Components & packages
No Java package changes. All new code is under `ui/`:

```
ui/
  .gitignore            node_modules/, dist/, coverage/
  .npmrc                save-exact=true, engine-strict=true
  .nvmrc                22
  .env.example          # VITE_API_BASE_URL=http://localhost:8080  (commented, documented as public)
  README.md             AC-38
  index.html            <html lang="en">, viewport meta, <div id="root">, module script /src/main.tsx
  package.json          private, "type": "module", engines, scripts, exact versions
  package-lock.json
  tsconfig.json         one config for src + vite.config.ts (see below)
  vite.config.ts        defineConfig from 'vitest/config': react(), base, server, preview, test
  src/
    main.tsx            resolveApiBaseUrl + loadClientId + loadUnits once → createRoot(...).render(<App …/>)
    App.tsx             AppProvider + shell: header (title, Units select, storage notice), Nav, routed view, Footer
    styles.css          tokens (--color-*), layout, .visually-hidden, :focus-visible
    vite-env.d.ts       /// <reference types="vite/client" />; ImportMetaEnv { VITE_API_BASE_URL?: string }
    config.ts           resolveApiBaseUrl(raw: string | undefined): string
    validation.ts       validateCity(raw) / validateLabel(raw) → {ok: true, value} | {ok: false, message}
    storage/clientId.ts CLIENT_ID_KEY, CLIENT_ID_PATTERN, randomUuid(), loadClientId(getStorage) → {id, persistent}
    storage/units.ts    UNITS_KEY, loadUnits(getStorage), saveUnits(getStorage, units)  (never throw)
    api/contract.ts     UNITS, CONDITION_CODES, PROBLEM_CODES, FAVOURITE_ERROR_CODES (as const); SCHEMA_CONTRACT; ENUM_CONTRACT
    api/types.ts        interfaces + exported type-level ContractChecks tuple
    api/client.ts       createApiClient({baseUrl, clientId, fetchImpl?, timeoutMs?}) → ApiClient; ApiError; ApiFailure
    api/messages.ts     PROBLEM_MESSAGES, ITEM_ERROR_MESSAGES, fixed UI texts (GENERIC, NETWORK(base), …)
    api/errors.ts       describeApiError(err, {city?, knownFields}) → ErrorView
    state/AppContext.tsx  {api, baseUrl, units, setUnits, storagePersistent}; useApp()
    hooks/useHashRoute.ts  'search' | 'favourites' | 'dashboard'
    hooks/useRequestSlot.ts  generic {state, run(fn: (signal) => Promise<T>), reset()} with sequence guard + abort
    format/format.ts    formatTemperature, formatWind, formatPercent, formatForecastDate, formatObservedAt
    components/         Nav, Footer, Loading, ErrorMessage, CurrentWeatherPanel, ForecastList
    views/              SearchView, FavouritesView, FavouriteItem, DashboardView, DashboardCard
    theme/contrast.ts   relativeLuminance(hex), contrastRatio(a, b)
    theme/contrastPairs.ts  [{fg: '--color-text', bg: '--color-bg', kind: 'text'}, …]
    test/setup.ts       jest-dom/vitest, cleanup, network guard, localStorage.clear()
    test/fetchMock.ts   mockFetch(): {on(method, url | RegExp, responder), calls, deferred()}; json(status, body, headers)
    test/fixtures.ts    spec-shaped sample bodies (London/Paris current, forecast, favourites, problems)
    test/renderApp.tsx  render <App> with a given hash, base URL and client id
    **/*.test.ts(x)     colocated tests
```

Responsibilities and key rules:
- **`api/client.ts`**: the only module that calls `fetch`. Every call uses `credentials: 'omit'`
  and sends no `X-Request-Id`. JSON bodies get `Content-Type: application/json`. The
  favourites functions add `X-Client-Id`, and the weather functions never do.
  - Each call has its own `AbortController`. It is aborted by the caller's signal or by a
    15 000 ms timer, and the timer is cleared in `finally`.
  - Status 2xx: `204` → `undefined`, otherwise `await res.json()`. A body that doesn't parse
    → `ApiError({kind: 'http', status, requestId})` with no problem.
  - Non-2xx: read the text, try `JSON.parse`. A *problem* is an object with a string `code`.
    The `requestId` is `problem.requestId` if it is a string, else
    `res.headers.get('X-Request-Id')`.
  - A rejection that is an `AbortError` caused by the caller's signal is rethrown as is (the
    slot drops it). A timeout, or any other rejection → `ApiError({kind: 'network'})`.
  - Functions:
    - `getCurrentWeather(city, units, signal?)`
    - `getForecast(city, units, days, signal?)`
    - `listFavourites(signal?)`
    - `createFavourite({city, label?})`
    - `updateFavouriteLabel(id, label | null)`
    - `reorderFavourites(ids)`
    - `deleteFavourite(id)`
    - `listFavouritesWeather(units, signal?)`
    - `listFavouritesForecast(units, days, signal?)`
  - Path ids go through `encodeURIComponent`.
- **`hooks/useRequestSlot.ts`**: state `{status: 'idle'} | {status: 'loading'} | {status:
  'success', data} | {status: 'error', error}`. `run` aborts the previous request, increments
  `seqRef`, sets `loading`, awaits, and commits only if `seq === seqRef.current` and the
  component is still mounted.
  - `reset()` aborts the request and sets the slot to `idle`.
  - Every view uses this hook for reads.
  - Mutations (create/patch/reorder/delete) use a local `pending` flag, because they are user
    actions with their own buttons, and their buttons are disabled while pending.
- **`SearchView`**: state `{submitted: {city} | null, days, addState}`. Submit validates first
  (no request on error), then resets both slots and the add state and runs both. The units
  effect fires only on a real change (previous-value ref) when `submitted` is set. Days
  `onChange` re-runs the forecast slot only.
- **`FavouritesView`**: a list slot plus local `items` (replaced only by server responses, A-8),
  the add form, a view-level alert, and focus refs keyed by id for AC-27.
- **`DashboardView`**: two slots, run on mount, on a units change and on Refresh. Retry re-runs
  the slots whose state is `error`.
- **`AppContext`**: built once in `main.tsx`. Tests render `<App>` through `renderApp`, which
  builds the context from parameters (a fixed base URL and client id), so no test depends on
  `import.meta.env`.

### Dependencies (all exact versions; baseline below, see "Version selection")
| Package | Kind | Baseline (major line) | Why |
|---------|------|------------------------|-----|
| `react` | runtime | 19.2.x | UI library (the human's choice). |
| `react-dom` | runtime | 19.2.x (= react) | DOM renderer for React. |
| `vite` | dev | 7.x | Dev server on :5173, production bundler, transform for Vitest. |
| `@vitejs/plugin-react` | dev | 5.x | JSX/TSX transform and fast refresh for Vite. |
| `typescript` | dev | 5.9.x | `strict` typechecking (`npm run typecheck`, AC-4/AC-37). |
| `@types/react` | dev | 19.2.x | React type declarations for TS. |
| `@types/react-dom` | dev | 19.2.x | `react-dom/client` type declarations. |
| `vitest` | dev | 4.x | Test runner that reuses the Vite config (ADR 0018). Its peer range must include the chosen Vite. |
| `jsdom` | dev | 27.x | DOM environment for component tests in Node. |
| `@testing-library/react` | dev | 16.x | Renders components and gives role/label queries (AC-34). |
| `@testing-library/dom` | dev | 10.x | Required peer dependency of `@testing-library/react` 16. It isn't installed automatically, and it is part of Testing Library. |
| `@testing-library/user-event` | dev | 14.x | Realistic keyboard and pointer interaction (Tab, Enter, Space, Escape). |
| `@testing-library/jest-dom` | dev | 6.x | DOM matchers (`toBeDisabled`, `toHaveAttribute`, `toHaveAccessibleName`) through `@testing-library/jest-dom/vitest`. |

That is 2 runtime and 11 dev dependencies. No OpenAPI generator (types are hand-written), no
`@types/node` (see Risks R-4), and no msw, eslint or prettier.

**Version selection (two-week rule).** The adoption cutoff for this feature is **2026-11-02**
(today minus 14 days).
1. For each package, the developer runs `npm view <pkg> time --json` (a read-only registry
   query) and picks the newest version in the major line above that was published on or before
   the cutoff.
2. Peer ranges must line up: the Vitest version's `vite` peer range must include the chosen
   Vite, and `@vitejs/plugin-react`'s range must include the chosen Vite.
3. The developer writes the exact versions into `package.json` and installs once with
   `npm --prefix ui install --before=2026-11-02`. This needs human approval. `--before` makes
   npm resolve every transitive package as of the cutoff too, and it fails if a pinned version
   is newer.
4. The developer records each chosen version and its publish date under "Dependency versions"
   in `status.md`.

If a major line above has no release before the cutoff that satisfies the peer ranges, the
developer stops and asks rather than switching majors.

**Node.js**: `engines: {"node": ">=22.12.0", "npm": ">=10"}` (Vite 7's floor, inside A-12's
"22 or newer"), `.nvmrc` = `22`, `engine-strict=true`.

### `package.json` scripts
| Script | Command |
|--------|---------|
| `dev` | `vite` |
| `build` | `npm run typecheck && vite build` |
| `preview` | `vite preview` |
| `typecheck` | `tsc --noEmit -p tsconfig.json` |
| `test` | `npm run typecheck && vitest run` |
| `test:watch` | `vitest` |

### `tsconfig.json` (single config)
- Compiler options:
  - `target` and `lib`: `ES2022`, `lib` adds `DOM` and `DOM.Iterable`;
  - module: `module: ESNext`, `moduleResolution: Bundler`, `jsx: react-jsx`;
  - strictness: `strict`, `noUncheckedIndexedAccess`, `noUnusedLocals`, `noUnusedParameters`;
  - build: `isolatedModules`, `verbatimModuleSyntax`, `resolveJsonModule`, `skipLibCheck`,
    `noEmit`;
  - `types: ["vite/client"]`.
- `include: ["src", "vite.config.ts"]`.
- `resolveJsonModule` is used only by `contract.test.ts` and `messages.test.ts` for the spec
  import.

### `vite.config.ts`
- `plugins: [react()]`, `base: './'`.
- `server` and `preview`: `{host: 'localhost', port: 5173, strictPort: true}`.
- `test`:
  - `{environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], include:
    ['src/**/*.test.{ts,tsx}'], env: {TZ: 'America/Los_Angeles'}, restoreMocks: true,
    unstubGlobals: true}`.
  - Don't widen `server.fs.allow`. Vitest's Node-side module loading isn't subject to it. If
    the spec import is ever refused, record a deviation instead of allowing the repo root on
    the dev server.
- No `process`/Node APIs in the config (no `@types/node`).

## API
No backend change. The UI consumes these existing `/api/v1` operations (as specified in
`openapi-v1.json`). `B` = the resolved base URL.

| UI action | Request | Headers | Success | Errors handled |
|-----------|---------|---------|---------|----------------|
| Search | `GET B/api/v1/weather/current?city=<enc>&units=<u>` | none custom | `200 CurrentWeather` | 400, 404 `LOCATION_NOT_FOUND`, 503, 5xx, network |
| Search / days | `GET B/api/v1/weather/forecast?city=<enc>&units=<u>&days=<1..7>` | none custom | `200 Forecast` | same |
| Favourites open/reload | `GET B/api/v1/favourites` | `X-Client-Id` | `200 FavouriteList` | 400, 5xx, network |
| Add (form/shortcut) | `POST B/api/v1/favourites` body `{"city":"…","label":"…"}` or `{"city":"…"}` | `X-Client-Id`, `Content-Type: application/json` | `201 Favourite` | 400 (`city`/`label` inline), 404, 409 ×2, 503, 5xx, network |
| Rename | `PATCH B/api/v1/favourites/{id}` body `{"label":"…"}` / `{"label":null}` | same | `200 Favourite` | 400, 404 `NOT_FOUND`, 5xx, network |
| Reorder | `PUT B/api/v1/favourites/order` body `{"ids":[…]}` | same | `200 FavouriteList` | 400 (`ids` → refresh), 5xx, network |
| Delete | `DELETE B/api/v1/favourites/{id}` | `X-Client-Id` | `204` | 404 (silent remove and reload), 5xx, network |
| Dashboard | `GET B/api/v1/favourites/weather?units=<u>` and `GET B/api/v1/favourites/forecast?units=<u>&days=3` | `X-Client-Id` | `200` lists with per-item `error` | whole-request 4xx/5xx, network |

Query parameter order is `city`, `units`, `days`, matching the ACs. The UI reads the `Location`
header of a `201` nowhere (it uses the body).

## Data model
- **No database, Flyway or Redis change.** There is no migration and no `CacheKeys` change.
- **Browser storage** (`localStorage`, the only persisted data):

  | Key | Value | Written |
  |-----|-------|---------|
  | `weather.clientId` | UUID v4 string matching `^[A-Za-z0-9._-]{1,64}$` | first load, or when the stored value is invalid |
  | `weather.units` | `metric` or `imperial` | on a units change |

  No API response is stored. A test asserts that after a full flow `localStorage` holds only
  these keys.
- **API types** (`ui/src/api/types.ts`, hand-written, mirroring the spec 1:1):
  - Enum unions derived from `contract.ts`: `Units`, `ConditionCode`, `ProblemCode`,
    `FavouriteErrorCode`.
  - Interfaces: `Location`, `Condition`, `CurrentWeather`, `ForecastDay`, `Forecast`, `Favourite`
    (`label?`), `FavouriteList`, `FavouriteWeather` (`label?`, `weather?`, `error?`),
    `FavouriteWeatherList`, `FavouriteForecast` (`label?`, `forecast?`, `error?`),
    `FavouriteForecastList`, `FavouriteWeatherError`, `CreateFavouriteRequest`
    (`label?: string | null`), `UpdateFavouriteRequest` (`label: string | null`),
    `ReorderFavouritesRequest`, `FieldError`, `Problem` (`errors?`).
  - Response enum fields are typed with the unions, but the UI code always handles unknown
    values (A-15, AC-29), e.g. `ITEM_ERROR_MESSAGES[code as string] ?? fallback`.
- **Contract manifest** (`ui/src/api/contract.ts`):

  ```ts
  export const SCHEMA_CONTRACT = {
    Location: { required: ['name','country','latitude','longitude'], optional: [], nullable: [], refs: {} },
    CurrentWeather: { required: [...9], optional: [], nullable: [], refs: { location: 'Location', condition: 'Condition' } },
    FavouriteList: { required: ['items'], optional: [], nullable: [], refs: { items: 'Favourite' } }, // array items → target
    // … all 17 schemas of AC-37: CurrentWeather, Forecast, ForecastDay, Location, Condition, Favourite,
    // FavouriteList, FavouriteWeather, FavouriteWeatherList, FavouriteForecast, FavouriteForecastList,
    // FavouriteWeatherError, CreateFavouriteRequest, UpdateFavouriteRequest, ReorderFavouritesRequest,
    // Problem, FieldError
  } as const;
  export const ENUM_CONTRACT = [
    { at: 'schemas.Condition.code', values: CONDITION_CODES },
    { at: 'schemas.CurrentWeather.units', values: UNITS },
    { at: 'schemas.Forecast.units', values: UNITS },
    { at: 'schemas.Problem.code', values: PROBLEM_CODES },
    { at: 'schemas.FavouriteWeatherError.code', values: FAVOURITE_ERROR_CODES },
    { at: 'parameters.Units', values: UNITS },
  ] as const;
  ```

  The 17 schemas count `FavouriteWeather`, `FavouriteForecast` and their `…List` schemas
  separately.
- **Compile-time tie** (`types.ts`): helper types `RequiredKeys<T>`, `OptionalKeys<T>`,
  `NullableKeys<T>` (`null extends T[K]`) and `Equals<A, B>`. Then
  `export type ContractChecks = [Assert<Equals<RequiredKeys<Location>,
  (typeof SCHEMA_CONTRACT.Location.required)[number]>>, …]`, with one required, one optional and
  one nullable assertion per schema. It is exported so that `noUnusedLocals` doesn't flag it.

## External integrations
- **Backend `/api/v1`** (the only network peer):
  - `fetch`, cross-origin, `credentials: 'omit'`, no cookies.
  - Client timeout 15 s per request, through `AbortController`. That is above the backend's
    aggregate deadline (5 s default, 30 s max is configurable, ADR 0013) for typical settings.
    A timeout shows the AC-19 network message with Retry.
  - No automatic retries and no polling: Retry and Refresh are explicit user actions (NFR).
  - Superseded reads are aborted, and their results are discarded by the sequence guard.
  - CORS: the backend must list `http://localhost:5173` in `WEATHER_API_CORS_ALLOWED_ORIGINS`.
    A CORS block is indistinguishable from "backend down" in the browser, and both give the
    AC-19 message. The README tells developers to check the console.
- **Open-Meteo**: never called by the UI. It is only linked in the footer (ADR 0005 attribution).

## Error handling
`describeApiError(error, ctx) → { message: string; inlineFieldErrors: Record<field, string>;
otherFieldErrors: FieldError[]; requestId?: string; retryable: boolean }`. It evaluates the
rules below in order:

1. `kind: 'network'` → `Can't reach the weather service at ${baseUrl}.`, retryable.
2. A problem with a known code:

   | Code | Message | Retryable |
   |------|---------|-----------|
   | `VALIDATION_FAILED` | Errors whose field is in `ctx.knownFields` go inline. Others go to `otherFieldErrors`. Message: "Please check the highlighted values." if everything is inline, else "Some values were not accepted:" followed by the list. | no |
   | `LOCATION_NOT_FOUND` | `No weather data found for "${city}".` | no |
   | `NOT_FOUND` | "This favourite no longer exists." | no |
   | `UPSTREAM_UNAVAILABLE` | "The weather service is temporarily unavailable. Please try again." | yes |
   | `FAVOURITE_ALREADY_EXISTS` | `${city} is already in your favourites.` | no |
   | `FAVOURITES_LIMIT_REACHED` | "You've reached the maximum number of favourites. Remove one to add another." | no |
   | `INTERNAL_ERROR`, `METHOD_NOT_ALLOWED` | generic (rule 3) plus Reference | yes |

3. Status ≥ 500, a non-problem body (e.g. Spring's default `415` JSON or a proxy HTML page),
   or an unknown code on a 5xx → "Something went wrong. Please try again." plus
   ` Reference: ${requestId}` when available, retryable.
4. An unknown code on a 4xx → the problem's `detail` (if it is a non-empty string, else the
   generic text) plus the Reference, not retryable.

The views decide what to do on top of that:
- `NOT_FOUND` on rename → reload the list.
- `NOT_FOUND` on delete → silent remove and reload.
- `VALIDATION_FAILED` on `ids` → the AC-26 text and a reload.

`components/ErrorMessage.tsx` renders `<div role="alert">` with the message, the
`otherFieldErrors` `<ul>`, the reference and an optional Retry `<button>`. It renders text
only. `dangerouslySetInnerHTML` never appears in `ui/src` (reviewers grep for it, and a test
renders markup-like API strings and asserts they appear as text).

Per-item dashboard errors (AC-29) never go through `describeApiError`. They use
`ITEM_ERROR_MESSAGES`, falling back to "No data available.".

## Security considerations
- The client id is a partition key, not a credential (ADR 0009). The UI never presents it,
  never logs it and never calls it an account or login. It goes only to `/api/v1/favourites*`.
- `credentials: 'omit'` and no cookies, which matches the backend's `allowCredentials=false`.
- No HTML injection: all API strings are rendered as React text children. External links are
  constants. `href`s are never built from API data.
- `VITE_API_BASE_URL` is public (it ends up in the bundle) and is validated as an absolute
  http(s) URL. There are no secrets in `ui/`. `.env`/`.env.*` files in `ui/` are ignored by the
  root `.gitignore` (A-14), except `.env.example`.
- Supply chain: 13 packages, exact versions, a committed lock file, the two-week age rule
  enforced with `--before`, `engine-strict`, and installs only with human approval.
- No telemetry and no third-party requests. Only the Reference id from the server is shown,
  and only if it matches the safe pattern.
- Not in scope (follow-ups): a Content-Security-Policy on the static host, and a review of
  `ignore-scripts` for npm install.

## Test strategy
All UI tests are unit/component tests run by `npm test` (Vitest + jsdom) with no backend,
Docker or network. There are no integration tests for the UI: the backend ITs are unchanged and
must still pass. E2E is the manual smoke checklist (ADR 0018). No Testcontainers, Playwright or
Cypress, and nothing starts or stops containers (ADR 0001).

Conventions:
- Test names start with the AC: `it('AC-13: shows not-found message and clears the previous
  result', …)` (Vitest uses `it`/`describe` names, which play the role of `@DisplayName`).
- Find elements by role, label or text only.
- Every test that expects requests declares them with `mockFetch().on(...)`. Assertions on
  method, URL (exact string), headers, `credentials` and body use the recorded calls.
- Use `deferred()` responders for in-flight states and races.

| AC | Test file(s) | Type |
|----|--------------|------|
| AC-1 | — (tester runs `npm --prefix ui ci`, `npm --prefix ui run build`, lists `ui/dist`, `git diff --name-only origin/main...HEAD`, `./gradlew build`) | pipeline check |
| AC-2 | `src/viteConfig.test.ts` (imports `../vite.config`; port, `strictPort`, `host`, also for preview) + manual: a second `npm run dev` fails | unit + manual |
| AC-3 | `config.test.ts` (unset, blank, with and without trailing `/`, multiple `/`, invalid) and `client.test.ts` (URL used) | unit |
| AC-4 | `test/networkGuard.test.ts`: an unmocked `fetch` rejects and is recorded (the test drains the record), and XHR `open` throws. The whole suite runs offline. `npm run typecheck` runs inside `npm test`. | unit |
| AC-5 | — (tester: `git status --porcelain` after ci+build shows no `ui/node_modules`/`ui/dist`; `git ls-files ui/package-lock.json`) | pipeline check |
| AC-6, AC-8 | `storage/clientId.test.ts` (fake `Storage`: empty, valid, `bad id!`, 65 chars, throwing getter/getItem/setItem; the `getRandomValues` fallback yields a v4 UUID) and `App.test.tsx` (the notice shows when storage throws, and it never says "account"/"login") | unit + component |
| AC-7 | `api/client.test.ts`: the header is on all 7 favourites functions and absent on both weather functions; `credentials === 'omit'`; no `X-Request-Id` | unit |
| AC-9–17, AC-23 | `views/SearchView.test.tsx` (AC-12 asserts that `Intl.DateTimeFormat().resolvedOptions().timeZone` is `America/Los_Angeles` first, then that the row shows `15` and `Nov` and not `14`). `format/format.test.ts` covers the rounding and decimal edge cases (`-0.4`, `11.5`, `4.05`). `validation.test.ts` covers the limits (100/101, 50/51, whitespace). | component + unit |
| AC-18, AC-19 | `api/errors.test.ts` (every rule above, the requestId body-vs-header precedence, an unsafe id hidden), `api/client.test.ts` (an HTML 502, a JSON 415 without `code`, a rejected fetch, a timeout with fake timers) and one rendering test in `SearchView.test.tsx` | unit + component |
| AC-20 | `api/messages.test.ts` (spec import) | unit |
| AC-21–27 | `views/FavouritesView.test.tsx` (AC-27 checks `document.activeElement` after cancel and after delete: next item, previous item, empty → City input) | component |
| AC-28–31 | `views/DashboardView.test.tsx` (both calls recorded before either resolves; exactly 2 calls; all-items-error with `200`; an unknown code `SOMETHING_NEW`; an orphan forecast id; only the forecast failing; Retry re-sends only the failed one; Refresh and units) | component |
| AC-32, AC-33 | `App.test.tsx` (nav landmark and links; render with `#/favourites` and `#/dashboard` already in `location.hash` simulates a reload; empty or unknown hash → Search; footer links on all 3 views) + manual `vite preview` reload | component + manual |
| AC-34 | `theme/focus.test.ts` (raw CSS), a keyboard test in `SearchView.test.tsx` (Tab to City, type, Enter; Tab to Search, Space), and role/label-only queries everywhere (reviewers check) | unit + component |
| AC-35 | `theme/contrast.test.ts` (the formula against known values, `#767676` on `#fff` ≈ 4.54; every pair; every token covered) | unit |
| AC-36 | manual (smoke checklist, 320 px in browser devtools) | manual |
| AC-37 | `api/contract.test.ts` (spec import; the manifest equals the spec for every schema and enum; the manifest schema set equals the AC-37 list) + the `ContractChecks` typecheck. The tester also proves the test works by temporarily renaming a required property and adding an enum value in a scratch copy of the manifest, without committing. | unit + typecheck |
| AC-38 | — (tester reviews the README content and runs the checklist; see R-3) | manual |
| NFR request budget / security / retention | `SearchView.test.tsx` (2 requests per search, 1 per days change, none while typing), `DashboardView.test.tsx` (2), and `App.test.tsx` (markup-like strings render as text; only the 2 `localStorage` keys exist) | component |
| NFR bundle | — (tester: the sum of the gzip sizes of the `.js` lines in the `vite build` summary ≤ 150 KB) | pipeline check |

### Commands for the pipeline (the CLAUDE.md-style mapping for the UI)
Run these from the repo root. Agent shells reset the working directory, so use `--prefix ui`
rather than `cd ui`.

| Task | Command | Who runs it |
|------|---------|-------------|
| Install exactly from the lock | `npm --prefix ui ci` | developer, tester, PR re-check |
| Typecheck only | `npm --prefix ui run typecheck` | developer |
| Unit/component tests (+ typecheck) | `npm --prefix ui test` | developer (before every commit that touches `ui/`), tester, PR re-check |
| Production build | `npm --prefix ui run build` | developer (before the last commit), tester, PR re-check |
| First install / dependency change (human approval) | `npm --prefix ui install --before=2026-11-02` | developer, once |
| Dev server for the smoke check | `npm --prefix ui run dev` (background) | tester |
| Backend for the smoke check | `scripts/test-env.sh up`, then `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub ./gradlew bootRun` (background) | tester |

- **Developer**: as well as `./gradlew build` (which must still pass with no change), every
  commit that touches `ui/` must pass `npm --prefix ui test`. The final commit must also pass
  `npm --prefix ui run build`.
- **Tester**: runs `./gradlew test`, then `scripts/test-env.sh it` (unchanged backend, which
  proves nothing regressed), then `npm --prefix ui ci`, `npm --prefix ui test`,
  `npm --prefix ui run build`, then the pipeline checks above, then the smoke checklist.
  - In `test-report.md`, the Env line adds `node <v>`/`npm <v>` (`node --version`,
    `npm --version`).
  - The AC table lists the Vitest test names (type `unit`/`component`/`manual`/`pipeline`).
  - The summary has separate counts for Gradle and Vitest.
- **PR re-check (orchestrator step 5)**: in addition to `./gradlew build` and
  `scripts/test-env.sh it`, run:
  1. `npm --prefix ui ci && npm --prefix ui test && npm --prefix ui run build`
  2. `git status --porcelain ui` must not list `node_modules`/`dist` (AC-5)
  3. `git diff --name-only origin/main...HEAD` must list only `ui/**`, `docs/sdlc/web-ui/**`,
     `docs/adr/0017-*`, `docs/adr/0018-*` and `docs/architecture/overview.md` (AC-1)
  4. the existing Testcontainers grep, plus
     `grep -nE "testcontainers|playwright|cypress|puppeteer|msw" ui/package.json` must be empty
  5. `grep -rn "dangerouslySetInnerHTML" ui/src` must be empty.

  pr-body.md's "how to test locally" adds the three npm commands.
- **PR reviewer**: checks the same items, the dependency table (versions and dates in
  `status.md`) and the role/label-only query rule.

## Implementation plan
Each step is one commit, and `./gradlew build` and `npm --prefix ui test` pass after each one
(from step 1 on).

1. `chore(ui): scaffold Vite React TypeScript project (AC-1, AC-2, AC-5)`:
   - Add `package.json` (exact versions picked as in "Version selection"), `.npmrc`, `.nvmrc`,
     `.gitignore`, `.env.example`, `tsconfig.json`, `vite.config.ts`, `index.html`,
     `src/vite-env.d.ts`, a minimal `main.tsx`/`App.tsx` and `src/viteConfig.test.ts`.
   - Run `npm --prefix ui install --before=2026-11-02` (needs human approval) and commit
     `package-lock.json`.
   - Record the versions and dates in `status.md`.
2. `test(ui): offline test setup with fetch/XHR network guard (AC-4)`: `test/setup.ts`,
   `test/fetchMock.ts`, `test/networkGuard.test.ts`.
3. `feat(ui): hand-written API types and OpenAPI contract drift test (AC-37)`: `api/contract.ts`,
   `api/types.ts` (with `ContractChecks`), `api/contract.test.ts`.
4. `feat(ui): API base URL, client id and units storage (AC-3, AC-6, AC-8, AC-11)`:
   `config.ts`, `storage/*.ts` and their tests.
5. `feat(ui): API client and error mapping (AC-7, AC-18, AC-19, AC-20)`: `api/client.ts`,
   `api/messages.ts`, `api/errors.ts` and their tests, plus `test/fixtures.ts`.
6. `feat(ui): formatting and input validation (AC-9, AC-10, AC-12, AC-22)`: `format/format.ts`,
   `validation.ts` and their tests.
7. `feat(ui): app shell with hash routing, units control, footer and palette (AC-32–AC-35)`:
   - add `useHashRoute`, `useRequestSlot`, `AppContext`, `Nav`, `Footer`, `Loading`,
     `ErrorMessage`, `styles.css`, `theme/*`, `App.test.tsx`, `theme/contrast.test.ts` and
     `theme/focus.test.ts`;
   - the views are placeholders with an `<h1>`.
8. `feat(ui): search view with current weather and forecast (AC-9–AC-17, AC-23)`.
9. `feat(ui): favourites management view (AC-21–AC-27)`.
10. `feat(ui): favourites dashboard (AC-28–AC-31)`.
11. `docs(ui): README with setup, CORS backend run and smoke checklist (AC-38)`. The README
    covers:
    - Node `>=22.12.0` (`.nvmrc`);
    - the 5 commands;
    - `VITE_API_BASE_URL`;
    - the host bash example, plus PowerShell
      `$env:WEATHER_API_CORS_ALLOWED_ORIGINS='http://localhost:5173'; $env:WEATHER_PROVIDER='stub'; ./gradlew bootRun`;
    - the compose example (`export …` then `docker compose --profile app up -d --build --wait`);
    - the note that `.env` values are also exported into `./gradlew integrationTest` (so keep
      them out of `.env`);
    - the `127.0.0.1` note;
    - `npm run preview` on 5173 for the reload check;
    - the checklist from AC-38.
12. Final verification, with no code change: the full command set from "Commands for the
    pipeline", the bundle-size reading, and a status.md line with the results.

Starting palette for step 7. It is a proposal: the contrast test is authoritative, so adjust
the values if a pair fails.

| Token | Value |
|-------|-------|
| `--color-bg` | `#ffffff` |
| `--color-surface` | `#f4f6f8` |
| `--color-text` | `#1b1f24` |
| `--color-text-muted` | `#4a5563` |
| `--color-link` | `#0a58a8` |
| `--color-primary` | `#0a58a8` |
| `--color-on-primary` | `#ffffff` |
| `--color-error` | `#a4231b` |
| `--color-error-bg` | `#fdeceb` |
| `--color-disabled-text` | `#5c6672` |
| `--color-disabled-bg` | `#e9ecef` |
| `--color-border` | `#6b7480` |
| `--color-focus` | `#0a58a8` |

Pairs to cover:
- text on bg and on surface;
- muted text on bg and on surface;
- link on bg;
- on-primary on primary;
- error on bg and on error-bg;
- disabled-text on disabled-bg;
- border on bg (ui);
- focus on bg and on surface (ui).

## Risks / open questions
- **R-1 (AC-17 ambiguity)**: the Search button is disabled while requests are in flight. HTML
  implicit submission (Enter) does nothing when the form's default button is disabled. So "search
  Paris, then Tokyo before Paris responds" can't be done by typing and pressing Enter. The
  sequence guard handles every re-issue path (submit, units change, days change, Retry). The
  AC-17 race test submits Tokyo by firing a `submit` event on the search form (role `form`,
  name "City search"). A second race test changes units mid-flight, which is the race a user
  can actually trigger. Human decision: keep the button disabled (current design), or leave it
  enabled and rely on the guard.
- **R-2 (AC-12 wording)**: dates use the browser locale. In `en-US` (jsdom's
  `navigator.language`) `2026-11-15` shows as "Sun, Nov 15", not "15 Nov". The test asserts the
  day and month, not their order. The formatters take `navigator.language` explicitly, so tests
  don't depend on the host OS locale.
- **R-3 (AC-38 smoke run)**: the tester agent has no browser. It can start the backend and the dev
  server, run the build and preview, and check the startup and port behaviour. But the visual and
  console items need a person: the 320 px layout, CORS console errors, and clicking through the
  flows. Proposal: the tester records each item as PASS, or as "NEEDS HUMAN" with exact steps, and
  pr-body.md asks the human reviewer to finish the checklist before merging. Human decision.
- **R-4 (`@types/node`)**: the design avoids Node APIs in tests and config. The spec is a JSON
  module import, `TZ` is set through `test.env` and `skipLibCheck` is on. If
  `npm run typecheck` still needs Node types (e.g. Vite/Vitest declarations that
  `skipLibCheck` doesn't silence), the developer must not add `@types/node` silently. Ask the
  human, because it's outside the requirements' dependency list. The fallback without the
  dependency is to drop `vite.config.ts` from `tsconfig.include` (Vite still transpiles it). The
  `viteConfig.test.ts` runtime test then guards it.
- **R-5 (versions)**: the baseline major lines reflect what is known to exist. The exact newest
  versions as of 2026-11-02 must be found with `npm view` (registry access, human approval). If a
  newer major is the only maintained line, that is a new ADR 0017 amendment, not a silent change.
- **R-6 (permissions)**: `.claude/settings.json` doesn't allow `npm` commands, so every `npm`
  call prompts the human. Allowing `npm --prefix ui ci|test|run *` (with `install` kept on
  "ask"), and adding the UI rows to `CLAUDE.md`'s command tables and `/sdlc` step 5, are
  follow-up `chore/` changes for a human. AC-1 forbids changing them in this PR. Until then this
  design.md, ADR 0018 §8 and the overview carry the mapping.
- **R-7 (AC-18 reading)**: "a `code` the UI does not know" is read as generic on 5xx, and as
  the Problem `detail` on 4xx ("unknown 4xx shows detail"). `METHOD_NOT_ALLOWED` is mapped to the
  generic text because it can only come from a UI bug.
- **R-8 (accessible names)**: "Move London down" (from AC-26) has the visible text "Move down"
  with the name inserted, visually hidden. This is a slight mismatch with WCAG 2.5.3
  (label-in-name), because the visible text isn't a contiguous substring. It is accepted as
  specified.
- **R-9 (spec coupling)**: an additive optional property on a used schema also fails the UI drift
  test (exact property-set equality, ADR 0018 Consequences). Backend changes to
  `openapi-v1.json` must run `npm --prefix ui test`.
- **R-10 (CORS vs down)**: the browser can't distinguish a CORS rejection from an unreachable
  backend. Both show the AC-19 text, and the README points to the console.
