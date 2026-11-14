# agentic-weather-app

Spring Boot 3 / Java 21 weather service using PostgreSQL and Redis, built with an agentic SDLC
in Claude Code.

## Quick start

```bash
scripts/test-env.sh up      # postgres + redis via Docker Compose (creates .env from .env.example)
./gradlew build             # compile + unit tests (no Docker needed)
scripts/test-env.sh it      # integration tests against the compose services
./gradlew bootRun           # run the app on http://localhost:8080 (health: /actuator/health)
```

See [CLAUDE.md](CLAUDE.md) for all commands and project rules.

## Agentic SDLC with Claude Code

Open Claude Code in the repo (`claude`). The project settings select Opus and the
`acceptEdits` permission mode.

### `/sdlc <feature description>`
Runs the whole pipeline for one feature:

1. **pm** writes `docs/sdlc/<slug>/requirements.md` (user stories, AC-1..n, out of scope)
2. **architect** writes `design.md`, ADRs in `docs/adr/`, and updates `docs/architecture/overview.md`
3. **developer** implements on `feature/<slug>` in small commits
4. **tester** writes unit and integration tests per AC, runs them against Docker Compose, and writes `test-report.md`
5. pushes the branch and opens a PR whose body lists the ACs and links the docs
6. **pr-reviewer** posts one COMMENT review with an AC traceability table and a verdict. On
   CHANGES NEEDED, the developer fixes the comments and the reviewer reviews again (max 2 rounds).
7. stops. **You review and merge the PR** (merge commit).

Example: `/sdlc Return the current weather for a city, cached in Redis for 10 minutes`

Resume an interrupted run with `/sdlc resume <slug>`. Progress is kept in `docs/sdlc/<slug>/status.md`.

### `/review-pr <number>`
Runs only the pr-reviewer on any PR, including PRs written by humans. It posts one review
(never an approval) with inline comments and a READY FOR HUMAN MERGE / CHANGES NEEDED verdict.

### Guardrails
- Agents can push only `feature/*`, `fix/*` and `chore/*` branches. Pushes to main, force
  pushes, rebases, PR merges and approvals are blocked by `.claude/hooks/guard-bash.sh`.
- Integration tests use the Docker Compose services and never Testcontainers ([ADR 0001](docs/adr/0001-integration-tests-use-docker-compose.md)).
