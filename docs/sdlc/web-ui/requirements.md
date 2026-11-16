# Web UI (React + Vite + TypeScript SPA): Requirements
Feature slug: web-ui · Status: draft

## Problem / goal
The backend exposes a complete `/api/v1` contract (current weather, forecast, favourites CRUD,
reorder, and the `/favourites/weather` and `/favourites/forecast` aggregates), but there is no
user interface. This feature starts the UI: a React + Vite + TypeScript single-page app in a new
top-level `ui/` directory. It is built and hosted separately from Spring Boot (the backend serves
no static content, ADR 0003 §6), runs on the Vite dev server at `http://localhost:5173`, produces
a static build, and calls the backend over CORS. The stack and the separate hosting were chosen
by the human. The Gradle build and backend stay untouched.

The first version must be shippable in one PR:
- city search with current weather and forecast;
- favourites management;
- a favourites dashboard;
- correct error and loading states;
- the Open-Meteo attribution that the API cannot carry (ADR 0005);
- basic accessibility and phone-width layout.

## User stories
- As a visitor, I want to search for a city and see its current weather and a daily forecast in
  metric or imperial units, so that I can check the weather quickly.
- As a visitor, I want to save cities as favourites with an optional label, rename, reorder and
  delete them, so that I can keep the places I care about.
- As a visitor, I want a dashboard of my favourites with their current weather and a short
  forecast, so that I can see all my places at once.
- As a visitor, I want clear messages when a city is not found, my input is invalid or the
  weather service is down, so that I know what to do next.
- As a keyboard or screen-reader user, or a phone user, I want the app to be operable and
  readable, so that I am not excluded.
- As a developer, I want to run the UI against a local backend with one documented command and
  have tests that catch drift between UI types and `openapi-v1.json`, so that the UI and the API
  stay in sync.

## Acceptance criteria

### Project, build and configuration
- **AC-1**: Given a clean checkout, when `npm ci` and then `npm run build` run in `ui/`, then both
  exit 0 and `ui/dist/` contains `index.html` plus hashed JS/CSS assets that can be served by any
  static file server. No file outside `ui/` and `docs/` is added or modified by this feature;
  specifically:
  - `build.gradle.kts`, `settings.gradle.kts`, `src/**`, `compose.yaml`, `.env.example` and
    `application.yml` are unchanged;
  - `./gradlew build` still passes.

  Changes under `docs/` are limited to `docs/sdlc/web-ui/`, `docs/architecture/overview.md` and
  any new ADRs.
- **AC-2**: Given `ui/` with dependencies installed, when `npm run dev` runs, then the app is
  served at `http://localhost:5173`. The port is fixed: if 5173 is taken, the command fails
  instead of silently choosing another port, because CORS is configured for that exact origin.
- **AC-3**: Given the Vite env var `VITE_API_BASE_URL` is unset, when the UI calls the API, then
  requests go to `http://localhost:8080/api/v1/...`.

  Given `VITE_API_BASE_URL=https://api.example.test` (with or without a trailing `/`), requests go
  to `https://api.example.test/api/v1/...` with no double slash. A committed `ui/.env.example`
  documents the variable.
- **AC-4**: Given `ui/`, when `npm test` runs with no backend running and no network, then all
  unit/component tests run and pass. HTTP is mocked at the `fetch` boundary, and any unmocked
  `fetch` call fails the test. `npm run typecheck` (TypeScript `strict`, no emit) exits 0.
- **AC-5**: Given `npm ci` and `npm run build` have run, when `git status` runs, then
  `ui/node_modules/` and `ui/dist/` do not appear as untracked files, because an ignore file inside
  `ui/` covers them. `ui/package-lock.json` is committed.

### Client id
- **AC-6**: Given empty `localStorage`, when the app loads, then it generates a client id that
  matches `^[A-Za-z0-9._-]{1,64}$` (a random UUID) and stores it under the key `weather.clientId`.
  After a reload, the same value is reused.
- **AC-7**: Given a stored client id, when the UI calls any `/api/v1/favourites*` endpoint, then
  the request carries `X-Client-Id: <stored id>`. Requests to `/api/v1/weather/*` do not send the
  header.
- **AC-8**: Given `localStorage` holds an invalid value under `weather.clientId` (e.g.
  `bad id!` or a 65-character string), when the app loads, then it replaces the value with a newly
  generated valid id.

  Given `localStorage` throws on access, the app uses an in-memory id for the session and still
  works. It also shows a non-blocking notice that favourites will not be kept after the tab
  closes.

### Search: current weather and forecast
- **AC-9**: Given the Search view, when the user types `London` in the input labelled "City" and
  presses Enter or activates the Search button, then the UI sends:
  - `GET /api/v1/weather/current?city=London&units=metric`;
  - `GET /api/v1/weather/forecast?city=London&units=metric&days=5`.

  On success it shows:
  - the location name and country code (`London, GB`);
  - temperature and feels-like, rounded to whole degrees with the unit symbol (`11.2` → `11 °C`);
  - humidity in %;
  - wind speed to one decimal place with its unit (`4.1 m/s`) and wind direction in degrees;
  - the condition description;
  - the observation time in the browser's local time.

  It also shows a forecast list with one row per returned day: date, min/max temperature, rain
  probability in %, and condition description.
- **AC-10**: Given the city input, when the user submits an empty or whitespace-only value, then
  no request is sent and an inline error "Enter a city name." is shown. The error is linked to
  the input with `aria-describedby`.

  When the trimmed value is longer than 100 characters, no request is sent and an inline error
  "City name must be at most 100 characters." is shown. Otherwise the trimmed value is sent and
  URL-encoded (e.g. `São Paulo` → `city=S%C3%A3o%20Paulo`).
- **AC-11**: Given a result is shown in metric units, when the user switches the units control to
  imperial, then both requests are re-sent with `units=imperial` and the values display with
  `°F` and `mph`. The choice is stored in `localStorage` (`weather.units`) and survives a reload.
  It also applies to the dashboard (AC-24, AC-25). The default is `metric`.
- **AC-12**: Given a forecast for `London` is shown, when the user changes the labelled "Days"
  control (options 1 to 7, default 5) to 3, then the UI sends
  `GET /api/v1/weather/forecast?city=London&units=metric&days=3` and the list shows exactly 3
  rows. Forecast dates are shown from the `YYYY-MM-DD` value without a timezone shift: with the
  test timezone set to `America/Los_Angeles`, `2026-11-15` is shown as 15 Nov, not 14 Nov.
- **AC-13**: Given the API answers `404` with `code: LOCATION_NOT_FOUND`, when the search
  completes, then the UI shows "No weather data found for "<city>"." and no previous result
  stays on screen.
- **AC-14**: Given the API answers `503` with `code: UPSTREAM_UNAVAILABLE`, when the search
  completes, then the UI shows "The weather service is temporarily unavailable. Please try again."
  with a Retry button. Activating Retry sends the same requests again.
- **AC-15**: Given the API answers `400` with `code: VALIDATION_FAILED` and
  `errors: [{field: "city", message: "must not be blank"}]`, when the search completes, then the
  message is shown inline on the City input. Field errors whose field has no matching control
  (e.g. `units`, `days`, `X-Client-Id`, `body`) are listed in a general error message.
- **AC-16**: Given the current-weather request succeeds and the forecast request fails (any
  error), when both complete, then the current weather is shown and the forecast area shows its
  own error message with a Retry action for the forecast only.
- **AC-17**: Given a search is in flight, then a loading indicator with `role="status"` is visible
  and the Search button is disabled. Both return to normal when the requests settle.

  Given the user searches `Paris` and then `Tokyo` before `Paris` responds, when `Paris` responds
  last, then only the `Tokyo` result is displayed.

### Generic error mapping (all views)
- **AC-18**: Given any API call that fails with:
  - a `5xx` status,
  - `code: INTERNAL_ERROR`,
  - a response whose body is not Problem Details JSON (e.g. `415` or a proxy HTML page), or
  - a `code` the UI does not know,

  when the error is shown, then the UI shows "Something went wrong. Please try again." For
  unknown 4xx it shows the Problem `detail` if present. The message includes
  "Reference: <requestId>", taken from the body's `requestId` or else the `X-Request-Id` response
  header, when either is present. No stack trace or raw body is rendered.
- **AC-19**: Given `fetch` rejects (backend down, or the browser blocks the response because of
  CORS), when the error is shown, then the UI shows "Can't reach the weather service at <base
  URL>." with a Retry action.
- **AC-20**: Given the Problem `code` enum in `src/main/resources/openapi/openapi-v1.json`, when
  `npm test` runs, then a test asserts that the UI has a user-facing message for every enum value:
  - `VALIDATION_FAILED`, `LOCATION_NOT_FOUND`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`,
    `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`, `FAVOURITE_ALREADY_EXISTS`,
    `FAVOURITES_LIMIT_REACHED`;
  - every value of the `FavouriteWeatherError.code` enum.

  The test fails if a code is added to the spec without a UI mapping.

### Favourites management
- **AC-21**: Given the Favourites view, when it opens, then the UI sends
  `GET /api/v1/favourites` with `X-Client-Id` and lists the items in response order. Each item
  shows its label (if set), its `city`, and `location.name, location.country`.

  Given `items: []`, the view shows "No favourites yet." and the add form.
- **AC-22**: Given the add form (labelled "City" and "Label (optional)"), when the user submits
  `city=London`, `label=Home`, then the UI sends `POST /api/v1/favourites` with
  `Content-Type: application/json` and body `{"city":"London","label":"Home"}`. On `201`, the
  returned favourite appears at the end of the list and the form is cleared.

  An empty or whitespace-only label is sent as no label: the property is omitted or `null`.
  Client-side validation applies before sending:
  - city: the AC-10 rules;
  - label: at most 50 characters after trimming, else the inline error "Label must be at most 50
    characters.".
- **AC-23**: Given a search result for `London` is shown, when the user activates
  "Add to favourites", then the UI sends `POST /api/v1/favourites` with body `{"city":"London"}`.
  On `201` the control changes to a non-actionable "Saved" state.
- **AC-24** (create errors): Given `POST /api/v1/favourites` answers with one of these errors,
  when it completes, then the list is unchanged and the form keeps its input:

  | Response | Message |
  |----------|---------|
  | `409 FAVOURITE_ALREADY_EXISTS` | "<city> is already in your favourites." |
  | `409 FAVOURITES_LIMIT_REACHED` | "You've reached the maximum number of favourites. Remove one to add another." |
  | `404 LOCATION_NOT_FOUND` | The AC-13 text |
  | `503 UPSTREAM_UNAVAILABLE` | The AC-14 text |
  | `400 VALIDATION_FAILED` | Errors on `city`/`label` shown on those inputs, others shown as in AC-15 |
- **AC-25** (rename): Given a favourite, when the user activates "Rename", enters `Work` and saves,
  then the UI sends `PATCH /api/v1/favourites/{id}` with body `{"label":"Work"}` and, on `200`,
  shows the returned label.

  Saving an empty label sends `{"label":null}` and the label disappears. Pressing Escape cancels
  the edit without a request. If the response is `404 NOT_FOUND`, the UI shows "This favourite no
  longer exists." and reloads the list (`GET /api/v1/favourites`).
- **AC-26** (reorder): Given favourites `[A, B, C]`, when the user activates "Move down" on `A`,
  then the UI sends `PUT /api/v1/favourites/order` with body `{"ids":[B,A,C]}` and, on `200`,
  shows the order returned by the server.

  The controls are buttons, so they are keyboard-operable and not drag-only:
  - "Move up" is disabled on the first item;
  - "Move down" is disabled on the last item;
  - each button's accessible name includes the favourite's label or city (e.g. "Move London
    down").

  Given the response is `400 VALIDATION_FAILED` on field `ids`, the UI shows "Your favourites
  changed elsewhere. The list has been refreshed." and reloads the list.
- **AC-27** (delete): Given a favourite, when the user activates "Delete" and confirms, then the UI
  sends `DELETE /api/v1/favourites/{id}` and, on `204`, removes the item. Cancelling the
  confirmation sends no request.

  If the response is `404 NOT_FOUND`, the item is removed and the list is reloaded, with no error
  shown, because the user's intent is already satisfied. The confirmation must be keyboard-operable
  and focus must return to a sensible element afterwards (the next item's Delete control, or the
  add form if the list is empty).

### Dashboard
- **AC-28**: Given the Dashboard view and units `metric`, when it opens, then the UI sends
  exactly two API requests, in parallel, and no per-favourite `/weather/*` requests:
  - `GET /api/v1/favourites/weather?units=metric`;
  - `GET /api/v1/favourites/forecast?units=metric&days=3`.

  It renders one card per item, in the order of the `/favourites/weather` response. Each card has
  a heading with the label, or the city if there is no label, and shows `location.name,
  location.country`. With `weather`, the card shows temperature, condition description, humidity
  and wind (formatted as in AC-9). With `forecast`, it shows 3 compact rows: date, min/max and
  condition.
- **AC-29**: Given an item with `error` in either aggregate response, when the dashboard renders,
  then only that card's section shows the per-item message. The other cards and that card's other
  section render normally:

  | `error.code` | Message |
  |--------------|---------|
  | `UPSTREAM_UNAVAILABLE` | "Weather temporarily unavailable." |
  | `LOCATION_NOT_FOUND` | "No weather data for this location." |
  | Any unknown code (e.g. `SOMETHING_NEW`) | "No data available." |

  This holds even when every item has an error and the HTTP status is `200`. Weather and forecast
  items are matched by `id`. A forecast item whose `id` is not in the weather response is ignored.
- **AC-30**: Given `/api/v1/favourites/weather` returns `items: []`, when the dashboard renders,
  then it shows "No favourites yet." with a link to the Favourites view.

  Given either aggregate request fails as a whole (non-2xx or network), that section uses the
  AC-18/AC-19 messages. A dashboard-level Retry re-sends the failed request(s). If only the
  forecast request failed, cards still show current weather.
- **AC-31**: Given the dashboard is shown, when the user activates "Refresh" or changes units, then
  both aggregate requests are re-sent with the current units. While they are in flight, a
  `role="status"` loading indicator is visible. The UI never polls automatically.

### Navigation, attribution, accessibility, responsiveness
- **AC-32**: Given the app, then a `<nav>` landmark offers Search, Favourites and Dashboard. Each
  view has a distinct URL fragment or path. Reloading the browser on any view shows the same view
  when the build is served by a plain static file server with no rewrite rules (e.g.
  `npx vite preview` or any static host). The default view is Search.
- **AC-33**: Given any view, then a footer is visible containing "Weather data by Open-Meteo.com"
  linked to `https://open-meteo.com/` and the licence "CC BY 4.0" linked to
  `https://creativecommons.org/licenses/by/4.0/`. A component test asserts both links (role
  `link`, `href`) on each of the three views.
- **AC-34**: Given every form control, then it has a programmatically associated label (findable
  with `getByLabelText`). Every interactive element is a native button, link or form control,
  reachable with Tab and operable with Enter/Space. Focus is visible (no `outline: none` without a
  replacement). Error messages are announced (`role="alert"` or an `aria-live` region). Component
  tests locate controls only by role or label, never by CSS selector or test id.
- **AC-35**: Given the UI's colour palette (text/background pairs, including error and disabled
  text), when `npm test` runs, then a test computes the WCAG contrast ratio of every declared
  pair. It fails if normal text is below 4.5:1 or large text and UI component boundaries are below
  3:1.
- **AC-36**: Given a viewport 320 px wide, when each view is shown with 5 favourites and a 7-day
  forecast, then the page has no horizontal scrolling and all controls stay usable. This is
  checked manually (see AC-38) because jsdom has no layout.

### API typing and contract drift
- **AC-37**: Given the UI's TypeScript types for API requests and responses, when `npm test` runs,
  then a test fails if they drift from `src/main/resources/openapi/openapi-v1.json`. The test
  reads the spec from that path; there is no copied spec in `ui/`.

  At minimum, renaming or removing a required property, or adding or removing an enum value, in a
  schema the UI uses makes the test fail. This applies to:
  - `CurrentWeather`, `Forecast`, `ForecastDay`, `Location`, `Condition`, `Favourite`;
  - `FavouriteList`, `FavouriteWeather(List)`, `FavouriteForecast(List)`,
    `FavouriteWeatherError`;
  - `CreateFavouriteRequest`, `UpdateFavouriteRequest`, `ReorderFavouritesRequest`;
  - `Problem`, `FieldError`.

  If types are generated, the test regenerates them and diffs against the committed output. The
  architect chooses generated or hand-written types.

### Developer documentation and smoke check
- **AC-38**: Given `ui/README.md`, then it documents:
  - the required Node.js version, which is also pinned in `package.json` `engines`;
  - `npm ci`, `npm run dev`, `npm run build`, `npm test`, `npm run typecheck`;
  - `VITE_API_BASE_URL`;
  - starting the backend for the UI with CORS, without changing backend defaults. Host example:
    `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub ./gradlew bootRun`
    after `scripts/test-env.sh up`. Compose example: the same variables exported in the shell
    before `docker compose --profile app up -d --build --wait`. Include a PowerShell equivalent.
    Add a note that values put in `.env` are also exported into `./gradlew integrationTest`.
  - a manual end-to-end smoke checklist.

  The checklist covers, against the stub backend:
  - search `London`, switch units and days;
  - search an unknown city to get the not-found message;
  - add `London` labelled `Home`, then add `Paris`;
  - add `London` again to get the duplicate message;
  - rename, reorder and delete;
  - check the dashboard shows both cards;
  - check the browser console has no CORS errors;
  - check the 320 px layout (AC-36);
  - stop the backend and check the AC-19 message.

  The tester runs this checklist once and records the results in
  `docs/sdlc/web-ui/test-report.md`.

## Non-functional requirements
- **Dependencies** (npm installs need human approval):
  - Runtime dependencies are only `react` and `react-dom`.
  - Dev dependencies are limited to: the Vite toolchain (`vite`, `@vitejs/plugin-react`),
    `typescript`, `@types/react*`, a test runner and DOM environment (e.g. `vitest`, `jsdom`),
    Testing Library (`@testing-library/react`, `/user-event`, `/jest-dom`), and at most one
    OpenAPI type generator if the architect chooses generation.
  - Every package is listed with a one-line justification in design.md.
  - No UI component library, CSS framework, state-management library, router library, HTTP
    client library or date library.
- **Bundle size**: the `npm run build` output has at most 150 KB of gzipped JavaScript in total,
  as reported by Vite's build summary.
- **Request budget**: a search makes exactly 2 API requests, and opening or refreshing the
  dashboard makes exactly 2. Changing only the days makes 1 request (forecast only). No polling,
  and no request on every keystroke.
- **Security**:
  - `fetch` uses `credentials: "omit"` and the UI sends no cookies.
  - API strings (city, label, descriptions, Problem `detail`) are rendered only as text, never as
    HTML (`dangerouslySetInnerHTML` is not used).
  - No secrets in `ui/`, and `VITE_*` values are treated as public.
  - The UI never calls the client id an account or login (ADR 0009: it is not authentication).
- **Observability**:
  - The UI does not send `X-Request-Id`; the server generates it.
  - It surfaces `requestId` / `X-Request-Id` in error messages (AC-18) so users can quote it.
  - No client-side telemetry.
- **Browser support**: current evergreen Chrome, Firefox, Safari and Edge (Vite's default build
  target). No IE or legacy polyfills.
- **Data retention**: the browser stores only `weather.clientId` and `weather.units` in
  `localStorage`. No API responses are persisted.

## Out of scope
- Serving the UI from Spring Boot, adding the UI to `compose.yaml` (`app` profile or a new
  service), Dockerising the UI, or any Gradle integration (e.g. a frontend Gradle plugin).
- Any backend change, including changing the default `WEATHER_API_CORS_ALLOWED_ORIGINS`, editing
  `.env.example`, or adding API endpoints or fields.
- An automated browser end-to-end suite (e.g. Playwright/Cypress). It needs extra dependencies and
  browser downloads, so v1 uses the documented manual smoke check (AC-38) instead. It is a
  candidate follow-up.
- CI workflow for the UI (the repo has none today).
- Automated accessibility scanning (axe) and visual regression tests.
- ESLint/Prettier setup. v1 relies on TypeScript `strict` and `npm run typecheck`.
- Showing, copying or importing the client id to move favourites between browsers or devices.
- City autocomplete or suggestions, geolocation ("my location"), maps, weather icons/illustrations,
  dark mode, i18n/localisation (UI text is English), PWA/offline support, auto-refresh.
- Drag-and-drop reordering (buttons only in v1).
- Showing `GET /api/v1/favourites/{id}` as a detail view, and a full forecast per favourite beyond
  the 3 compact dashboard days.

## Open questions / assumptions
Each one is a decision for the human reviewer to confirm on the PR.
- **A-1 Views in v1**: three views: Search (current + forecast), Favourites (manage: add, rename,
  reorder, delete), Dashboard (aggregate current weather + 3-day forecast). Default view is
  Search.
- **A-2 Routing**: no router dependency. The view must survive a reload on a plain static host
  without rewrite rules (AC-32), which in practice means hash-based URLs (`#/favourites`). The
  architect may choose otherwise only if AC-32 still holds.
- **A-3 State management**: React built-ins only (state, context, reducer). No global store or
  data-fetching library.
- **A-4 Styling**: hand-written CSS (plain CSS files or CSS modules, as built into Vite). No
  framework. Mobile-first layout down to 320 px.
- **A-5 Dashboard forecast**: included because it costs one extra aggregate request. It uses a
  fixed `days=3` for compact cards and does not follow the Search view's days control. Units are
  shared across views.
- **A-6 Adding favourites**: from the Favourites view form (city + optional label) and through an
  "Add to favourites" shortcut on a search result (no label). The shortcut sends the city as typed
  (trimmed), not the canonical `location.name`. The API stores and deduplicates on the typed city.
- **A-7 Delete confirmation**: required, implemented accessibly (native `<dialog>` or an inline
  confirm step, architect's choice). No undo.
- **A-8 Optimistic updates**: none. The list changes only after a successful response. Reorder
  shows the server-returned order.
- **A-9 CORS**: backend defaults stay deny-all and nothing in the backend changes. The dev origin
  is set through `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173` in the process
  environment (AC-38).

  Putting it in `.env` also works for compose, but `build.gradle.kts` exports `.env` into
  `integrationTest`. That is why `.env.example` keeps it commented, and the README must say so.
  Note that `http://127.0.0.1:5173` is a different origin and is not covered.
- **A-10 Attribution**: always shown, even when the backend runs with `WEATHER_PROVIDER=stub`. The
  UI cannot detect the provider, and the API has no field for it (ADR 0005). Whether the free
  Open-Meteo tier is acceptable for this use (non-commercial) stays an open business question
  (ADR 0005 A-2).
- **A-11 E2E**: an automated end-to-end test against the real backend is out of scope (see Out of
  scope). The smoke check is manual, documented in `ui/README.md`, and run by the tester against
  `./gradlew bootRun` with `WEATHER_PROVIDER=stub` (or the compose `app` profile). No test code
  starts or stops containers, and Testcontainers is not used (ADR 0001).
- **A-12 Node.js**: the current Node.js LTS (22 or newer) and npm, pinned in `engines` and an
  `.nvmrc`. The installed Node version on the developer machine is not verified by this document.
  The tester must record it.
- **A-13 Drift test location**: the UI test reads `../src/main/resources/openapi/openapi-v1.json`
  directly, so `ui/` is not standalone outside the monorepo. This is accepted because the UI and
  the API live in one repo.
- **A-14 Ignore rules**: the root `.gitignore` pattern `.env.*` already ignores `ui/.env.local`
  and similar files, and `!.env.example` keeps `ui/.env.example` tracked. `node_modules/` and
  `dist/` are ignored through a new `ui/.gitignore` (inside `ui/`), not by editing the root file.
- **A-15 Display formatting**:
  - temperatures are rounded to whole degrees;
  - wind is shown to 1 decimal place;
  - dates are short weekday + day + month in the browser locale;
  - `observedAt` is shown in local time.

  No weather icons, only condition description text. An unknown `condition.code` still shows its
  description.
- **A-16 Client id on weather endpoints**: not sent (AC-7), to keep the weather requests free of
  custom headers. It is harmless if the architect prefers one header policy for all requests;
  if so, change AC-7.
