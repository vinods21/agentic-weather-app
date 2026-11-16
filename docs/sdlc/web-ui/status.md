# web-ui: SDLC status
Request: Start the UI: a React + Vite + TypeScript single-page app in ui/, run separately (Vite dev server on localhost:5173) and calling the /api/v1 backend over CORS. Human chose UI before the backend follow-up fix/ PR.
Branch: feature/web-ui · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [x] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-16 pm: requirements.md written (AC-1..AC-38)
- 2026-11-16 architect: design.md written; ADR 0017 (UI stack/hosting), ADR 0018 (UI testing, hand-written types + spec drift test); overview.md UI section added; open points R-1..R-10 in design.md
- 2026-11-16 human decisions: R-1 Search button stays enabled during a search (AC-17 reworded; latest-wins guard covers races). R-3 agents mark the visual/console/320px smoke-checklist items NEEDS HUMAN in test-report.md; the PR asks the human to complete them. R-6 (npm commands in CLAUDE.md, /sdlc and the settings allow-list) left as a separate human chore.
- 2026-11-16 developer: ui/ implemented per design steps 1-12 (10 commits); `npm --prefix ui test` 146/146 pass (typecheck included), `npm --prefix ui run build` OK (JS 68.60 KB gzip), `./gradlew build` green; no file outside ui/ and docs/sdlc/web-ui/ changed; no @types/node needed (vite.config.ts stays in tsconfig include, R-4 fallback unused)
## Dependency versions
Cutoff 2026-11-02 (design "Version selection"); installed with `npm --prefix ui install --before=2026-11-02`. Newest release in the design's major line published before the cutoff, peer ranges checked.

| Package | Version | Published (UTC) |
|---------|---------|-----------------|
| react | 19.2.9 | 2026-10-14 |
| react-dom | 19.2.9 | 2026-10-14 |
| vite | 7.3.7 | 2026-10-06 |
| @vitejs/plugin-react | 5.2.0 | 2026-03-12 |
| typescript | 5.9.3 | 2025-09-30 |
| @types/react | 19.2.18 | 2026-07-30 |
| @types/react-dom | 19.2.7 | 2026-09-03 |
| vitest | 4.1.12 | 2026-10-15 |
| jsdom | 27.4.0 | 2025-12-26 |
| @testing-library/react | 16.3.3 | 2026-08-27 |
| @testing-library/dom | 10.4.2 | 2026-09-13 |
| @testing-library/user-event | 14.6.8 | 2026-10-13 |
| @testing-library/jest-dom | 6.9.1 | 2025-10-01 |

Peers: vitest 4.1.12 `vite ^6||^7||^8`, plugin-react 5.2.0 `vite ^4.2..^8`, RTL 16.3.3 `react/react-dom/@types ^18||^19`, `@testing-library/dom ^10`. Engines all satisfied by Node >=22.12.
## Deviations from design
- `@testing-library/jest-dom` is 6.9.1, not the newest 6.x before the cutoff (6.10.0, 2026-07-20): npm marks 6.10.0 deprecated ("Incorrect minor release with breaking changes ... Use 6.9.1 for the 6.x line"). This took a second `npm --prefix ui install --before=2026-11-02` run after editing the pin.
- `src/viteConfig.test.ts` runs in the Vitest `node` environment (`// @vitest-environment node` docblock): importing `vite.config.ts` loads esbuild, which refuses to load under jsdom (TextEncoder/Uint8Array realm check).
- `vite.config.ts` adds `test.css: { include: [/src\/styles\.css/] }`: Vitest empties CSS modules by default, so the theme tests' `styles.css?raw` import was an empty string. Only test-time CSS handling changes; the build is unaffected.
- `ApiFailure` `{kind: 'network'}` carries `baseUrl`, so `describeApiError(err, {city?, knownFields?})` can build the AC-19 text without the base URL in its context.
- Small additions not listed in the design's layout: `components/ViewHeading.tsx` (the focusable `<h1>`), `test/spec.ts` (shared spec import for the contract and message tests), `test/fakeStorage.ts`, and a `noContent()` helper in `test/fetchMock.ts`. `CurrentWeatherDetails` has a `compact` mode (no feels-like) for the dashboard cards.
- Favourites: after a successful reorder, focus goes back to the moved item's Move button (or its other Move button if that one is now disabled), because the buttons are disabled while the request is pending and would otherwise drop focus. Rename/Delete buttons keep the plain names "Rename"/"Delete" as in the design; only the Move buttons include the name (AC-26).
- Dashboard: if both aggregates fail with the same message (e.g. backend down), it is shown once; the single dashboard-level Retry re-sends only the failed request(s).
- `VALIDATION_FAILED` with no usable `errors` entries shows "Some values were not accepted:" with no list (the design doesn't cover this case).
- 2026-11-16 tester: +69 AC-mapped Vitest tests in 6 files (215/215 pass, 3 consecutive runs); test setup asyncUtilTimeout 5 s for a parallel-load flake; AC-37 mutation proof done (not committed); build OK (JS 68.60 KB gzip); ./gradlew build 750/750, scripts/test-env.sh it 517/517; no product bugs; smoke checklist (AC-36, AC-38, manual parts of AC-1/2/32/34) marked NEEDS HUMAN in test-report.md
