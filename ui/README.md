# Weather UI

A React + Vite + TypeScript single-page app for the `/api/v1` weather backend. It is built and
hosted separately from Spring Boot and calls the API over CORS (ADR 0017). Tests run offline
with Vitest, jsdom and Testing Library (ADR 0018).

## Requirements
- Node.js `>=22.12.0` with npm `>=10` (pinned in `package.json` `engines` and enforced by
  `engine-strict=true` in `.npmrc`; `.nvmrc` says `22`).
- The backend running locally (see below) for `npm run dev`. Tests and the build don't need it.

## Commands
Run these in `ui/` (or from the repo root with `npm --prefix ui ...`):

| Command | What it does |
|---------|--------------|
| `npm ci` | Installs the exact versions in `package-lock.json`. |
| `npm run dev` | Dev server on `http://localhost:5173`. The port is fixed: if 5173 is taken, the command fails. |
| `npm run build` | Typecheck, then a static build in `dist/` (relative asset paths, any static server can host it). |
| `npm run preview` | Serves `dist/` on `http://localhost:5173` (same fixed port). |
| `npm test` | Typecheck, then all unit/component tests (no backend, no network). |
| `npm run typecheck` | TypeScript `strict`, no emit. |

`npm test` also reads `../src/main/resources/openapi/openapi-v1.json` and fails if the UI's
API types drift from it, so run it whenever that file changes.

## Backend address: `VITE_API_BASE_URL`
The UI calls `${VITE_API_BASE_URL}/api/v1/...`. The default is `http://localhost:8080`, and a
trailing `/` is allowed. To change it, copy `.env.example` to `.env.local` (ignored by git) or set
the variable when you build:

```bash
VITE_API_BASE_URL=https://api.example.test npm run build
```

`VITE_*` values are inlined into the bundle and are public, so never put secrets in them.

## Running the backend for the UI (CORS)
The backend denies cross-origin requests by default. Allow the UI origin for the backend
process only; don't change the backend defaults.

Host (bash), after `scripts/test-env.sh up` from the repo root:

```bash
WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub ./gradlew bootRun
```

Host (PowerShell):

```powershell
$env:WEATHER_API_CORS_ALLOWED_ORIGINS='http://localhost:5173'; $env:WEATHER_PROVIDER='stub'; ./gradlew bootRun
```

Docker Compose (the `app` profile), with the variables exported in the shell first:

```bash
export WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173 WEATHER_PROVIDER=stub
docker compose --profile app up -d --build --wait
```

```powershell
$env:WEATHER_API_CORS_ALLOWED_ORIGINS='http://localhost:5173'; $env:WEATHER_PROVIDER='stub'
docker compose --profile app up -d --build --wait
```

Notes:
- Keep these values out of the repo's `.env`. Values in `.env` are also exported into
  `./gradlew integrationTest`, which would change what the integration tests run against.
- `http://127.0.0.1:5173` is a different origin from `http://localhost:5173` and is not allowed
  by the setting above. Open the UI at `http://localhost:5173`.
- The browser reports a CORS rejection the same way as an unreachable backend. If the UI says
  "Can't reach the weather service at ...", check the browser console for a CORS error.
- `WEATHER_PROVIDER=stub` gives deterministic data. Without it the backend calls Open-Meteo.

## Manual smoke checklist
Run against the stub backend above, with `npm run dev` (or `npm run build` and
`npm run preview`). Record the results in the feature's `test-report.md`.

1. Search `London`: current weather and a 5-day forecast are shown.
2. Switch units to imperial: values show `°F` and `mph`. Reload the page: imperial is kept.
3. Change Days to 3: the forecast shows 3 rows.
4. Search an unknown city: the not-found message is shown and no old result stays.
5. On Favourites, add `London` with the label `Home`, then add `Paris`.
6. Add `London` again: "London is already in your favourites." is shown.
7. Rename `Paris` to `Work`, move it up, then delete one favourite (confirm the prompt).
8. Re-add the deleted favourite so both cities are saved. Open Dashboard: both cards are
   shown with current weather and 3 forecast rows.
9. Reload on `#/favourites` and `#/dashboard` (also with `npm run preview`): the same view is
   shown.
10. Check the browser console: no CORS errors.
11. In the browser devtools, set the viewport to 320 px wide, with 5 favourites and a 7-day
    forecast: no horizontal scrolling, and all controls are usable.
12. Stop the backend and search again: "Can't reach the weather service at
    http://localhost:8080." is shown with a Retry button.
