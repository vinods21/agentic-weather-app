# 0017. Web UI: React + Vite + TypeScript SPA in `ui/`, built and hosted separately from the backend
Date: 2026-11-16 · Status: Accepted · Feature: web-ui

## Context
The backend has a complete `/api/v1` contract (ADR 0002, 0003, 0009, 0010, 0014, 0016) but no user
interface. The human chose the stack (React + Vite + TypeScript) and the hosting model (a separate
static build that calls the API over CORS). ADR 0003 §6 says the backend serves no static content.
This is the first JavaScript code in a Gradle/Java repo. The requirements (web-ui) also restrict
the change to `ui/` and `docs/`, allow only `react` and `react-dom` at runtime, need human approval
for every npm install, and forbid router, state, HTTP-client, CSS and date libraries. These choices
shape every later UI feature and are hard to reverse once code depends on them, so they are
recorded once.

## Decision
1. **Location and isolation.** The UI is a self-contained npm project in the top-level `ui/`
   directory. Gradle, `src/**`, `compose.yaml`, `.env.example` and `application.yml` don't know
   it exists: no Gradle plugin, no Spring static resources, no compose service, no Dockerfile.
   `ui/` is not standalone outside the monorepo, because its contract test reads
   `../src/main/resources/openapi/openapi-v1.json` (ADR 0018).
2. **Stack.** React 19 function components and hooks, TypeScript in `strict` mode, Vite 7 with
   `@vitejs/plugin-react`. Runtime dependencies are exactly `react` and `react-dom`. Everything
   else is built in:
   - routing: hash-based URLs (`#/search`, `#/favourites`, `#/dashboard`) and a small
     `hashchange` hook. A plain static server with no rewrite rules can serve any view after a
     reload;
   - state: `useState`/`useReducer`/context. There is no global store and no data-fetching
     library;
   - HTTP: the browser `fetch` behind one hand-written API client module;
   - styling: one hand-written CSS file with custom properties (colour tokens), mobile first;
   - dates and numbers: `Intl.DateTimeFormat` and plain arithmetic.
   `<StrictMode>` is not used, so the dev server makes the same number of requests as the
   production build (requirements: request budget).
3. **Hosting.** `npm run build` writes a static bundle to `ui/dist/` with `base: './'`, so any
   static file server can host it at any path. The dev server runs on `localhost:5173` with
   `strictPort: true`. `vite preview` uses the same port and also uses `strictPort`, because the
   backend's CORS allow-list names that exact origin. `http://127.0.0.1:5173` is a different
   origin.
4. **Backend address.** The `VITE_API_BASE_URL` build-time variable sets the backend address. It
   defaults to `http://localhost:8080`. Trailing slashes are removed, and a value that isn't an
   absolute `http(s)` URL fails at startup. `VITE_*` values are public and end up in the bundle.
   `ui/.env.example` documents the variable. The root `.gitignore` already ignores `ui/.env` and
   `ui/.env.*` except `.env.example`.
5. **CORS stays deny-by-default (ADR 0003 §5, 0010).** Nothing in the backend changes. Developers
   allow the UI origin per process with
   `WEATHER_API_CORS_ALLOWED_ORIGINS=http://localhost:5173` (`ui/README.md`). `fetch` uses
   `credentials: "omit"`. The UI sends no cookies and no `X-Request-Id`.
6. **Client id (ADR 0009).** The UI generates the opaque `X-Client-Id` once per browser:
   `crypto.randomUUID()`, with a `crypto.getRandomValues` fallback for non-secure contexts. It
   stores the id in `localStorage` under `weather.clientId` and sends it only to
   `/api/v1/favourites*`. If storage is unavailable, an in-memory id is used and the UI shows a
   notice. The UI never calls the id an account or a login. `weather.units` is the only other
   stored key, and no API response is persisted.
7. **Dependency policy.** All dependencies have exact versions (no `^`/`~`, `save-exact=true` in
   `ui/.npmrc`), and `ui/package-lock.json` is committed. A version must have been published at
   least 14 days before it is adopted. To enforce this for transitive packages too, packages are
   installed with `npm install --before=<adoption date − 14 days>`. Node.js `>=22.12.0` is pinned
   in `engines`, enforced with `engine-strict=true` and recorded in `ui/.nvmrc`. A new dependency
   in `ui/` needs a design or ADR entry, as it does for Gradle (CLAUDE.md).

## Consequences
- The backend and its build are unaffected. `./gradlew build` and the ITs don't need Node, and
  the UI's checks don't need Java or Docker.
- There are two toolchains on a developer machine (JDK 21 and Node 22+). The SDLC pipeline runs
  the UI commands in addition to the Gradle ones (ADR 0018).
- Hash URLs look less clean than path URLs, but they need no host configuration. Switching to
  path routing later needs rewrite rules on every host.
- A deployment must set `VITE_API_BASE_URL` at build time and add the UI origin to the backend's
  CORS allow-list. A runtime-configurable base URL (e.g. a `config.json`) is a later option.
- With no router or data library, hand-written hooks must handle stale responses (a
  request-sequence guard) and loading/error states. The design keeps that logic in one hook.
- Because there is no `<StrictMode>`, React's dev-only double-invocation checks are lost.

## Alternatives considered
- **Serve the build from Spring Boot** (static resources or a Gradle frontend plugin): the human
  rejected it, it contradicts ADR 0003 §6, and it would couple the two builds.
- **A UI service in `compose.yaml`/Docker**: out of scope (requirements). It may be added later
  without changing this ADR's decisions 1 to 6.
- **History-API routing**: cleaner URLs, but a reload on `/favourites` gives 404 on a plain static
  host without rewrite rules (AC-32).
- **React Router, TanStack Query, Redux/Zustand, axios, a CSS framework, date-fns**: each is one
  more dependency to approve and keep up to date, for an app with three views and about ten
  endpoints. All are excluded by the requirements.
- **A Vite dev-server proxy to `:8080` instead of CORS**: it hides CORS problems in development
  that would then appear in every real deployment. The backend already supports a CORS allow-list.
- **Caret version ranges**: they let `npm install` pick releases that are newer than the
  two-week rule allows. The lock file alone doesn't stop this when the lock is regenerated.
