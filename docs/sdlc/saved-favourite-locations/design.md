# Saved favourite locations: Design
Requirements: ./requirements.md · ADRs: 0001, 0002, 0003, 0005, 0006, 0007, 0008 (new), 0009 (new), 0010 (new, amends 0003), 0011 (new)

## Summary
This feature adds a CRUD API under `/api/v1/favourites` that stores saved cities in PostgreSQL
through a new Flyway migration `V2__create_favourite_table.sql`. Data access uses Spring's
`JdbcClient` on the existing `spring-boot-starter-jdbc` (no JPA, no Spring Data, no new
dependency; ADR 0008).

Favourites belong to an opaque `X-Client-Id` header (ADR 0009, A-1). The header is validated
by a `ClientId` argument resolver before anything else, and every query filters by client id.

Create resolves the city through a new port method `WeatherProvider#locate(city)` (ADR 0011).
The Open-Meteo adapter implements it as geocoding only, through the existing `WeatherCache`, so
a create costs at most 1 upstream call and shares the geocoding entry with the weather
endpoints. The duplicate key is the same normalisation `CacheKeys` uses, extracted into
`weather.CityNames` so that both share one implementation.

Integrity:
- a `UNIQUE (client_id, city_key)` constraint, and `DuplicateKeyException` maps to `409`;
- the limit is held under a per-client `pg_advisory_xact_lock`;
- the provider is never called inside a transaction.

The API follows ADR 0003 plus the new write conventions in ADR 0010, which amends 0003:
- JSON bodies;
- an unreadable body is `400 VALIDATION_FAILED` with field `body`;
- `201` + `Location`, `PATCH` with presence semantics, and `204` on delete;
- two new `409` codes;
- CORS allows `GET, POST, PUT, PATCH, DELETE, OPTIONS` and exposes `Location`.

The OpenAPI document stays hand-written (ADR 0002). The drift and conformance ITs cover the new
operations.

Final names (A-12):

| Item | Name |
|---|---|
| Collection | `/api/v1/favourites` |
| Item | `/api/v1/favourites/{id}` |
| Reorder | `PUT /api/v1/favourites/order` |
| Header | `X-Client-Id` |
| Properties | `id`, `city`, `label`, `location`, `createdAt`, `updatedAt`; reorder body `ids`; list body `items` |
| Configuration | `weather.favourites.max-per-client` / `WEATHER_FAVOURITES_MAX_PER_CLIENT` |
| Table | `favourite` |

The reorder path is kept as proposed because AC-26 requires `PUT /api/v1/favourites` to be
`405`. As a side effect, `GET|PATCH|DELETE /api/v1/favourites/order` match `{id}` and give
`400` (field `id`). This is documented in the spec.

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `db/migration/V2__create_favourite_table.sql` | One new file. Unqualified names only. Named constraint `favourite_client_city_key_uq UNIQUE (client_id, city_key)`. Flyway migrates `it_<id>` at context start. `V1__baseline.sql` is untouched. |
| AC-2 | `FavouritesController#create`, `FavouriteService#create`, `JdbcFavouriteRepository` | The `city` is stripped by the `CreateFavouriteRequest` constructor and stored as sent. `location` comes from `provider.locate(city)`. `createdAt = updatedAt = Instant.now(clock).truncatedTo(SECONDS)`. The response is `201` with `Location: /api/v1/favourites/<id>`. `X-Request-Id` comes from the existing filter. |
| AC-3 | `FavouriteResponse` (`@JsonInclude(NON_NULL)` on `label`) | A null label is omitted. A missing `label` and `"label": null` both bind to null. |
| AC-4 | `CreateFavouriteRequest` Bean Validation → existing `BindException` handler | `city`: `@NotBlank`, `@Size(max=100)`. `label`: repeatable `@Size(min=1, "must not be blank")` + `@Size(max=50, …)` on the stripped value (null allowed). Validation runs during argument resolution, so the service and provider are never reached. Errors are sorted by field (existing comparator). |
| AC-5 | `ApiExceptionHandler#handleUnreadableBody` (new) | `HttpMessageNotReadableException` → `400 VALIDATION_FAILED`, `errors [{body, "must be a JSON object matching the request schema"}]`. Fixed text. The exception message is never used. |
| AC-6 | `@PostMapping(consumes = APPLICATION_JSON_VALUE)` | `HttpMediaTypeNotSupportedException` is a 4xx `ErrorResponse`, so `handleUnexpected` rethrows it and Spring's default `415` applies. No handler method runs, so nothing is written. |
| AC-7 | `provider.locate` → `LocationNotFoundException` → existing handler | `detail` "No weather data is available for city 'Atlantis'." Nothing is inserted (the insert comes after `locate`). |
| AC-8 | existing `WeatherProviderUnavailableException` handler | `locate` throws before any transaction. The existing WARN line with the request id is reused. |
| AC-9 | `FavouriteService#create` pre-check, `CityNames.normalise` | `repository.existsByKey(clientId, key)` runs before `locate` → `FavouriteAlreadyExistsException` → `409 FAVOURITE_ALREADY_EXISTS`, detail "A favourite for this city already exists." (no echo). |
| AC-10 | every query has `client_id = :clientId` | The uniqueness scope is `(client_id, city_key)`. |
| AC-11 | `weather.CityNames.normalise` | Moved from `CacheKeys.normaliseCity` (which now delegates). `CacheKeys.geocoding` and the favourites key both call it. |
| AC-12 | advisory lock + re-check + unique constraint + `DuplicateKeyException` mapping | The loser either sees the row under the lock or hits the constraint. Either way it gets `409`, never `500` (ADR 0008 §4). |
| AC-13 | `FavouriteService#create`, `FavouritesProperties` | The order is: duplicate check → count check (`count >= max` → `FavouritesLimitReachedException(max)` → `409 FAVOURITES_LIMIT_REACHED`, detail "This client already has the maximum of 3 favourites.") → `locate`. Both checks are repeated under the lock. Because the duplicate check comes first, a duplicate that is also over the limit gives `FAVOURITE_ALREADY_EXISTS`. |
| AC-14 | `favourites.FavouritesProperties`, `application.yml`, `.env.example`, `compose.yaml` | `Integer maxPerClient`. null → 20. Outside 1..100 → `IllegalArgumentException("weather.favourites.max-per-client must be between 1 and 100 (env WEATHER_FAVOURITES_MAX_PER_CLIENT)")`. `abc` → the binder conversion failure, which names the property. |
| AC-15 | `api.ClientId`, `api.ClientIdArgumentResolver`, `InvalidClientIdException` handler | `ClientId` is the first parameter of every handler method, so it is resolved before the path id and the body. Invalid → `400 VALIDATION_FAILED` with exactly `[{X-Client-Id, "must match ^[A-Za-z0-9._-]{1,64}$"}]`. A unit test enforces the first-parameter rule. |
| AC-16 | repository `WHERE id = :id AND client_id = :clientId` | Another client's id is treated as missing: `404 NOT_FOUND`, the same detail. A reorder set mismatch gives `400` (AC-23). |
| AC-17, AC-18 | `FavouriteService#list`, `ORDER BY position, created_at, id` | New rows get `position = max + 1`. Query parameters are not bound, so `page`/`size` are ignored. |
| AC-19 | `FavouriteService` | Only `create` touches the provider. No favourites code touches Redis. |
| AC-20 | `#get`, `FavouriteNotFoundException` handler, existing `TypeMismatchException` handler | detail "No favourite exists with this id." `@PathVariable UUID id` with `not-a-uuid` gives the existing `[{id, "has an invalid value"}]`. |
| AC-21 | `UpdateFavouriteRequest`, repository `updateLabel … RETURNING` | One `UPDATE` sets `label` and `updated_at = now`. `city`, the location columns and `created_at` are never in the statement. Unknown body properties are ignored. |
| AC-22 | `UpdateFavouriteRequest` (presence flag + class-level `@LabelPresent`) | `{}` → `[{label, "must be present (null clears the label)"}]`. Blank/long labels give the same constraints as create. A malformed body is handled as in AC-5. An update of 0 rows → `404`. |
| AC-23 | `FavouriteService#reorder`, `ReorderFavouritesRequest`, `InvalidFavouriteOrderException` handler | `ids` is `List<String>` (so a non-UUID element is a field error, not a body error). The checks are listed under API. Under the advisory lock the client's id set is read and compared, and then positions 1..n are written in one transaction. Any failure → `400` on field `ids` before any update. |
| AC-24 | `position` column | Create appends `max + 1`. Delete leaves a gap, which is harmless for ordering. |
| AC-25 | `#delete` | `DELETE … WHERE id AND client_id` → 0 rows → `404`. Otherwise `204` with no body. |
| AC-26 | Spring routing + existing 405 handler | `/api/v1/favourites` maps only GET and POST, so `Allow: GET, POST`. `/{id}` maps GET, PATCH and DELETE. `/order` (PUT) does not match a UUID path. |
| AC-27 | `ErrorCode` | `FAVOURITE_ALREADY_EXISTS(CONFLICT, "favourite-already-exists", "Favourite already exists")` and `FAVOURITES_LIMIT_REACHED(CONFLICT, "favourites-limit-reached", "Favourites limit reached")` are appended. The spec enum lists all 8. |
| AC-28 | `openapi-v1.json` | See API → OpenAPI changes. A new `FavouritesContractConformanceIT`. |
| AC-29 | `ApiWebConfig` (ADR 0010) | `allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")`, `allowedHeaders("*")` (unchanged), `exposedHeaders(X-Request-Id, Location)`, `allowCredentials(false)`. |
| AC-30 | no new logging, `ClientId#toString` redacted, pgjdbc `logServerErrorDetail=false` | Only the existing access-log line (method + path + status) is written. Favourites code logs nothing at INFO+. |
| AC-31 | additive changes only | The weather controllers, DTOs and spec path entries are unchanged. `CorsIT#ac18_edge` switches its disallowed method to `TRACE`. |
| AC-32 | `OpenMeteoWeatherProvider#locate` (ADR 0011) | `geocode(cache.session(), city)` makes 1 `GEOCODING` call on a miss and writes `CacheKeys.geocoding(city)`. The following weather request hits that entry. |

## Components & packages
All under `com.example.weather`.

### `weather` (changed)
| Class | Change | Responsibility |
|---|---|---|
| `weather.CityNames` | **new** | `public final class` with `public static String normalise(String city)`: NFC → strip → collapse `\s+` (`UNICODE_CHARACTER_CLASS`) to one space → `toLowerCase(Locale.ROOT)` → NFC. The code moves from `CacheKeys` unchanged. |

### `cache` (changed)
| Class | Change | Responsibility |
|---|---|---|
| `cache.CacheKeys` | changed | `static String normaliseCity(String)` stays (package-private) and delegates to `CityNames.normalise`. `geocoding(city)` is unchanged in behaviour, so existing keys and `CacheKeysTest` still hold. |

### `provider` (changed, ADR 0011)
| Class | Change | Responsibility |
|---|---|---|
| `provider.WeatherProvider` | changed | Adds `default Location locate(String city) { return current(city).location(); }`, with Javadoc stating the failure contract. |
| `provider.openmeteo.OpenMeteoWeatherProvider` | changed | `@Override public Location locate(String city) { return geocode(cache.session(), city); }`. |
| `provider.stub.StubWeatherProvider` | changed | `@Override public Location locate(String city) { return lookup(city).current().location(); }`. |

### `favourites` (new domain package)
| Class | Responsibility |
|---|---|
| `favourites.Favourite` | `record Favourite(UUID id, String clientId, String city, String label, Location location, int position, Instant createdAt, Instant updatedAt)`. `toString()` omits `clientId` and `label`. |
| `favourites.FavouritesProperties` | `@ConfigurationProperties("weather.favourites") record FavouritesProperties(Integer maxPerClient)`. Found by `@ConfigurationPropertiesScan`. Validation is listed under AC-14. Accessor `int maxPerClient()`. |
| `favourites.FavouriteRepository` | Interface: `List<Favourite> findAll(String clientId)` (ordered), `Optional<Favourite> find(String clientId, UUID id)`, `boolean existsByKey(String clientId, String cityKey)`, `int count(String clientId)`, `void lockClient(String clientId)`, `void insert(Favourite f, String cityKey)` (the position is computed in SQL), `Optional<Favourite> updateLabel(String clientId, UUID id, String label, Instant updatedAt)`, `List<UUID> findIds(String clientId)`, `void updatePositions(String clientId, List<UUID> orderedIds)`, `boolean delete(String clientId, UUID id)`. |
| `favourites.JdbcFavouriteRepository` | `@Repository` implementation on Boot's auto-configured `JdbcClient` bean (Boot 3.2+), with the `JdbcTemplate` bean injected as well. It also uses `JdbcTemplate#batchUpdate` for `updatePositions`. SQL is under Data model. Instants are bound as `OffsetDateTime.ofInstant(i, UTC)` and read with `rs.getObject(col, OffsetDateTime.class).toInstant()`. UUIDs are bound as `java.util.UUID`. |
| `favourites.FavouriteService` | `@Service`. Constructor `(FavouriteRepository, WeatherProvider, TransactionOperations, Clock, FavouritesProperties)`. Methods `create(clientId, city, label)`, `list(clientId)`, `get(clientId, id)`, `rename(clientId, id, label)`, `reorder(clientId, List<String> ids)`, `delete(clientId, id)`. The flows are below. It never logs client ids or labels. |
| `favourites.FavouriteAlreadyExistsException` | `RuntimeException`, fixed message, no cause. |
| `favourites.FavouritesLimitReachedException` | Carries `int max`. Fixed message. |
| `favourites.FavouriteNotFoundException` | Fixed message. |
| `favourites.InvalidFavouriteOrderException` | Carries one fixed `message` from the list under API. |

**Create flow** (`FavouriteService#create`; `city` is already stripped and validated):
```
key = CityNames.normalise(city)
if repo.existsByKey(clientId, key)          -> FavouriteAlreadyExistsException
if repo.count(clientId) >= max              -> FavouritesLimitReachedException(max)
location = provider.locate(city)            // no connection held; LNF / unavailable propagate
now = Instant.now(clock).truncatedTo(SECONDS)
fav = new Favourite(UUID.randomUUID(), clientId, city, label, location, 0, now, now)
try:
  tx.execute:
    repo.lockClient(clientId)
    if repo.existsByKey(clientId, key)      -> FavouriteAlreadyExistsException
    if repo.count(clientId) >= max          -> FavouritesLimitReachedException(max)
    repo.insert(fav, key)
    return repo.find(clientId, fav.id())    // returns the stored row (with its position)
catch DuplicateKeyException                 -> FavouriteAlreadyExistsException
```
**Rename**: a single `updateLabel` statement (autocommit, atomic). Empty → `FavouriteNotFoundException`.
**Reorder**:
1. Outside the transaction, parse each element with `UUID.fromString` and check for duplicates
   among the parsed values.
2. Inside the transaction: `lockClient` → `findIds` → set-equality check →
   `updatePositions(1..n)` → `findAll`.
3. Every check throws before the first update. A thrown exception rolls the transaction back.

**Delete**: `tx.execute { lockClient; delete }`. False → `FavouriteNotFoundException`.
**List/get**: plain reads with no transaction.

### `api` (changed / new)
| Class | Change | Responsibility |
|---|---|---|
| `api.ClientId` | **new** | `record ClientId(String value)`. `HEADER = "X-Client-Id"`, `PATTERN = ^[A-Za-z0-9._-]{1,64}$`, `MESSAGE = "must match ^[A-Za-z0-9._-]{1,64}$"`. `static ClientId parse(String raw)`: null / no full match → `InvalidClientIdException`. There is no stripping: `" abc"` is invalid. `toString()` returns `"ClientId[***]"`. |
| `api.InvalidClientIdException` | **new** | `RuntimeException` with a fixed message. It never holds the value. |
| `api.ClientIdArgumentResolver` | **new** | `HandlerMethodArgumentResolver`: `supportsParameter` → the type is `ClientId`. `resolveArgument` → `ClientId.parse(request.getHeader(HEADER))`. |
| `api.ApiWebConfig` | changed | `addArgumentResolvers` registers `ClientIdArgumentResolver`. CORS methods and exposed headers change as in ADR 0010. The Javadoc is updated. |

### `api.error` (changed)
| Class | Change | Responsibility |
|---|---|---|
| `api.error.ErrorCode` | changed | Appends the two `CONFLICT` codes (AC-27). |
| `api.error.ApiExceptionHandler` | changed | New handlers, each starting with `guard(...)`. See Error handling. The existing handlers are unchanged. |

### `api.v1` (new)
| Class | Responsibility |
|---|---|
| `api.v1.FavouritesController` | `@RestController @RequestMapping(value="/api/v1/favourites", produces=APPLICATION_JSON_VALUE)`. The first parameter is always `ClientId`, and there are no constraint annotations on parameters (ADR 0010 §2). Mappings:<br>• `@GetMapping list(ClientId)`<br>• `@PostMapping(consumes=JSON) create(ClientId, @Valid @RequestBody CreateFavouriteRequest)` → `ResponseEntity.created(URI.create("/api/v1/favourites/" + id)).body(...)`<br>• `@GetMapping("/{id}") get(ClientId, @PathVariable UUID id)`<br>• `@PatchMapping(value="/{id}", consumes=JSON) rename(ClientId, @PathVariable UUID id, @Valid @RequestBody UpdateFavouriteRequest)`<br>• `@PutMapping(value="/order", consumes=JSON) reorder(ClientId, @Valid @RequestBody ReorderFavouritesRequest)`<br>• `@DeleteMapping("/{id}") delete(ClientId, @PathVariable UUID id)` → `ResponseEntity.noContent().build()` |
| `api.v1.CreateFavouriteRequest` | `record (String city, String label)`. The compact constructor strips both (null stays null). Constraints: `city` `@NotBlank(message="must not be blank") @Size(max=100, message="must be at most 100 characters")`. `label` `@Size(min=1, message="must not be blank") @Size(max=50, message="must be at most 50 characters")` (repeatable `@Size`; null passes). |
| `api.v1.UpdateFavouriteRequest` | A final class, not a record, because it must tell absent from null. It has a no-arg constructor and `@JsonSetter("label") void setLabel(String v)`, which stores `v == null ? null : v.strip()` and sets `labelPresent = true`. `label` carries the same two `@Size` constraints as create. The class-level constraint `@LabelPresent` (new annotation + validator in `api.v1`) reports `"must be present (null clears the label)"` on property node `label` when `!labelPresent`. Getters are `label()` and `labelPresent()`, and `@JsonIgnoreProperties(ignoreUnknown = true)` is set explicitly. |
| `api.v1.ReorderFavouritesRequest` | `record (@NotNull(message="must not be null") @Size(max=100, message="must contain at most 100 ids") List<String> ids)`. |
| `api.v1.dto.FavouriteResponse` | `record (String id, String city, @JsonInclude(NON_NULL) String label, LocationDto location, Instant createdAt, Instant updatedAt)` plus `static from(Favourite)`. `id` is `uuid.toString()` (lower case). |
| `api.v1.dto.FavouriteListResponse` | `record (List<FavouriteResponse> items)`. |

## API
Common to every operation:
- `X-Client-Id` is required (ADR 0009).
- `X-Request-Id` behaves as today, and every response carries it.
- Errors are `application/problem+json` as in ADR 0003.
- Request bodies need `Content-Type: application/json`. Anything else gives Spring's default `415`.

| Operation | Success | Errors |
|---|---|---|
| `GET /api/v1/favourites` | `200 FavouriteList` | 400 (client id), 500 |
| `POST /api/v1/favourites` body `CreateFavouriteRequest` | `201 Favourite`, `Location: /api/v1/favourites/{id}` | 400, 404 `LOCATION_NOT_FOUND`, 409 `FAVOURITE_ALREADY_EXISTS` / `FAVOURITES_LIMIT_REACHED`, 415, 500, 503 |
| `GET /api/v1/favourites/{id}` | `200 Favourite` | 400 (client id, `id`), 404 `NOT_FOUND`, 500 |
| `PATCH /api/v1/favourites/{id}` body `UpdateFavouriteRequest` | `200 Favourite` | 400, 404 `NOT_FOUND`, 415, 500 |
| `PUT /api/v1/favourites/order` body `ReorderFavouritesRequest` | `200 FavouriteList` (new order) | 400, 415, 500 |
| `DELETE /api/v1/favourites/{id}` | `204`, no body | 400 (client id, `id`), 404 `NOT_FOUND`, 500 |

Examples:
```json
POST /api/v1/favourites
{"city": "  london  ", "label": "Home"}

201 Created
Location: /api/v1/favourites/0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f
{"id":"0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f","city":"london","label":"Home",
 "location":{"name":"London","country":"GB","latitude":51.5074,"longitude":-0.1278},
 "createdAt":"2026-11-15T10:00:00Z","updatedAt":"2026-11-15T10:00:00Z"}

PATCH /api/v1/favourites/{id}   {"label": null}            -> 200, no "label" property
PUT   /api/v1/favourites/order  {"ids": ["<T>","<L>","<P>"]} -> 200 {"items":[T, L, P]}

409 {"type":"https://weather.example.com/problems/favourite-already-exists","title":"Favourite already exists",
     "status":409,"detail":"A favourite for this city already exists.","instance":"/api/v1/favourites",
     "code":"FAVOURITE_ALREADY_EXISTS","requestId":"…"}
```

Fixed texts:

| Case | `field` | `message` / `detail` |
|---|---|---|
| Client id | `X-Client-Id` | `must match ^[A-Za-z0-9._-]{1,64}$` |
| Unreadable body | `body` | `must be a JSON object matching the request schema` |
| Label absent (PATCH) | `label` | `must be present (null clears the label)` |
| `ids` missing/null | `ids` | `must not be null` |
| `ids` > 100 elements | `ids` | `must contain at most 100 ids` |
| non-UUID / null element | `ids` | `must contain only favourite ids (UUIDs)` |
| duplicate element | `ids` | `must not contain duplicate ids` |
| set mismatch (missing, unknown, other client's, `[]` with favourites) | `ids` | `must contain every favourite id of this client exactly once` |
| Missing favourite | (404 detail) | `No favourite exists with this id.` |
| Duplicate | (409 detail) | `A favourite for this city already exists.` |
| Limit | (409 detail) | `This client already has the maximum of <max> favourites.` |

All validation responses use the existing `VALIDATION_DETAIL`.

**OpenAPI changes (`openapi-v1.json`, hand-edited):**
- `info.description`: the first sentence becomes "Weather and saved-favourites API for the UI."
  It gains a paragraph on `X-Client-Id` (an opaque partition key, not authentication; 400 when
  invalid) and on JSON bodies (unreadable → `VALIDATION_FAILED` field `body`; non-JSON → 415).
  The CORS sentence becomes "Allowed methods are GET, POST, PUT, PATCH, DELETE and OPTIONS, no
  credentials; X-Request-Id and Location are exposed." "Read-only" and "GET and OPTIONS" are
  removed. The 405 sentence says "A method that is not supported on an existing path…".
- Tag `favourites`.
- New paths `/api/v1/favourites` (`get` listFavourites, `post` createFavourite),
  `/api/v1/favourites/{id}` (`get` getFavourite, `patch` updateFavourite, `delete` deleteFavourite)
  and `/api/v1/favourites/order` (`put` reorderFavourites). Path keys must equal the Spring
  patterns exactly (drift test). The `{id}` description notes that `order` is not an id.
- `components.parameters`:
  - `ClientId`: header, required, `pattern ^[A-Za-z0-9._-]{1,64}$`, `maxLength 64`.
  - `FavouriteId`: path `id`, required, `type string, format uuid`.
- `components.headers.Location`.
- `components.responses`:
  - `FavouriteNotFound` (404 `NOT_FOUND`) and `Conflict` (409, whose description lists both codes);
  - the `BadRequest` description is widened to "query parameters, path parameters, headers or
    the request body".
- `components.schemas`:
  - `Favourite`: required `id, city, location, createdAt, updatedAt`. `id` is a string
    (format uuid, `pattern ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).
    `city` is a string (minLength 1, maxLength 100). `label` is an optional string (minLength
    1, maxLength 50; "omitted when not set"). `location` is `$ref Location`. `createdAt` and
    `updatedAt` are date-time strings.
  - `FavouriteList`: required `items`, an array of `Favourite` with `maxItems 100`.
  - `CreateFavouriteRequest`: required `city`. `label` is a string, `nullable: true`.
  - `UpdateFavouriteRequest`: required `label` (string, `nullable: true`, "null clears").
  - `ReorderFavouritesRequest`: required `ids`, an array of uuid strings with `uniqueItems`.
  - `Problem.code` enum: the 8 codes.
  - The `FieldError.field` description becomes "Name of the invalid query parameter, path
    parameter, request header (e.g. X-Client-Id) or body property (e.g. city, ids), or `body`
    when the request body cannot be read."
- The `/api/v1/weather/*` path entries stay byte-identical.

## Data model
**Flyway** `src/main/resources/db/migration/V2__create_favourite_table.sql`. There are no schema
names, and no extensions are needed.
```sql
-- Saved favourite locations (ADR 0008/0009). Unqualified names only: ITs run in it_<id>.
CREATE TABLE favourite (
    id                 UUID         NOT NULL,
    client_id          VARCHAR(64)  NOT NULL,
    city               VARCHAR(100) NOT NULL,   -- as sent, stripped
    city_key           TEXT         NOT NULL,   -- CityNames.normalise(city)
    label              VARCHAR(50),
    location_name      TEXT         NOT NULL,
    location_country   TEXT         NOT NULL,
    location_latitude  DOUBLE PRECISION NOT NULL,
    location_longitude DOUBLE PRECISION NOT NULL,
    position           INTEGER      NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT favourite_pk PRIMARY KEY (id),
    CONSTRAINT favourite_client_city_key_uq UNIQUE (client_id, city_key)
);
CREATE INDEX favourite_client_position_idx ON favourite (client_id, position);
```
Column notes:
- `city_key` is `TEXT` because lower-casing can lengthen a string (e.g. `İ`).
- Java `@Size` counts UTF-16 units, which is always at least the number of Postgres characters,
  so validated values always fit.
- `double precision` round-trips exactly (PG ≥ 12 prints the shortest form).

SQL (named parameters, all through `JdbcClient` unless noted):

| Method | Statement |
|---|---|
| `findAll` | `SELECT … FROM favourite WHERE client_id = :clientId ORDER BY position, created_at, id` |
| `find` | `SELECT … FROM favourite WHERE client_id = :clientId AND id = :id` |
| `existsByKey` | `SELECT EXISTS (SELECT 1 FROM favourite WHERE client_id = :clientId AND city_key = :key)` |
| `count` | `SELECT count(*) FROM favourite WHERE client_id = :clientId` |
| `findIds` | `SELECT id FROM favourite WHERE client_id = :clientId` |
| `lockClient` | `SELECT pg_advisory_xact_lock(:ns, hashtext(:clientId))`, where `ns` = constant `FavouriteRepository.LOCK_NAMESPACE = 0x46415631`. The function returns `void`, so run it as a query and discard the row. |
| `insert` | `INSERT INTO favourite (id, client_id, city, city_key, label, location_name, location_country, location_latitude, location_longitude, position, created_at, updated_at) VALUES (:id, :clientId, :city, :key, :label, :name, :country, :lat, :lon, (SELECT COALESCE(MAX(position), 0) + 1 FROM favourite WHERE client_id = :clientId), :createdAt, :updatedAt)` |
| `updateLabel` | `UPDATE favourite SET label = :label, updated_at = :updatedAt WHERE client_id = :clientId AND id = :id RETURNING …` |
| `updatePositions` | `JdbcTemplate#batchUpdate("UPDATE favourite SET position = ? WHERE client_id = ? AND id = ?", …)` with positions 1..n |
| `delete` | `DELETE FROM favourite WHERE client_id = :clientId AND id = :id` |

**Configuration:**
| Property | Env var | Default | Notes |
|---|---|---|---|
| `weather.favourites.max-per-client` | `WEATHER_FAVOURITES_MAX_PER_CLIENT` | `20` | 1..100. `application.yml`: `${WEATHER_FAVOURITES_MAX_PER_CLIENT:20}`. `.env.example`: comment + `# WEATHER_FAVOURITES_MAX_PER_CLIENT=20` under a "Saved favourites (ADR 0008)" header. `compose.yaml` `app`: `WEATHER_FAVOURITES_MAX_PER_CLIENT: ${WEATHER_FAVOURITES_MAX_PER_CLIENT:-20}`. |
| `spring.datasource.hikari.data-source-properties.logServerErrorDetail` | (none) | `false` | ADR 0008 §8. A literal in `application.yml`, not configurable. |

`application-it.yml` is unchanged.

**Redis**: no new keys. `locate` reuses the existing `CacheKeys.geocoding(city)` entry (TTL
`geocoding-ttl` / `not-found-ttl`, ADR 0007).

## External integrations
- **WeatherProvider** (create only): `locate(city)`.
  - With Open-Meteo it is one geocoding call on a cache miss and none on a hit, bounded by the
    existing connect/read timeouts (2 s/3 s) with no retries (ADR 0006).
  - "Not found" → `404 LOCATION_NOT_FOUND`. It is negatively cached as today.
  - Unavailable → `503 UPSTREAM_UNAVAILABLE`, nothing written.
  - The call is made with no DB connection or lock held.
- **PostgreSQL**:
  - Boot's Hikari pool and the default timeouts.
  - The advisory-lock wait has no explicit timeout: the lock is held only for a few short
    statements, never across I/O.
  - A DB failure → `DataAccessException` → existing `500 INTERNAL_ERROR` (ERROR log with stack
    trace; values hidden by `logServerErrorDetail=false`).
  - Readiness is unchanged.
- **Redis**: not used by the favourites code (only indirectly, through the adapter's geocoding
  cache, which fails open).

## Error handling
New `ApiExceptionHandler` methods. Each calls `guard` first and builds the body through `ProblemFactory`.

| Exception | Response |
|---|---|
| `HttpMessageNotReadableException` | `400 VALIDATION_FAILED`, `errors [{body, "must be a JSON object matching the request schema"}]` |
| `InvalidClientIdException` | `400 VALIDATION_FAILED`, `errors [{X-Client-Id, ClientId.MESSAGE}]` |
| `InvalidFavouriteOrderException` | `400 VALIDATION_FAILED`, `errors [{ids, ex.message()}]` |
| `FavouriteNotFoundException` | `404 NOT_FOUND`, detail `No favourite exists with this id.` |
| `FavouriteAlreadyExistsException` | `409 FAVOURITE_ALREADY_EXISTS`, fixed detail |
| `FavouritesLimitReachedException` | `409 FAVOURITES_LIMIT_REACHED`, detail with `max` |

Unchanged paths:
- `MethodArgumentNotValidException` → the existing `BindException` handler: field errors only,
  sorted and distinct. `@LabelPresent` attaches its violation to property `label`, so it
  becomes a `FieldError`.
- `TypeMismatchException` (path `id`) → the existing handler.
- `LocationNotFoundException` and `WeatherProviderUnavailableException` → the existing handlers.
- 405 → the existing handler. 415 → rethrown (Spring default).
- `DuplicateKeyException` is caught inside `FavouriteService` and never reaches the advice.
- Any other exception → `500`.

None of the new handlers log anything, because these are client errors. Detail texts never
contain input values, SQL, class names or exception messages.

## Security considerations
- **No authentication** (A-1, ADR 0009): `X-Client-Id` is a partition key, not a secret
  boundary. The PR must surface A-1 for a human decision.
- **Isolation**: every statement has `client_id = :clientId`. A cross-client get, rename or
  delete gives the same `404` as a missing id, and a cross-client reorder the same `400` as an
  unknown id. Favourite ids are random UUIDs, so they are not enumerable.
- **Injection**: all SQL is parameterised. The client id is checked against
  `^[A-Za-z0-9._-]{1,64}$` before use. There is no dynamic SQL, and the `ORDER BY` is constant.
- **Privacy**: client ids and labels are never logged.
  - The access log has the method, path (favourite UUID only) and status.
  - `ClientId` and `Favourite` have redacting `toString()`s.
  - pgjdbc server details are suppressed.
  - Problem bodies never echo the client id, label or a duplicate city. Only
    `LOCATION_NOT_FOUND` echoes the city, as it already does for the weather endpoints (AC-7).
- **Resource bounds**:
  - city ≤ 100, label ≤ 50, client id ≤ 64;
  - `ids` ≤ 100 elements;
  - ≤ `max-per-client` (≤ 100) rows per client;
  - the provider is never called for a duplicate or over-limit create, so quota can't be
    burned that way.
  - The total number of client ids is unbounded (A-9, accepted).
- **CORS** (ADR 0010): still deny-by-default with no credentials. The extra methods only work
  on paths that map them.
- **Concurrency**: last-write-wins for rename/reorder (ETags out of scope). The limit and
  uniqueness are race-free (ADR 0008).

## Test strategy
Unit tests live in `src/test` (no Spring context, no Docker). Integration tests live in
`src/integrationTest`, extend `IntegrationTestBase` and use the compose Postgres/Redis through
profile `it`. Testcontainers is never used, and no test starts or stops a container (ADR 0001).
Every test has `@DisplayName("AC-n: …")`. ITs delete the rows they created in `@AfterEach`.

### Unit tests
| Test | ACs | Notes |
|---|---|---|
| `weather.CityNamesTest` | AC-11 | The `New York` variants are equal. `São Paulo` NFC = NFD. `Sao Paulo` ≠ `São Paulo`. For a sample set, `CityNames.normalise(x)` equals `CacheKeys.normaliseCity(x)` (proves delegation). `CacheKeysTest` is unchanged and passes. |
| `favourites.MigrationFilesTest` | AC-1 (static part) | `db/migration` holds exactly `V1__baseline.sql` and one `V2__*.sql`. V2 contains no `public.` and no `it_` (case-insensitive), and contains `UNIQUE (client_id, city_key)`. The `git diff main -- …V1__baseline.sql` check is done by the tester/reviewer. |
| `favourites.FavouritesPropertiesTest` | AC-14 | `Binder` + `MapConfigurationPropertySource` (as in `WeatherCachePropertiesTest`): default 20; `1` and `100` are accepted; `0`, `-1`, `101`, `abc` → `BindException` whose message chain contains `weather.favourites.max-per-client`. |
| `favourites.FavouritesConfigFilesTest` | AC-14 | `application.yml` placeholder, the commented line in `.env.example` (and no live `WEATHER_FAVOURITES_MAX_PER_CLIENT=` line), and the `compose.yaml` pass-through `${WEATHER_FAVOURITES_MAX_PER_CLIENT:-20}`. |
| `favourites.FavouriteServiceTest` | AC-9, 12, 13, 21, 23, 24 (logic) | Mock `FavouriteRepository` and `WeatherProvider`, `TransactionOperations.withoutTransaction()`, fixed `Clock`. Covers: a duplicate before the provider; the limit before the provider; duplicate+limit → duplicate; `DuplicateKeyException` from `insert` → `FavouriteAlreadyExistsException`; the limit re-check under the lock; timestamps truncated to seconds; each reorder message; reorder never calls `updatePositions` on failure; the provider is never called by list/get/rename/reorder/delete. |
| `api.ClientIdTest` | AC-15 | null, `""`, 65 chars, `a b`, `a/b`, `ü`, `" abc"` → `InvalidClientIdException`. 64 chars is accepted. `toString` doesn't contain the value. |
| `api.v1.FavouriteRequestValidationTest` | AC-4, AC-22, AC-23 | Jakarta `Validator` (as in `QueryValidationTest`) plus a plain `ObjectMapper` for `UpdateFavouriteRequest` presence. Covers every AC-4 table row, the 100/101 and 50/51 boundaries after stripping, `{}` vs `{"label":null}` for PATCH, and `ids` null. |
| `api.v1.FavouritesControllerSignatureTest` | AC-15 | Reflection: every `@RequestMapping`-annotated method of `FavouritesController` has `ClientId` as parameter 0, and no parameter has a `jakarta.validation.constraints.*` annotation. |
| `api.error.ApiExceptionHandlerTest` (new cases) | AC-5, 13, 20, 27 | Unreadable body → fixed error (the body contains no `Jackson`, `com.`, `java.` or input text). Each new exception → status/code/type/detail. |
| `api.v1.OpenApiDocumentTest` (extended) | AC-27, AC-28 | The enum test passes with 8 codes. The favourites paths exist. `info.description` doesn't contain `Read-only` or `GET and OPTIONS`. `Favourite.location.$ref` = `#/components/schemas/Location`. |
| `provider.stub.StubWeatherProviderTest`, `provider.openmeteo.OpenMeteoWeatherProviderTest` (new cases) | AC-32 (unit) | Stub `locate` returns the location and throws for unknown cities. Open-Meteo `locate` sends only the geocoding request (`MockRestServiceServer`) and uses the cache session. |

### Integration tests (`src/integrationTest/java/com/example/weather/favourites/`)
Shared support `support.FavouritesIntegrationTestBase extends ApiIntegrationTestBase`:
- `@TestPropertySource(properties = {"weather.favourites.max-per-client=20", "spring.datasource.hikari.maximum-pool-size=4"})`,
  so an exported env var can't change outcomes;
- `@MockitoSpyBean WeatherProvider provider` (reset after each test by Spring);
- `@TestBean Clock clock` backed by a static `MutableClock`, reset to `2026-11-15T10:00:00Z` in
  `@BeforeEach`;
- `newClientId()`, which returns `"it-fav-" + UUID` and records it;
- `@AfterEach deleteFavourites()` → `jdbc.update("DELETE FROM favourite WHERE client_id = ?", id)`
  for each recorded id;
- helpers `post/patch/put/delete(path, clientId, jsonBody, extraHeaders)` that send a raw JSON
  string with `Content-Type: application/json` (so malformed bodies can be sent), plus
  `rows(clientId)`.

All subclasses without extra properties share one context.

| IT | Base / context | ACs |
|---|---|---|
| `FavouritesSchemaIT` | `IntegrationTestBase` (default context) | AC-1: `flyway_schema_history` in `current_schema()` has version `2`, `success = true`. `information_schema.tables` has `favourite` in `it_<id>`. A second `INSERT` with the same `(client_id, city_key)` via `jdbc` → `DuplicateKeyException`. Cleanup deletes the probe rows. |
| `CreateFavouriteIT` | favourites base | AC-2, AC-3, AC-4 (+ `verifyNoInteractions(provider)`; 100-char city → 404; 50-char label → 201), AC-6, AC-7, AC-8 (`doThrow(...).when(provider).locate(anyString())`, `OutputCaptureExtension` for the WARN with `it-fav-8`, then `Mockito.reset(provider)` → 201) |
| `FavouriteBodyErrorsIT` | favourites base | AC-5 (the 4 bodies on POST), AC-22 malformed-body part on PATCH |
| `FavouriteDuplicatesIT` | favourites base | AC-9 (`verify(provider, times(1)).locate(anyString())`), AC-10, AC-12 (10 repetitions: 2 threads + `CountDownLatch`, statuses sorted = `[201, 409]`, 1 row, rows deleted between repetitions) |
| `FavouritesLimitIT` | favourites base + `@TestPropertySource("weather.favourites.max-per-client=3")` (its own context) | AC-13 |
| `ClientIdIT` | favourites base | AC-15 (parameterised over the 6 operations × the invalid values; 64-char id accepted; invalid header + malformed body → contains the header error; row counts unchanged; `verifyNoInteractions`), AC-16 |
| `ListGetFavouritesIT` | favourites base | AC-17, AC-18, AC-20 |
| `FavouritesNoUpstreamIT` | favourites base | AC-19: rows are created first, then `doThrow` on `locate`/`current`/`currentAt`/`forecast` and `clearInvocations(provider)`, then the 5 operations, then `verifyNoInteractions(provider)` |
| `RenameFavouriteIT` | favourites base | AC-21 (clock moved to 11:00), AC-22 |
| `ReorderFavouritesIT` | favourites base | AC-23 (each failure case asserts the unchanged order via list), AC-24 |
| `DeleteFavouriteIT` | favourites base | AC-25 |
| `FavouritesRoutingIT` | `ApiIntegrationTestBase` | AC-26 |
| `FavouritesContractConformanceIT` | favourites base + `OpenApiSchemaAssert` | AC-28: the 201 create and 200 get/list/rename/reorder bodies against `Favourite`/`FavouriteList`. 400, 404 `NOT_FOUND`, 404 `LOCATION_NOT_FOUND`, 409 `FAVOURITE_ALREADY_EXISTS` and 503 against `Problem`. The 409 `FAVOURITES_LIMIT_REACHED` body is checked against `Problem` in `FavouritesLimitIT`, which has the max=3 context, so no extra context is needed. |
| `FavouritesCorsIT` | `ApiIntegrationTestBase` + `@TestPropertySource("weather.api.cors.allowed-origins=http://ui.example")` | AC-29 allowed/denied origins, the four methods, request headers, no credentials |
| `CorsDefaultDenyIT` (one new parameterised case) | existing | AC-29 empty allow-list for an `OPTIONS /api/v1/favourites/<uuid>` preflight |
| `FavouritesLoggingIT` | favourites base + `OutputCaptureExtension` | AC-30: exactly one INFO `RequestIdFilter` line with `it-fav-30` per request, and no INFO+ line with `cid-SECRET30`/`LBL-SECRET30`. The client id `cid-SECRET30` is a fixed value, so it is registered for cleanup. |
| `FavouritesRealProviderIT` | `CachingIntegrationTestBase` (real adapter + `FakeOpenMeteo`, cache on) + its own `@AfterEach` row cleanup | AC-32: `received(GEOCODING).size()==1`, `CURRENT`/`DAILY` 0. Then `GET /api/v1/weather/current?city=London` → still 1 `GEOCODING`. |
| Existing ITs | unchanged contexts | AC-31. `CorsIT#ac18_edge_disallowedMethodRejected` uses `TRACE` instead of `DELETE` (an assertion update the requirements allow). `OpenApiDriftIT` and `RealProviderOpenApiDriftIT` pass once the spec has the 6 operations. |

Context count: this adds 3 cached contexts (favourites base, limit, CORS origin).
`FavouritesRealProviderIT` reuses the existing `CachingIntegrationTestBase` context. Each has a small Hikari pool, and the favourites base uses 4
connections so that AC-12's two concurrent requests plus the test's `jdbc` never starve.

## Implementation plan
1. `refactor: extract city normalisation into weather.CityNames`: move the code, make
   `CacheKeys.normaliseCity` delegate, and add `CityNamesTest` (AC-11). No behaviour change.
2. `feat: add WeatherProvider#locate with a geocoding-only Open-Meteo override`: the default
   method, the stub and Open-Meteo overrides, and the unit tests (ADR 0011).
3. `feat: favourites table migration and max-per-client setting`: `V2__create_favourite_table.sql`,
   `FavouritesProperties`, the `application.yml` entries (including `logServerErrorDetail`), the
   `.env.example` and `compose.yaml` lines, plus `MigrationFilesTest`, `FavouritesPropertiesTest`
   and `FavouritesConfigFilesTest` (AC-1 static, AC-14).
4. `feat: favourites domain service and JDBC repository`: `Favourite`, the exceptions,
   `FavouriteRepository`, `JdbcFavouriteRepository`, `FavouriteService`, and
   `FavouriteServiceTest`.
5. `feat: error codes and handlers for favourites and unreadable bodies`: the `ErrorCode`
   additions, the `Problem.code` enum in `openapi-v1.json` (so that `OpenApiDocumentTest` keeps
   passing), the new `ApiExceptionHandler` methods, and handler unit tests (AC-5, 27).
6. `feat: X-Client-Id resolver and CORS for write methods`: `ClientId`,
   `InvalidClientIdException`, `ClientIdArgumentResolver`, and the `ApiWebConfig` changes (ADR
   0009/0010). Update `CorsIT#ac18_edge` to `TRACE`. Add `ClientIdTest`.
7. `feat: favourites REST controller and OpenAPI contract`: the controller, the request records
   and classes (`@LabelPresent`), the DTOs, and all spec additions and description changes in
   the same commit (the drift test), plus the request-validation and signature unit tests and
   the `OpenApiDocumentTest` extensions.
8. `test: favourites integration test support and CRUD ITs`: `FavouritesIntegrationTestBase`,
   `FavouritesSchemaIT`, `CreateFavouriteIT`, `FavouriteBodyErrorsIT`, `ListGetFavouritesIT`,
   `RenameFavouriteIT`, `ReorderFavouritesIT` and `DeleteFavouriteIT`.
9. `test: favourites isolation, concurrency, limit, contract, CORS, logging and quota ITs`:
   `ClientIdIT`, `FavouriteDuplicatesIT`, `FavouritesLimitIT`, `FavouritesNoUpstreamIT`,
   `FavouritesRoutingIT`, `FavouritesContractConformanceIT`, `FavouritesCorsIT`, the
   `CorsDefaultDenyIT` case, `FavouritesLoggingIT` and `FavouritesRealProviderIT`.
10. `docs: …`: Javadoc. Run `scripts/test-env.sh all`, and check that
    `git diff main -- src/main/resources/db/migration/V1__baseline.sql` is empty and that the
    `/api/v1/weather/*` spec entries are unchanged.

## Risks / open questions
- **A-1 (human decision)**: `X-Client-Id` ownership versus a single global list. If the human
  picks the global list:
  - drop `ClientId` and its resolver;
  - make the unique constraint `(city_key)` and the advisory-lock key a constant;
  - drop AC-10/15/16;
  - ADR 0009 becomes Superseded.

  Doing this before merge is cheap. After merge it is a breaking change.
- **A-5/A-6 confirmations**: the duplicate rule (normalised submitted text, not the resolved
  place) and the limit (20, `409`) are implemented as assumed.
- **Exposing `Location` in CORS** (ADR 0010) is a small addition that the ACs don't strictly
  require. It is cheap to drop if reviewers object.
- **Jackson scalar coercion**: Boot's `ObjectMapper` coerces scalars to strings, so
  `{"city": 123}` binds as `"123"` (then `404 LOCATION_NOT_FOUND`), and `{"label": true}` as
  `"true"`. The ACs don't cover this. Making the mapper strict would change global
  deserialisation and is left out.
- **405 `Allow` contents**: we expect Spring 6.2 to list exactly the declared methods (`GET, POST`,
  with no implicit `HEAD`/`OPTIONS`) for AC-26. Verify this in step 9. If it adds `HEAD`, the
  AC's "exactly" needs a human ruling, rather than a workaround in the handler.
- **Non-ASCII header in tests (AC-15 `ü`)**: `TestRestTemplate` uses the JDK `HttpClient`, which
  might reject a non-ASCII header value client-side. In that case the IT sends that one request
  over a raw `java.net.Socket` (no new dependency). PATCH works through the JDK `HttpClient`
  (it would not through `HttpURLConnection`).
- **`GET /api/v1/favourites/order`** gives `400` (field `id`), not `404`/`405`, because it
  matches `{id}`. This is documented and accepted per A-12.
- **Advisory locks across IT runs**: they are database-wide, so parallel runs can collide on a
  hash and serialise briefly. That costs a little latency and is still correct.
- **Position gaps** after deletes are intentional. Positions are only an ordering, and they are
  never exposed.
