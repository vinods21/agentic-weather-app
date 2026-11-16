# web-ui: SDLC status
Request: Start the UI: a React + Vite + TypeScript single-page app in ui/, run separately (Vite dev server on localhost:5173) and calling the /api/v1 backend over CORS. Human chose UI before the backend follow-up fix/ PR.
Branch: feature/web-ui · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-16 pm: requirements.md written (AC-1..AC-38)
- 2026-11-16 architect: design.md written; ADR 0017 (UI stack/hosting), ADR 0018 (UI testing, hand-written types + spec drift test); overview.md UI section added; open points R-1..R-10 in design.md
- 2026-11-16 human decisions: R-1 Search button stays enabled during a search (AC-17 reworded; latest-wins guard covers races). R-3 agents mark the visual/console/320px smoke-checklist items NEEDS HUMAN in test-report.md; the PR asks the human to complete them. R-6 (npm commands in CLAUDE.md, /sdlc and the settings allow-list) left as a separate human chore.
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
