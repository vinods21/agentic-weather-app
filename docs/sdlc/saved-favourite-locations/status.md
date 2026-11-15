# saved-favourite-locations: SDLC status
Request: Saved favourite locations: CRUD API for saved cities, stored in Postgres via a Flyway migration.
Branch: feature/saved-favourite-locations · PR: (none yet)
## Phases
- [x] pm  - [x] architect  - [x] developer  - [x] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
## Log
- 2026-11-15 pm: requirements.md written (AC-1..AC-32)
- 2026-11-15 architect: design.md written; ADRs 0008 (JDBC persistence), 0009 (X-Client-Id), 0010 (write conventions + CORS, amends 0003), 0011 (WeatherProvider#locate); overview updated
- 2026-11-15 developer: design steps 1-7 implemented in 7 commits (CityNames, WeatherProvider#locate, V2 migration + max-per-client, service/JDBC repository, error codes/handlers, X-Client-Id + CORS, controller + OpenAPI); `./gradlew build` green; `scripts/test-env.sh it` green (234 existing ITs); AC-26 checked: Spring's 405 `Allow` lists only the mapped methods (`GET,POST` on the collection, `DELETE,GET,PATCH` on `/{id}`), no implicit HEAD/OPTIONS; AC-mapped ITs (design steps 8-9) left to the tester
- 2026-11-15 tester: design steps 8-9 done (FavouritesIntegrationTestBase + 16 favourites IT classes, CorsDefaultDenyIT case, 10 unit edge cases); `./gradlew test` 496/0, `scripts/test-env.sh it` 394/0; AC-1..AC-32 all PASS, no product defects; test-report.md written

## Deviations from design
- `FavouritesProperties.maxPerClient()` returns `Integer`, not `int`: a record component accessor can't change the component type. It is never null after construction (null -> 20).
- `UpdateFavouriteRequest` also has a JavaBeans `getLabel()`. Spring's `SpringValidatorAdapter` reads the rejected value of a class-level violation (`@LabelPresent` on property `label`) through the bean wrapper; without a readable property that is a `NotReadablePropertyException` (500).
- Reorder `ids` elements must be canonical UUID text (8-4-4-4-12 hex, any case) before `UUID.fromString`, because `UUID.fromString` also accepts forms like `1-1-1-1-1`. Same message (`must contain only favourite ids (UUIDs)`).
- The V2 header comment says "per-run schema" instead of `it_<id>`: AC-1 and `MigrationFilesTest` forbid the text `it_` anywhere in V2.
- Existing-test adjustment beyond `CorsIT#ac18_edge` (TRACE): `OpenApiIT.ERROR_CODES` / `ac20_problemCodeEnum` asserted exactly six codes; updated to the eight AC-27 requires.
- The `InvalidClientIdException` handler landed in the step-6 commit (with the class), not step 5.
- `ClientId` validates in its compact constructor; `parse` delegates, so no unvalidated `ClientId` can exist.
- The delegation check `CacheKeys.normaliseCity == CityNames.normalise` lives in `cache.CacheKeysNormalisationTest` (same package, as `normaliseCity` is package-private); `CityNamesTest` covers the AC-11 cases.
- Spec notes: a body-less `UnsupportedMediaType` (415) response component is referenced by POST/PATCH/PUT; `FavouriteId` is a path-level parameter of `/api/v1/favourites/{id}`.
