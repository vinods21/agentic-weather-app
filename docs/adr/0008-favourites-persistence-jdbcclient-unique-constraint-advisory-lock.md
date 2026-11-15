# 0008. Favourites persistence: JdbcClient on spring-boot-starter-jdbc, DB-enforced uniqueness, per-client advisory lock, no transaction across provider calls
Date: 2026-11-15 · Status: Accepted · Feature: saved-favourite-locations

## Context
`saved-favourite-locations` is the first feature that stores data in PostgreSQL. Flyway and
`spring-boot-starter-jdbc` are already on the classpath (`V1__baseline.sql`), but there is no JPA
and no Spring Data JDBC, and the requirements forbid new dependencies. Later features will copy
the choices made here: the data-access API, how transactions are bounded, how ids are generated,
how invariants are enforced under concurrency, and how migrations are written.

Constraints from the requirements:
- duplicates (same client id plus the same normalised city) must be prevented by a database
  constraint, not only by an application check, and two concurrent creates must give exactly one
  `201` and one `409`, never a `500` (AC-1, AC-12);
- at most `max-per-client` rows per client, which must hold under concurrent creates (A-6);
- every write is a single transaction, and a failed request leaves no partial change;
- create calls the `WeatherProvider`, which can take up to about 10 s on an upstream timeout
  (ADR 0006);
- migrations must not contain schema names, because ITs run in a per-run schema `it_<id>`
  (ADR 0001).

## Decision
1. **Data access**: Spring Framework's `JdbcClient` (fluent API over `NamedParameterJdbcTemplate`),
   built from Boot's auto-configured `DataSource`/`JdbcTemplate`. `JdbcTemplate#batchUpdate` is
   used where `JdbcClient` has no batch API. All SQL is hand-written and parameterised. Row
   mapping is explicit (`RowMapper` lambdas into the app's own records). No JPA, no Spring Data,
   no `@Query` magic.
2. **Repository port**: the domain service depends on a small interface
   (`favourites.FavouriteRepository`) with one JDBC implementation. Unit tests use a fake or a
   mock, and no unit test needs a database.
3. **Transactions are explicit and short**: the service uses the auto-configured
   `TransactionOperations` (`TransactionTemplate`), not `@Transactional`, so that boundaries are
   visible and self-invocation can't silently drop them. **No transaction is held across a
   provider call**: create does its cheap pre-checks, calls the provider with no connection
   checked out, and only then opens the insert transaction.
4. **Invariants**:
   - Uniqueness is a `UNIQUE (client_id, city_key)` constraint. The service maps Spring's
     `DuplicateKeyException` (SQLSTATE 23505) to `409 FAVOURITE_ALREADY_EXISTS`, so a race that
     slips past the application checks is still a clean `409`.
   - Per-client serialisation: every transaction that changes the membership or order of a
     client's list (create, reorder, delete) starts with
     `pg_advisory_xact_lock(<fixed namespace int>, hashtext(client_id))`. The lock is released at
     commit/rollback. Under the lock the transaction re-checks the duplicate and the count, so
     the limit holds under concurrency even when the client has no rows yet (a `SELECT … FOR
     UPDATE` would lock nothing in that case). A hash collision only serialises two unrelated
     clients briefly.
5. **Ids** are `UUID`s generated in the application (`UUID.randomUUID()`), stored as Postgres
   `uuid`. They are not enumerable and need no database extension.
6. **Timestamps** are `timestamptz`, written from the service `Clock` (truncated to seconds) and
   bound as `OffsetDateTime` in UTC. pgjdbc doesn't bind `Instant` directly. There are no
   database-side `DEFAULT now()` values, so tests with a fixed clock are deterministic.
7. **Migrations**: one new `V<n>__*.sql` file per schema change. Never edit an applied file.
   Unqualified object names only (no `public.`, no `it_…`). Constraints and indexes get explicit
   names so that later migrations can refer to them.
8. **Error privacy**: the datasource sets pgjdbc's `logServerErrorDetail=false`, so the server's
   `Detail:` text (which for constraint violations contains the offending values, e.g. a client
   id) is never put into exception messages or the ERROR log of an unexpected database failure.

## Consequences
- No new dependency. The SQL is visible in one class and easy to review for injection and
  index use.
- Hand-written mapping costs some boilerplate per table, which is acceptable at this size.
- Create does two reads (duplicate, count) before the provider call and the same two reads
  again under the lock. These are cheap, indexed reads on at most 100 rows per client.
- The advisory lock is database-wide, not schema-scoped. Parallel IT runs in other schemas can
  share a lock id on a hash collision, which costs some latency but is still correct.
- A database outage gives `500 INTERNAL_ERROR` through the existing handler. There are no
  retries.
- A later feature that needs complex object graphs may adopt Spring Data JDBC through a new ADR.
  This ADR doesn't rule it out.

## Alternatives considered
- **Spring Data JDBC / JPA**: new dependencies (not on the classpath) and more machinery than one
  table needs. JPA's flush timing would also make the `DuplicateKeyException` mapping and the
  "no transaction across the provider call" rule harder to see.
- **`@Transactional` on service methods**: idiomatic, but create needs a non-transactional
  outer flow with a transactional inner step. With proxies that means a second bean or
  self-injection. `TransactionOperations` keeps it in one readable method and is trivially
  replaceable in unit tests (`TransactionOperations.withoutTransaction()`).
- **Holding one transaction (and a row/advisory lock) across the provider call**: simplest
  correctness argument, but a slow upstream would pin a pool connection and a lock for up to
  about 10 s per request.
- **`SERIALIZABLE` isolation**: correct, but it needs retry-on-serialization-failure logic, or
  the failures surface as `500`s.
- **A per-client parent row (`favourite_client`) locked with `SELECT … FOR UPDATE`**: also
  correct, but it needs an extra table and an upsert on every create just to have something to
  lock.
- **Database-generated ids (`gen_random_uuid()`) and `DEFAULT now()` timestamps**: the service
  clock (A-13) and test determinism favour application-side values.
