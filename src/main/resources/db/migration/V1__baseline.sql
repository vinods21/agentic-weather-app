-- Baseline migration. Schema changes for features go in V2__*.sql, V3__*.sql, ...
-- Migrations must not hard-code a schema name: integration tests run them in a
-- per-run schema (see src/integrationTest/resources/application-it.yml).
CREATE TABLE app_metadata (
    key        VARCHAR(100) PRIMARY KEY,
    value      VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
