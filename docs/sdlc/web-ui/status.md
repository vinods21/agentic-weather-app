# web-ui: SDLC status
Request: Start the UI: a React + Vite + TypeScript single-page app in ui/, run separately (Vite dev server on localhost:5173) and calling the /api/v1 backend over CORS. Human chose UI before the backend follow-up fix/ PR.
Branch: feature/web-ui · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-16 pm: requirements.md written (AC-1..AC-38)
- 2026-11-16 architect: design.md written; ADR 0017 (UI stack/hosting), ADR 0018 (UI testing, hand-written types + spec drift test); overview.md UI section added; open points R-1..R-10 in design.md
- 2026-11-16 human decisions: R-1 Search button stays enabled during a search (AC-17 reworded; latest-wins guard covers races). R-3 agents mark the visual/console/320px smoke-checklist items NEEDS HUMAN in test-report.md; the PR asks the human to complete them. R-6 (npm commands in CLAUDE.md, /sdlc and the settings allow-list) left as a separate human chore.
