# Saved favourite locations: Requirements
Feature slug: saved-favourite-locations · Status: draft

## Problem / goal
Today the UI must re-enter a city for every `/api/v1/weather/*` request. The service has no way to
remember the places a user cares about. PostgreSQL is wired up (Flyway, `V1__baseline.sql`), but
no feature uses it yet.

Goal: a small CRUD API under `/api/v1/favourites` that saves cities to PostgreSQL. You can create
a favourite (the city is resolved through the `WeatherProvider`), list, get, rename (an optional
label), reorder and delete favourites. The table comes from a new Flyway migration. The API
follows ADR 0002 (spec-first OpenAPI, drift and conformance tests) and ADR 0003 (Problem Details,
fixed `ErrorCode` set, `X-Request-Id`, CORS allow-list).

v1 has **no authentication**. This feature does not add any. Ownership is an opaque
client-supplied id (A-1, **needs a human decision**).

Unless an AC says otherwise:
- the `it` profile applies, which means the stub provider. Valid cities are London, Paris,
  New York, Tokyo and Sydney (case-insensitive). Any other city is not found.
- every request carries `X-Client-Id: <unique value per test>` and, if it has a body,
  `Content-Type: application/json`.
- the database is the compose Postgres in the per-run schema `it_<id>`. Tests delete the rows
  they create in `@AfterEach`. Testcontainers is never used (ADR 0001).
- "the provider is not called" means zero interactions on a `@MockitoSpyBean WeatherProvider`,
  as in `ProviderFailureIT`.
- the service clock is the `Clock` bean. Tests that assert timestamps fix it.

Path and field names below are proposals. The architect may rename them (A-12) and records the
final names in design.md and `openapi-v1.json`.

## User stories
- As a UI user, I want to save the cities I check often, so that I can see their weather without
  typing them again.
- As a UI user, I want to give a favourite a short label (e.g. "Home") and put my favourites in my
  own order, so that the list matches how I think about the places.
- As a UI user, I want to be told when a city doesn't exist or is already saved, so that my list
  stays clean.
- As a UI developer, I want the favourites endpoints in the OpenAPI document and errors in the
  same Problem Details format with stable codes, so that I can generate a client and handle errors
  in one place.
- As an operator, I want the list size capped and configurable, so that one client can't grow
  the table without bound.

## Acceptance criteria

### Schema
- **AC-1**: Given the repository, then exactly one new migration
  `src/main/resources/db/migration/V2__<desc>.sql` (the next free version) creates the favourites
  table. It contains no schema name (no `public.`, no `it_`), and `V1__baseline.sql` is unchanged
  (`git diff main -- src/main/resources/db/migration/V1__baseline.sql` is empty). When the IT
  context starts, the `flyway_schema_history` table in `it_<id>` has version `2` with
  `success = true`, and the table exists in `it_<id>`. A unique constraint on
  (client id, normalised city key) exists, so inserting a second row with the same client id and
  key directly through `JdbcTemplate` fails.

### Create: `POST /api/v1/favourites`
- **AC-2**: Given a fixed clock at `2026-11-15T10:00:00Z` and no favourites, when a request posts
  `{"city": "  london  ", "label": "Home"}`, then the response is `201` with a `Location` header
  `/api/v1/favourites/<id>` and an `X-Request-Id` header. The body has `id` (a UUID string equal
  to `<id>`), `city "london"` (the city as sent, stripped), `label "Home"`,
  `location {name "London", country "GB", latitude 51.5074, longitude -0.1278}` (the
  provider-resolved location) and `createdAt` = `updatedAt` = `"2026-11-15T10:00:00Z"`. Exactly one
  row exists for this client, holding these values.
- **AC-3**: Given no favourites, when `{"city": "Paris"}` is posted (no label), then the response
  is `201` and the body has no `label` property. In a second case, `{"city": "Paris", "label": null}`
  gives the same result.
- **AC-4**: When each of the following bodies is posted, the response is `400 VALIDATION_FAILED`
  with exactly the listed `errors` (sorted by field, as today), no row is written and the
  provider is not called:
  | Body | errors |
  |---|---|
  | `{}` | `city: must not be blank` |
  | `{"city": "   "}` | `city: must not be blank` |
  | `{"city": "<101 chars>"}` | `city: must be at most 100 characters` |
  | `{"city": "London", "label": "   "}` | `label: must not be blank` |
  | `{"city": "London", "label": "<51 chars>"}` | `label: must be at most 50 characters` |
  | `{"city": "", "label": ""}` | `city: must not be blank`, `label: must not be blank` |

  A 100-character city passes validation and reaches the provider (with the stub this gives
  `404 LOCATION_NOT_FOUND`). `{"city": "London", "label": "<50 chars>"}` gives `201`. Lengths
  are counted after stripping. The city rules match the weather endpoints' `city` parameter.
- **AC-5**: When the request body is each of: empty, `{"city":` (invalid JSON), `[]`, and
  `{"city": ["London"]}`, then the response is `400 VALIDATION_FAILED` (never 500). `errors`
  holds exactly one entry with `field "body"` and fixed message text. The text contains no
  Jackson or Java class names and none of the submitted content. No row is written.
- **AC-6**: Given `Content-Type: text/plain` with body `London`, when it is posted, then the
  response status is `415` and no row is written. The body format follows Spring's default for
  framework 4xx errors outside the code set (ADR 0003, Consequences). Adding a code for it is
  out of scope.
- **AC-7**: When `{"city": "Atlantis"}` is posted, then the response is `404 LOCATION_NOT_FOUND`.
  Its `detail` contains `Atlantis`, as the weather endpoints do today, and no row is written.
- **AC-8**: Given the provider spy throws `WeatherProviderUnavailableException` for any call and
  header `X-Request-Id: it-fav-8`, when `{"city": "London"}` is posted, then the response is
  `503 UPSTREAM_UNAVAILABLE` with `requestId "it-fav-8"`. No row is written, and a WARN line with
  `it-fav-8` is logged (the existing handler). After the spy is reset, the same request gives `201`.
- **AC-9 (duplicates)**: Given `{"city": "London"}` was created, when `{"city": "london"}`, then
  `{"city": "  LONDON "}` are posted, then each response is `409 FAVOURITE_ALREADY_EXISTS`
  (type `https://weather.example.com/problems/favourite-already-exists`). Its `detail` is fixed
  text that does not echo the city, and exactly one row still exists. In a second case,
  `New York` followed by `new   york` gives `201` then `409`. The provider is called exactly
  once in each case, for the first create only. A duplicate is detected before the provider is
  called (A-5).
- **AC-10**: Given client `A` has a favourite `London`, when client `B` posts `{"city": "London"}`,
  then the response is `201`. Duplicates are checked per client.
- **AC-11 (normalisation, unit test)**: The favourites duplicate key for a city equals the
  normalisation that `CacheKeys` uses for geocoding keys (NFC, strip, collapse Unicode whitespace
  runs to one space, lower-case with `Locale.ROOT`, NFC). Both use the same implementation, so
  they can't drift apart. `"New York"`, `"new   york"` and `" NEW YORK "` give the same key.
  `"São Paulo"` in NFC and in NFD (`a` + U+0303) give the same key. `"Sao Paulo"` and
  `"São Paulo"` give different keys.
- **AC-12 (concurrent duplicates)**: Given no favourites, when two `POST {"city": "London"}`
  requests for the same client are sent at the same time (e.g. released together by a
  `CountDownLatch`; 10 repetitions), then each repetition yields exactly one `201` and one
  `409 FAVOURITE_ALREADY_EXISTS`, never a `500`, and exactly one row.
- **AC-13 (limit)**: Given `weather.favourites.max-per-client=3` and the client has London, Paris
  and Tokyo, when `{"city": "Sydney"}` is posted, then the response is
  `409 FAVOURITES_LIMIT_REACHED` (type `.../favourites-limit-reached`), its `detail` contains
  `3`, the provider is not called for this request and 3 rows remain. Another client can still
  create Sydney (`201`). After the first client deletes Paris, its Sydney create gives `201`.
  When a city is both a duplicate and over the limit, the response is `409 FAVOURITE_ALREADY_EXISTS`.
- **AC-14 (configuration)**: `weather.favourites.max-per-client` is bound in `application.yml`
  from `${WEATHER_FAVOURITES_MAX_PER_CLIENT:20}`. The variable is documented (commented out) in
  `.env.example` and passed to the `app` service in `compose.yaml` as `${VAR:-20}`. When it is
  set to `0`, `-1`, `101` or `abc`, startup fails and the failure message names the property
  (unit test, no Docker). Valid range: 1..100.

### Client id (ownership, A-1)
- **AC-15**: For every favourites operation (create, list, get, rename, reorder, delete), when the
  `X-Client-Id` header is missing, empty, 65 characters long, or contains a character outside
  `[A-Za-z0-9._-]` (e.g. `a b`, `a/b`, `ü`), then the response is `400 VALIDATION_FAILED`.
  `errors` contains `{field "X-Client-Id", message "must match ^[A-Za-z0-9._-]{1,64}$"}`. No row is
  read or changed, and the provider is not called. A 64-character valid id is accepted. If a POST
  also has an invalid body, `errors` contains at least the `X-Client-Id` entry.
- **AC-16 (isolation)**: Given client `A` has a favourite with id `<idA>`, when client `B` calls
  the list endpoint, then `B` gets `{"items": []}`. When `B` calls get, rename or delete on
  `<idA>`, each response is `404 NOT_FOUND`, the same as for an id that does not exist. `A`'s row
  is unchanged. When `B` sends a reorder whose `ids` include `<idA>`, the response is
  `400 VALIDATION_FAILED` (AC-23).

### List: `GET /api/v1/favourites`
- **AC-17**: Given no favourites, when the list is requested, then the response is `200`
  `{"items": []}`.
- **AC-18**: Given London, Paris and Tokyo were created in that order, when the list is
  requested, then `items` holds the 3 favourites in that order. Each item equals the body
  returned by its create. `GET /api/v1/favourites?page=2&size=1` returns the same response
  (no pagination, unknown parameters are ignored).
- **AC-19 (no upstream dependency)**: Given favourites exist and the provider spy throws
  `WeatherProviderUnavailableException` for every call, then list, get, rename, reorder and delete
  all succeed with their normal status codes, and the provider is not called by any of them.

### Get: `GET /api/v1/favourites/{id}`
- **AC-20**: Given a created favourite, when it is fetched by `id`, then the response is `200`
  with the same body as its create response. A random UUID that does not exist gives
  `404 NOT_FOUND` with a `detail` that differs from the routing 404's ("No endpoint exists at
  this path."), e.g. "No favourite exists with this id.". An `id` of `not-a-uuid` gives
  `400 VALIDATION_FAILED` with `errors` `[{field "id", message "has an invalid value"}]`
  (the existing type-mismatch handling).

### Rename: `PATCH /api/v1/favourites/{id}`
- **AC-21**: Given a favourite created at `2026-11-15T10:00:00Z` and the clock moved to
  `2026-11-15T11:00:00Z`, when `{"label": "Work"}` is sent, then the response is `200` with
  `label "Work"`, `updatedAt "2026-11-15T11:00:00Z"`, and `createdAt`, `id`, `city` and `location`
  unchanged. The row matches, and a later get returns the same body. Then `{"label": null}` gives
  `200` with no `label` property. `{"label": "Work", "city": "Paris"}` changes only the label:
  other properties are ignored, and `city`/`location` can never change (A-7). The provider is
  not called.
- **AC-22**: When the PATCH body is `{}` (label key missing), the response is
  `400 VALIDATION_FAILED` with `errors` `[{field "label", message "must be present (null clears the label)"}]`.
  `{"label": "   "}` and a 51-character label give the same errors as in AC-4. A malformed body
  behaves as in AC-5, and an unknown or other client's `id` gives `404 NOT_FOUND`. The row is
  unchanged in every case.

### Reorder: `PUT /api/v1/favourites/order`
- **AC-23**: Given favourites with ids `L`, `P`, `T` (London, Paris, Tokyo, created in that order),
  when `{"ids": ["T", "L", "P"]}` is sent, then the response is `200` with the same shape as the
  list (`{"items": [...]}`) in order T, L, P. A later list returns that order, and each item's
  `createdAt` is unchanged. Each of the following gives `400 VALIDATION_FAILED` with an error on
  field `ids`, and the order stays unchanged: an id is missing (`["T", "L"]`), an unknown UUID is
  added, an id is listed twice, another client's id is included, an element is not a UUID, the
  `ids` key is missing or `null`, or `ids` is `[]` while favourites exist. Given no favourites,
  `{"ids": []}` gives `200 {"items": []}`.
- **AC-24**: Given the order T, L, P after AC-23, when Sydney is created, then the list order is
  T, L, P, Sydney: new favourites go at the end. When L is then deleted, the list order is
  T, P, Sydney.

### Delete: `DELETE /api/v1/favourites/{id}`
- **AC-25**: Given a favourite, when it is deleted, then the response is `204` with an empty body
  and an `X-Request-Id` header, and its row is gone. A later get on the id gives `404 NOT_FOUND`,
  and a second delete gives `404 NOT_FOUND`. A `not-a-uuid` id gives `400` as in AC-20.

### Contract, errors, CORS, logging
- **AC-26**: `PUT /api/v1/favourites` gives `405 METHOD_NOT_ALLOWED` with an `Allow` header of
  exactly `GET, POST` (in any order). `POST /api/v1/favourites/<uuid>` gives `405` with an `Allow`
  header that contains `GET`, `PATCH` and `DELETE`. Both bodies are Problem Details as today.
- **AC-27**: `ErrorCode` gains exactly two codes: `FAVOURITE_ALREADY_EXISTS` (409,
  `favourite-already-exists`) and `FAVOURITES_LIMIT_REACHED` (409, `favourites-limit-reached`).
  The existing six codes are unchanged. The `Problem.code` enum in `openapi-v1.json` lists all
  eight, and the existing unit test that compares the enum with `ErrorCode` passes.
- **AC-28**: `openapi-v1.json` documents every new operation, the `X-Client-Id` header parameter,
  the request and response schemas (e.g. `Favourite`, `FavouriteList`, `CreateFavouriteRequest`,
  `UpdateFavouriteRequest`, `ReorderFavouritesRequest`, with `Favourite.location` referencing the
  existing `Location` schema) and every documented error response. `OpenApiDriftIT` and
  `RealProviderOpenApiDriftIT` pass. A conformance IT checks the `201` create, `200` get, list,
  rename and reorder bodies against their schemas, and the `400`, `404 NOT_FOUND`,
  `404 LOCATION_NOT_FOUND`, `409` (both codes) and `503` favourites bodies against `Problem`.
  The `/api/v1/weather/*` path entries are unchanged. `info.description` no longer says
  "Read-only" or "GET and OPTIONS". The `FieldError.field` description covers headers and body
  properties as well as query parameters.
- **AC-29 (CORS)**: Given `WEATHER_API_CORS_ALLOWED_ORIGINS=http://ui.example`, when a preflight
  `OPTIONS /api/v1/favourites/<uuid>` from that origin sends `Access-Control-Request-Method` as
  each of `POST`, `PATCH`, `PUT` and `DELETE`, with `Access-Control-Request-Headers:
  content-type, x-client-id`, then the response is `200`. `Access-Control-Allow-Methods` contains
  the requested method, the allowed headers include both headers, and there is no
  `Access-Control-Allow-Credentials: true`. The same preflight from `http://evil.example` gives
  `403`. With an empty allow-list every origin is denied. The existing `CorsIT` and
  `CorsDefaultDenyIT` pass, though their method assertions may be updated to the new method list.
- **AC-30 (logging)**: Given `X-Request-Id: it-fav-30`, `X-Client-Id: cid-SECRET30`, city
  `London` and label `LBL-SECRET30`, when create, list, rename and delete are called, then every
  response carries `X-Request-Id: it-fav-30`, and each request produces exactly one INFO
  access-log line with `it-fav-30`. No captured log line at INFO or above contains
  `cid-SECRET30` or `LBL-SECRET30`.
- **AC-31 (regression)**: All existing unit tests and ITs pass. `/api/v1/weather/current` and
  `/forecast` responses, status codes and headers are unchanged.
- **AC-32 (real provider quota)**: Given the real provider pointed at `FakeOpenMeteo` (defaults:
  London geocoding) with caching enabled and an empty cache, when `{"city": "London"}` is posted,
  then the response is `201` with the fake's geocoding location. The fake receives exactly 1
  `GEOCODING` call and 0 `CURRENT`/`DAILY` calls. A following
  `GET /api/v1/weather/current?city=London` makes 0 further `GEOCODING` calls, because the
  geocoding cache entry is shared (A-4).

## Non-functional requirements
- **Latency**: list, get, rename, reorder and delete touch only Postgres, never the provider or
  Redis (AC-19). Create makes at most one provider location lookup, which is 1 upstream call on
  a geocoding cache miss and 0 on a hit (AC-32). It is bounded by the existing provider timeouts
  (ADR 0006).
- **Integrity**: duplicates are prevented by a database unique constraint, not only by an
  application check (AC-1, AC-12). Every create, rename, reorder and delete is a single
  transaction. A failed request leaves no partial change.
- **Security / privacy**: there is no authentication. `X-Client-Id` is an unguessable-by-convention
  partition key, **not** a security boundary: anyone who knows or guesses an id can read and
  change that list (A-1). Ids are UUIDs, so favourite ids are not enumerable. Cross-client access
  gives `404`, the same as a missing id (AC-16). Client ids and labels are never logged at INFO
  or above (AC-30). Problem bodies never contain SQL, exception messages or class names. A
  database failure gives `500 INTERNAL_ERROR` through the existing handler. All SQL is
  parameterised.
- **Size bounds**: at most `max-per-client` (default 20) favourites per client id. City ≤ 100,
  label ≤ 50 and client id ≤ 64 characters. The total number of client ids is not bounded (A-9).
- **Data retention**: favourites are kept until the client deletes them. There is no expiry or
  cleanup job (A-9). Nothing new is written to Redis by this feature beyond the existing geocoding
  cache entries (TTL'd, ADR 0007).
- **Dependencies**: no new dependency. Use the JDBC/Flyway/Postgres stack already on the classpath.
  The architect decides between `JdbcClient`/`JdbcTemplate` and Spring Data JDBC only if it is
  already present, and records the choice in design.md.

## Out of scope
- Authentication, user accounts, or binding favourites to an authenticated user (A-1).
- Weather for all favourites in one call (e.g. `GET /api/v1/favourites/weather`). The UI calls
  `/api/v1/weather/current?city=<favourite.city>` per item.
- Changing a favourite's city (delete and re-create instead), and refreshing the stored location
  if the provider's data changes later.
- Pagination, filtering, sorting options and search on the list.
- Merging or sharing lists across client ids, importing/exporting lists.
- Cleanup of inactive client ids, soft delete, audit history.
- An error code for 415 / 406 (ADR 0003 keeps Spring defaults).
- Detecting "different spellings, same place" as duplicates (e.g. `Londres` vs `London`, A-5).
- ETags / optimistic concurrency on rename and reorder (last write wins).
- Rate limiting of create calls.

## Open questions / assumptions
Each item needs a human decision on the PR. If none is given, the assumption stands. Items in
**bold** most need confirming.

- **A-1 (ownership without auth; human decision)**: favourites are scoped to an opaque,
  client-generated id in a required `X-Client-Id` header (same pattern as `X-Request-Id`). The
  UI generates a random UUID once per browser/device and stores it locally. Why this default:
  separate users of a shared deployment don't overwrite each other, ITs can isolate by client
  id, and a later auth feature can map an authenticated user to the same `client_id` column
  (or migrate it) without a v2. Cost: it is not security (see NFRs), and clearing browser storage
  "loses" the list. **Alternative: a single global list** (no header). It is simpler, but every
  caller shares and can edit one list, and adding owners later would mean a breaking change
  (a new required header) or a default-owner hack. If the human picks the global list, drop
  AC-10, AC-15 and AC-16, the client-id parts of AC-12, AC-13, AC-29 and AC-30, and make the
  unique constraint on the normalised key alone.
- **A-2 (validate on create)**: create resolves the city through the `WeatherProvider` and
  stores the resolved location (name, country, lat, lon) as a snapshot. Unknown cities are
  rejected (`404 LOCATION_NOT_FOUND`, AC-7). The upstream being down means no favourite can be
  created (`503`, AC-8). We don't save unvalidated entries for later validation. The other
  operations never need the provider (AC-19). With the stub (it profile) only the five stub
  cities can be saved, which is why AC-13 makes the limit configurable and sets it to 3.
- **A-3 (stored city vs canonical name)**: `city` is stored and returned as sent (stripped). The
  UI passes it to `/api/v1/weather/*`, which hits the same geocoding cache key. `location` is the
  canonical provider result, for display. Both are returned.
- **A-4 (provider port)**: the port has no location-only method today. `current(city)` would cost
  an extra `CURRENT` upstream call per create with the real provider. Proposal for the
  architect: add a location lookup to `WeatherProvider` (e.g. a default `locate(city)` that
  returns `current(city).location()`, overridden by the Open-Meteo adapter to do geocoding
  only, through `WeatherCache`), with the same failure contract. AC-32 pins the observable
  result.
- **A-5 (duplicate rule)**: a duplicate is the same client plus the same **normalised submitted
  city**, using the cache's normalisation (AC-11). It is checked before the provider call to save
  quota, and enforced by a unique constraint. Different inputs that resolve to the same place
  (`Londres`/`London`) are **not** duplicates (Out of scope). The alternative, deduplicating on
  the resolved location, would always need a provider call first. **Confirm.**
- **A-6 (limit)**: default 20 per client, configurable from 1 to 100. Exceeding it gives `409
  FAVOURITES_LIMIT_REACHED` (a state conflict, like a duplicate), not `422`/`400`. With the list
  bounded, it needs no pagination. Under concurrent creates the architect must keep the count
  ≤ limit (e.g. lock the client's rows or count inside the insert transaction). No AC tests
  concurrency at the limit. **Confirm 20 and 409.**
- **A-7 (update scope)**: "update" means rename (label only, `PATCH`) and reorder (whole-list
  `PUT .../order` with every id exactly once). The city can't be changed. A missing `label`
  key is `400`, and `null` clears it (AC-22). This needs a body binding that tells absent from
  null; the architect chooses how. Reordering by a full id list avoids gaps and conflicting
  per-item positions.
- **A-8 (new error codes)**: two codes, `FAVOURITE_ALREADY_EXISTS` and `FAVOURITES_LIMIT_REACHED`,
  both 409. A missing favourite reuses `NOT_FOUND` with a different `detail` instead of a new
  `FAVOURITE_NOT_FOUND`, keeping the code set small. The UI tells routing and resource 404s
  apart by path. The 409 bodies carry no extra properties (e.g. no `existingId`). Adding one
  would need a Problem schema change.
- **A-9 (retention)**: rows live until deleted. Abandoned client ids are kept forever, bounded at
  20 rows each. A cleanup policy is a later feature.
- **A-10 (malformed id and body)**: a non-UUID path id is `400 VALIDATION_FAILED` (field `id`),
  reusing the existing type-mismatch handler, rather than `404`. An unreadable JSON body is `400
  VALIDATION_FAILED` with one error on field `body` (AC-5). Today that exception is not mapped and
  would give `500`.
- **A-11 (CORS change)**: the favourites API needs `POST`, `PATCH`, `PUT` and `DELETE` (and the
  `Content-Type`/`X-Client-Id` request headers) in the CORS config. ADR 0003 decision 5 fixes
  `GET, OPTIONS`, so the architect must record the change in a new ADR that amends 0003. It
  stays deny-by-default with no credentials.
- **A-12 (names)**: paths (`/api/v1/favourites`, `/api/v1/favourites/order`), the header
  (`X-Client-Id`), property/env names, field names (`label`, `ids`, `createdAt`, `updatedAt`) and
  the table/column names are proposals. Note that `GET /api/v1/favourites/order` matches the
  `{id}` route and gives `400` (not a UUID). The architect may pick another reorder path (e.g.
  `PUT /api/v1/favourites` with `{"ids": [...]}`) to avoid this.
- **A-13 (timestamps)**: `createdAt` and `updatedAt` come from the service `Clock`, truncated to
  seconds, ISO-8601 UTC `Z` (ADR 0003). Reorder does not change `updatedAt`.
