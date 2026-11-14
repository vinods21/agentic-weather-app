# Architecture overview

Maintained by the `architect` agent: update it whenever a feature changes the structure.

## System
agentic-weather-app is a Spring Boot 3 (Java 21) HTTP service.

```
client ──HTTP──▶ app (Spring Boot :8080)
                  ├──JDBC──▶ PostgreSQL 17   (system of record; schema via Flyway)
                  └──RESP──▶ Redis 7         (cache; keys built by CacheKeys)
```

## Code layout
| Path | Purpose |
|------|---------|
| `src/main/java/com/example/weather/` | Application code (`WeatherApplication`) |
| `.../cache/CacheKeys` | Namespaced Redis keys (`weather.cache.key-prefix`) |
| `src/main/resources/db/migration/` | Flyway migrations `V<n>__*.sql` |
| `src/test/` | Unit tests (no Docker) |
| `src/integrationTest/` | Integration tests against compose services (profile `it`) |

## Runtime environment
`compose.yaml` (see ADR 0001): `postgres`, `redis`, plus `app` under the `app` profile.
Health is exposed at `/actuator/health` (readiness at `/actuator/health/readiness`).

## Features
_None yet. Each feature adds a row: name, components, ADRs, link to docs/sdlc/<slug>/._

## ADRs
- [0001](../adr/0001-integration-tests-use-docker-compose.md) Integration tests use the Docker Compose services, not Testcontainers
