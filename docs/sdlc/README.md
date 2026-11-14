# SDLC handoff folder

Each feature run by `/sdlc` gets a folder `docs/sdlc/<feature-slug>/`:

| File | Written by | Purpose |
|------|-----------|---------|
| `status.md` | orchestrator + every agent | phase checklist, log, PR number; used to resume (`/sdlc resume <slug>`) |
| `requirements.md` | pm | user stories, AC-1..n, out of scope |
| `design.md` | architect | design, AC mapping, implementation plan (+ ADRs in `docs/adr/`) |
| `test-report.md` | tester | AC → test → result table |
| `pr-body.md` | orchestrator | PR description |
