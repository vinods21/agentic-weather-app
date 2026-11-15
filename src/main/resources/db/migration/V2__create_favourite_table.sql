-- Saved favourite locations (ADR 0008/0009). Unqualified names only: ITs run in a per-run schema.
CREATE TABLE favourite (
    id                 UUID             NOT NULL,
    client_id          VARCHAR(64)      NOT NULL,
    city               VARCHAR(100)     NOT NULL,   -- as sent, stripped
    city_key           TEXT             NOT NULL,   -- CityNames.normalise(city)
    label              VARCHAR(50),
    location_name      TEXT             NOT NULL,
    location_country   TEXT             NOT NULL,
    location_latitude  DOUBLE PRECISION NOT NULL,
    location_longitude DOUBLE PRECISION NOT NULL,
    position           INTEGER          NOT NULL,
    created_at         TIMESTAMPTZ      NOT NULL,
    updated_at         TIMESTAMPTZ      NOT NULL,
    CONSTRAINT favourite_pk PRIMARY KEY (id),
    CONSTRAINT favourite_client_city_key_uq UNIQUE (client_id, city_key)
);

CREATE INDEX favourite_client_position_idx ON favourite (client_id, position);
