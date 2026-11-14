# 0001. Integration tests use the Docker Compose services, not Testcontainers
Date: 2026-11-15 · Status: Accepted · Feature: agentic-sdlc-setup

## Context
Agents and humans develop on a laptop with Docker. We want one environment definition for
running the app locally and for integration tests. Tests that start their own containers are
slow and opaque, and they duplicate the compose definition.

## Decision
- `compose.yaml` defines the runtime environment: PostgreSQL 17, Redis 7, and the app under the
  `app` profile. Configuration comes from `.env`, and ports bind to 127.0.0.1.
- Integration tests (`src/integrationTest`, Gradle task `integrationTest`, profile `it`) connect to
  the already running services on localhost. `scripts/test-env.sh` starts the services and waits
  until they're healthy.
- Test code never starts or stops containers. Testcontainers and similar libraries are not used.
- Isolation per Gradle run: a random run id gives a private Postgres schema `it_<id>` (Flyway
  migrates it at context start; it's dropped after the run) and a Redis key prefix `it:<id>`
  (cleaned after every test and after the run).
- Unit tests (`src/test`, task `test`, part of `build`) need no Docker.

## Consequences
- The services must be running before `./gradlew integrationTest`. Forgetting this gives
  connection errors, not auto-start.
- Migrations must not hard-code schema names.
- Parallel runs on the same machine are safe because schemas and prefixes are per run. Data
  in the default `weather` schema is untouched by tests.

## Alternatives considered
- Testcontainers: rejected by project policy (see above).
- H2 or embedded Redis: rejected because they behave differently from production.
