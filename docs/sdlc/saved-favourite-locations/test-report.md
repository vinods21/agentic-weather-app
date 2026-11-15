# Saved favourite locations: Test report
Branch: feature/saved-favourite-locations @ 404e158 · Date: 2026-11-15 · Env: docker compose (postgres:17, redis:7)

ITs live in `src/integrationTest/java/com/example/weather/favourites/` unless a package is given.
Unit tests marked "dev" were written by the developer (steps 1-7). The rest are new in this phase.

| AC | Test(s) | Type | Result |
|----|---------|------|--------|
| AC-1 | `FavouritesSchemaIT#ac1_migrationApplied`, `#ac1_tableAndConstraintExist`, `#ac1_uniqueConstraintEnforced`; `MigrationFilesTest` (dev); `git diff origin/main -- …/V1__baseline.sql` is empty (checked by hand) | integration + unit | PASS |
| AC-2 | `CreateFavouriteIT#ac2_createReturnsResourceAndStoresRow`, `#ac2_edge_timestampsTruncatedToSeconds`; `FavouriteRequestEdgeCaseTest#responseSerialisation` | integration + unit | PASS |
| AC-3 | `CreateFavouriteIT#ac3_noLabel` (missing and `null`) | integration | PASS |
| AC-4 | `CreateFavouriteIT#ac4_validation` (all 6 table rows), `#ac4_hundredCharCityReachesProvider`, `#ac4_fiftyCharLabelAccepted`; `FavouriteRequestEdgeCaseTest#unicodeWhitespace*` | integration + unit | PASS |
| AC-5 | `FavouriteBodyErrorsIT#ac5_unreadableCreateBody` (4 bodies), `#ac5_edge_unreadableReorderBody` | integration | PASS |
| AC-6 | `CreateFavouriteIT#ac6_unsupportedMediaType` | integration | PASS |
| AC-7 | `CreateFavouriteIT#ac7_unknownCity` | integration | PASS |
| AC-8 | `CreateFavouriteIT#ac8_upstreamUnavailable` (503, `it-fav-8`, WARN line, then 201 after reset); `FavouriteServiceEdgeCaseTest#providerFailureTouchesNoTransaction` | integration + unit | PASS |
| AC-9 | `FavouriteDuplicatesIT#ac9_caseAndWhitespaceDuplicates`, `#ac9_collapsedWhitespaceDuplicate`, `#ac9_edge_duplicateWithOtherLabel`; `FavouriteServiceEdgeCaseTest#createUsesNormalisedKeyButStoresCityAsSent` | integration + unit | PASS |
| AC-10 | `FavouriteDuplicatesIT#ac10_duplicatesArePerClient` | integration | PASS |
| AC-11 | `CityNamesTest`, `CacheKeysNormalisationTest` (dev) | unit | PASS |
| AC-12 | `FavouriteDuplicatesIT#ac12_concurrentDuplicates` (25 repetitions), `#ac12_forcedRaceAfterPreCheck` (10 repetitions, both requests held in `locate` until both passed the pre-check) | integration | PASS |
| AC-13 | `FavouritesLimitIT#ac13_limitReached`, `#ac13_otherClientUnaffected`, `#ac13_deleteFreesASlot`, `#ac13_duplicateWinsOverLimit`, `#ac13_edge_concurrentCreatesAtTheLimit`; `FavouriteServiceEdgeCaseTest#limitBoundary` | integration + unit | PASS |
| AC-14 | `FavouritesPropertiesTest`, `FavouritesConfigFilesTest` (dev) | unit | PASS |
| AC-15 | `ClientIdIT#ac15_invalidClientId` (6 operations x missing/empty/65 chars/`a b`/`a/b`), `#ac15_nonAsciiClientId` (6 operations x `ü` as UTF-8 and ISO-8859-1, raw socket), `#ac15_sixtyFourCharsAccepted`, `#ac15_invalidHeaderAndMalformedBody`, `#ac15_edge_*`; `ClientIdTest` (dev) | integration + unit | PASS |
| AC-16 | `ClientIdIT#ac16_isolation`, `#ac16_edge_mixedReorder` | integration | PASS |
| AC-17 | `ListGetFavouritesIT#ac17_emptyList` | integration | PASS |
| AC-18 | `ListGetFavouritesIT#ac18_listInCreationOrder`, `#ac18_edge_orderWithEqualTimestamps` | integration | PASS |
| AC-19 | `FavouritesNoUpstreamIT#ac19_noUpstreamDependency`, `#ac19_edge_duplicateCheckedBeforeProvider` | integration | PASS |
| AC-20 | `ListGetFavouritesIT#ac20_getById`, `#ac20_unknownId`, `#ac20_notAUuid`, `#ac20_edge_upperCaseId` | integration | PASS |
| AC-21 | `RenameFavouriteIT#ac21_rename`, `#ac21_edge_labelStripped`; `FavouriteServiceEdgeCaseTest#renameNullClears` | integration + unit | PASS |
| AC-22 | `RenameFavouriteIT#ac22_invalidLabel`, `#ac22_unknownOrForeignId`, `#ac22_edge_notAUuid`; `FavouriteBodyErrorsIT#ac22_unreadableRenameBody` | integration | PASS |
| AC-23 | `ReorderFavouritesIT#ac23_reorder`, `#ac23_invalidReorder` (10 failure cases), `#ac23_emptyReorderWithNoFavourites`, `#ac23_edge_*`; `FavouriteServiceEdgeCaseTest#reorder*` | integration + unit | PASS |
| AC-24 | `ReorderFavouritesIT#ac24_appendAndDeleteKeepOrder`, `#ac24_edge_appendAfterDeletingLast` | integration | PASS |
| AC-25 | `DeleteFavouriteIT#ac25_delete`, `#ac25_notAUuid`, `#ac25_edge_recreateAfterDelete` | integration | PASS |
| AC-26 | `FavouritesRoutingIT#ac26_putOnCollection` (Allow exactly `GET, POST`), `#ac26_postOnItem`, `#ac26_edge_*` | integration | PASS |
| AC-27 | `OpenApiDocumentTest#problemCodeEnumEqualsErrorCodes`, `ApiExceptionHandlerTest#errorCodeSet` (dev); `api.OpenApiIT` (8 codes); 409 bodies in `FavouriteDuplicatesIT` / `FavouritesLimitIT` | unit + integration | PASS |
| AC-28 | `FavouritesContractConformanceIT` (201/200 bodies; 400, 404 NOT_FOUND, 404 LOCATION_NOT_FOUND, 409 FAVOURITE_ALREADY_EXISTS, 503), `FavouritesLimitIT#ac28_limitProblemConforms`; `api.OpenApiDriftIT`, `api.RealProviderOpenApiDriftIT`; `OpenApiDocumentTest` (dev) | integration + unit | PASS |
| AC-29 | `FavouritesCorsIT#ac29_allowedOriginPreflight` (POST/PATCH/PUT/DELETE), `#ac29_otherOriginRejected`, `#ac29_edge_*`; `api.CorsDefaultDenyIT#ac29_emptyAllowListDeniesFavouritesPreflight` (new case); `api.CorsIT`, `api.CorsDefaultDenyIT` (existing) | integration | PASS |
| AC-30 | `FavouritesLoggingIT#ac30_logging`, `#ac30_edge_errorsDoNotLogSecrets` | integration | PASS |
| AC-31 | full suites: `./gradlew test` (496) and `scripts/test-env.sh it` (394, including all 234 pre-existing ITs) | unit + integration | PASS |
| AC-32 | `FavouritesRealProviderIT#ac32_createUsesGeocodingOnlyAndSharesTheCache`, `#ac32_edge_createAfterWeatherRequestIsCacheHit`, `#ac32_edge_unknownCity` | integration | PASS |

## Summary
- Unit (`./gradlew test`): **496 passed / 0 failed**. 10 of them are new tester edge cases
  (`favourites.FavouriteServiceEdgeCaseTest` 6, `api.v1.FavouriteRequestEdgeCaseTest` 4).
- Integration (`scripts/test-env.sh it`): **394 passed / 0 failed**: 156 new favourites ITs in 16
  classes, 4 new `CorsDefaultDenyIT` cases, and 234 pre-existing ITs.
- All 32 ACs pass. No product code was changed.
- Contexts added: favourites base (max 20, pool 4), `FavouritesLimitIT` (max 3) and
  `FavouritesCorsIT` (origin `http://ui.example`, pool 3). `FavouritesRealProviderIT` reuses the
  `CachingIntegrationTestBase` context, and `FavouritesRoutingIT` reuses the default API context.
- Isolation: every test uses `it-fav-<uuid>` client ids (or a tracked fixed id) and deletes its
  rows in `@AfterEach`. The schema comes from Flyway in `it_<run-id>`, with no schema names in tests.
  Testcontainers is not used, and no new dependencies were added.

## Failures / defects found
**Product bugs: none.** All 32 ACs pass against the code as delivered.

**Test issues (fixed in the tests, not product bugs):**
- `ListGetFavouritesIT#ac20_notAUuid` first asserted that the whole Problem body doesn't contain
  `not-a-uuid`. Problem `instance` is the request path by design, so the assertion is now limited
  to `detail` and `errors`.
- The JDK `HttpClient` behind `TestRestTemplate` does not reject a non-ASCII header value. It
  silently sends `ü` as `?` (checked with a probe on JDK 25.0.1). A test through
  `TestRestTemplate` would pass without testing `ü`, so the `ü` case of AC-15 goes over a raw
  socket (`FavouritesIntegrationTestBase#rawRequest`). It is sent as both UTF-8 and ISO-8859-1
  bytes. Tomcat accepts the bytes, and the app answers `400` problem+json with the `X-Client-Id`
  error.

**Observations (design risks now confirmed):**
- AC-26: Spring lists exactly `GET, POST` in `Allow` for `PUT /api/v1/favourites`, with no
  implicit `HEAD`/`OPTIONS`. So the "exactly" in the AC holds.
- `GET /api/v1/favourites/order` gives `400` on field `id`, as documented (A-12).
- `{"ids": "abc"}` and `{"ids": {...}}` are unreadable bodies (`400`, field `body`). Jackson does
  not coerce a scalar into a list.

## Not covered / risks
- **AC-14 startup failure** is covered only by the developer's `Binder` unit tests. No test starts
  a full application with an invalid `WEATHER_FAVOURITES_MAX_PER_CLIENT`.
- **AC-6** asserts only the `415` status and that no row was written. The body format (Spring's
  default) is not asserted, by design (ADR 0003).
- **AC-30** is checked at the default log level (INFO) on captured stdout only. DEBUG/TRACE
  output (e.g. Spring's request logging, if someone enables it) is not covered.
- **Concurrency**: AC-12 uses 25 plain plus 10 forced-race repetitions. The forced race proves
  that the lock and re-check path works. The `DuplicateKeyException` -> 409 fallback can't be
  reached through the API while the lock works, so only `FavouriteServiceTest` (dev) covers it.
  The limit under concurrency (A-6) is covered by one edge test (`ac13_edge_*`). No AC requires it.
- **Advisory-lock hash collisions** across concurrent IT runs on the same database (design risk)
  are not tested. They would only serialise requests briefly.
- **Jackson scalar coercion** (`{"city": 123}` binds as `"123"`) is a known, accepted design risk
  and is not tested.
- **Database failure -> 500** on favourites endpoints is not tested at IT level. Only the existing
  generic 500 handler tests cover it.
- **A-1 (ownership via `X-Client-Id`)** still needs a human decision. The tests pin the current
  behaviour (AC-15/16). If a single global list is chosen, `ClientIdIT` and parts of the other ITs
  change.
