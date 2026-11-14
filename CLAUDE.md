# CLAUDE.md

agentic-weather-app is a Spring Boot 3 / Java 21 Gradle weather service using PostgreSQL
(Flyway) and Redis. Work here is done by Claude Code agents through an SDLC pipeline. Humans
review and merge every PR.

## Hard rules
- **Never push to `main`/`master`**, never force push, never rebase, never `reset --hard`.
  `.claude/hooks/guard-bash.sh` blocks these, along with gh pr merge/close/approve, curl/wget,
  sudo, `rm -rf` and reading secrets.
- **Merge commits only**: no rebase, no squash. Update a branch with `git pull --no-rebase origin main`
  or `git merge --no-ff origin/main`.
- **Humans merge PRs.** Agents open PRs and post COMMENT reviews only, never APPROVE.
- **Integration tests use the Docker Compose services; never use Testcontainers** (or anything
  that starts or stops containers from test code). See [ADR 0001](docs/adr/0001-integration-tests-use-docker-compose.md).
- Don't read or print `.env`, `**/secrets/**`, `~/.ssh`, `~/.aws` or `~/.kube`. Use `.env.example` for reference.
- No new dependencies unless the design/an ADR calls for them.

## Branches and commits
- `feature/<slug>` for features, `fix/<slug>` for bug fixes, `chore/<slug>` for tooling/docs.
  These are the only branch prefixes agents can push.
- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`); small commits that each build.
- Push with `git push -u origin <branch>`; open PRs against `main` with `gh pr create`.

## Workflow (`/sdlc <feature description>`)
pm → architect → developer → tester → push + PR → pr-reviewer (≤ 2 fix rounds) → stop for a human.
The handoff files live in `docs/sdlc/<slug>/` (status.md, requirements.md, design.md,
test-report.md, pr-body.md), so `/sdlc resume <slug>` can continue a run. ADRs go in
`docs/adr/NNNN-*.md`, and the system overview in `docs/architecture/overview.md`.
`/review-pr <n>` runs only the reviewer.

Agents are defined in `.claude/agents/`: pm, architect, developer, tester, pr-reviewer (all Opus).

## Environment (Docker Compose)
Services: `postgres` (17, localhost:5432), `redis` (7, localhost:6379), and `app` (profile `app`, localhost:8080).
Configuration comes from `.env`; `scripts/test-env.sh` creates it from `.env.example` if missing.

| Task | Command |
|------|---------|
| Start postgres + redis, wait for healthy | `scripts/test-env.sh up` |
| Status / logs | `scripts/test-env.sh status` · `scripts/test-env.sh logs [service]` |
| Stop (keeps data) | `scripts/test-env.sh down` |
| Also run the app in Docker | `docker compose --profile app up -d --build --wait` |
| Wipe data (asks the human first) | `docker compose down -v` |

## Build and test
**Builds and tests run on the host** (JDK 21+ is installed; the Gradle wrapper targets Java 21
bytecode). Containers are only used for the backing services, not for running Gradle.

| Task | Command |
|------|---------|
| Compile + unit tests (no Docker) | `./gradlew build` (or `./gradlew test`) |
| Integration tests (needs compose up) | `scripts/test-env.sh it` (= up + `./gradlew integrationTest`) |
| Everything | `scripts/test-env.sh all` |
| Run app on host | `./gradlew bootRun` (needs compose up) |
| Hook self-test | `bash .claude/hooks/test-guard.sh` |

Tests:
- Unit tests: `src/test/java`, `*Test.java`, no Spring infra, no Docker.
- Integration tests: `src/integrationTest/java`, `*IT.java`, extend `IntegrationTestBase` (profile `it`,
  `src/integrationTest/resources/application-it.yml`).
- Isolation: every Gradle run gets a random run id, which gives Postgres schema `it_<id>` (Flyway-migrated
  at startup, dropped after the run) and Redis prefix `it:<id>` (build keys with `CacheKeys`). Delete
  the rows your test creates in `@AfterEach`.
- Map tests to acceptance criteria: `@DisplayName("AC-n: ...")`.

## Code conventions
- Package root `com.example.weather`. Configuration lives in `application.yml` with env-var placeholders.
- DB changes only through new Flyway migrations `V<n>__desc.sql`. Never edit an applied migration, and
  never hard-code schema names.
- Redis keys go through `CacheKeys` and always carry a TTL.
