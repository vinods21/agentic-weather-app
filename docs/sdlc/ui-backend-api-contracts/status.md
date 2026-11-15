# ui-backend-api-contracts: SDLC status
Request: build the api contracts between ui and backend
Branch: feature/ui-backend-api-contracts · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15: run started. `scripts/test-env.sh up` failed: Docker daemon not running (npipe docker_engine not found).
- 2026-11-15 pm: requirements.md written (AC-1..AC-23)
- 2026-11-15: Docker now available; ran scripts/test-env.sh up.
- 2026-11-15 architect: design.md written; ADRs 0002 (hand-written OpenAPI), 0003 (API conventions), 0004 (provider port + stub); overview.md updated; no new dependencies, no migrations
