# Redis caching of weather provider responses: Design
Requirements: ./requirements.md · ADRs: 0001, 0003, 0005, 0006, 0007 (new)

## Summary
The Open-Meteo adapter caches three kinds of entry in Redis:
- **geocoding**: normalised city → location, or "not found";
- **current conditions**: per location, stored with the instant they were fetched;
- **daily forecast**: per location + UTC start date + day count.

The adapter calls an explicit `cache.WeatherCache` component (no `@Cacheable`). It sits on a
tiny `CacheStore` port, implemented on Boot's `StringRedisTemplate`. Values are versioned JSON
mapped to the app's own DTO records and validated on every read.

Every cache failure is caught and logged as one sanitised WARN line. A failed read is treated
as a miss and a failed write is skipped, so the request still succeeds (fail open). After the
first Redis failure, a provider call skips its remaining cache operations, so a dead Redis
costs at most one Redis timeout per request. Boot's global Redis timeouts drop from 60 s to
500 ms (`REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`).

`observedAt` stays owned by the service. `WeatherProvider` gains a default method
`currentAt(city, now)`. The service passes its clock time, and a cache hit returns the time
that was stored with the entry (A-8).

The stub is never cached, and `weather.cache.enabled=false` swaps in a no-op cache.
`/api/v1`, `openapi-v1.json`, the DTOs, `ApiExceptionHandler`, the database and the build file
do not change, and no dependency is added. Integration tests break only the cache's Redis
connection. They do this with a test-only `@Primary CacheStore` (closed port / silent socket)
or a `@MockitoSpyBean CacheStore`. The context's own `StringRedisTemplate` stays on the compose
Redis, so `IntegrationTestBase` cleanup keeps working (A-10, ADR 0007 §11).

## AC mapping
| AC | Component(s) | How it is satisfied |
|----|--------------|---------------------|
| AC-1 | `application.yml`, `WeatherCacheProperties`, `.env.example`, `compose.yaml` | Seven placeholders, with the names proposed in the requirements kept (table under Data model). They are commented out in `.env.example` and passed through in `compose.yaml` as `${VAR:-default}`. `RedisTimeoutConfigIT` checks that Boot's `LettuceConnectionFactory` really uses 500 ms. |
| AC-2 | `WeatherCacheProperties` compact constructor + Boot binder | `0s` / `-5m` → `IllegalArgumentException("weather.cache.<name>-ttl must be a positive duration (env WEATHER_CACHE_<NAME>_TTL)")`. `abc` → the binder's conversion failure, which names `weather.cache.<name>-ttl`. Unit test with `Binder` + `MapConfigurationPropertySource` (no context, no Docker). |
| AC-3 | `WeatherProviderConfiguration` | The `stub` branch returns `new StubWeatherProvider()` and never receives the `WeatherCache`. No code path reaches Redis. |
| AC-4 | `WeatherCacheConfiguration` | `enabled=false` → bean `WeatherCache.disabled()`: every get is a miss, every put is a no-op, and no `CacheStore` call is made. |
| AC-5 | `OpenMeteoWeatherProvider#currentAt`, `RedisWeatherCache` | The 1st request misses, calls upstream and writes the geocoding and current entries. The 2nd request hits both, so 0 upstream calls. `observedAt` comes from the entry, so the bodies are byte-equal. |
| AC-6 | same + `WeatherService` | Entries are metric. `WeatherService` converts per request. Units are never passed to the adapter, so they can't reach a key. |
| AC-7 | `CacheKeys#normaliseCity` / `#geocoding` | NFC → strip → collapse `\s+` (`UNICODE_CHARACTER_CLASS`) to `" "` → `toLowerCase(Locale.ROOT)` → NFC, then SHA-256. The upstream still receives the city as sent by the first requester (`geocode(city)` is unchanged). |
| AC-8 | adapter `geocode` used by both `currentAt` and `forecast` | One geocoding key per city, shared by both endpoints. The forecast miss makes only the `DAILY` call. |
| AC-9 | `CacheKeys#forecast(location, startDate, days)` | `startDate` is the service's UTC date (unchanged `WeatherService`). `days=5` and the next day both build a different key, so each misses once. |
| AC-10 | `CacheKeys` | City → 64 lowercase hex chars. Every part must match `[A-Za-z0-9._-]+` (`IllegalArgumentException` otherwise; a programming error). Coordinates are `BigDecimal.toPlainString()`. |
| AC-11 | `CacheCodec` | Doubles are written by Jackson as shortest round-trip text and read back as `double`, so they are exact. Strings are UTF-8 JSON. `condition.description` is stored. |
| AC-12 | `RedisWeatherCache` + `CacheStore#set(key, value, ttl)` | Every write is `SET key value PX <ttl>`, with the TTL from `WeatherCacheProperties` per kind (found location → `geocodingTtl`, not found → `notFoundTtl`). No write path without a TTL exists. |
| AC-13 | same | The current/forecast entry expires on its own TTL. The geocoding entry (7d) is still a hit. |
| AC-14 | adapter `geocode` | A `LocationNotFoundException` from the mapper → `putNotFound(city)` → rethrow. A not-found hit → `throw new LocationNotFoundException(city)` with **this** request's city, so `detail` echoes it. A missing `country_code` is already a `LocationNotFoundException` in the mapper, so it is cached the same way. |
| AC-15 | `notFoundTtl` | The not-found entry expires, so the next request is a miss and calls geocoding again. |
| AC-16 | adapter | `WeatherProviderUnavailableException` propagates before any `put`, so nothing is written. |
| AC-17 | adapter order | Geocoding is put **before** the forecast call. A failed forecast call throws before `putCurrent`/`putForecast`. |
| AC-18 | `RedisCacheStore`, `WeatherCacheConfiguration`, Boot defaults | Nothing touches Redis at construction (no PING; Lettuce `eagerInitialization` stays false). Readiness keeps Boot's default group (`readinessState` only). |
| AC-19 | `RedisWeatherCache.Session` short circuit + 500 ms timeouts | The 1st op fails fast (refused, at most `connect-timeout`). The rest of the call skips the cache, so all upstream calls are made. Results and errors are exactly the uncached ones. |
| AC-20 | `RedisWeatherCache#warn` | Exactly 1 WARN per request in the unreachable case (≤ 4 always holds, since a session has at most 4 ops). The line format follows ADR 0007 §9. No throwable is passed to the logger, so no stack trace. |
| AC-21 | same + `spring.data.redis.timeout/connect-timeout=250ms` | The silent server accepts TCP. Lettuce's handshake/command times out at about 250 ms on the 1st op, and the call then bypasses the cache. Total time is about 250–500 ms plus the fake's latency. |
| AC-22 | `RedisWeatherCache` catch set | A `DataAccessException` from `CacheStore#get` → WARN `op=get reason=unavailable` → miss. From `#set` → WARN `op=put` → ignored. |
| AC-23 | `CacheCodec` validation, WRONGTYPE classification | `not-json`, `{}` and `"v":0` → `JacksonException` / `UnreadableCacheEntryException`. A hash → Redis `WRONGTYPE` → `reason=unreadable`. This does not trip the short circuit, so the miss is followed by `SET … PX`, which overwrites any type. The next request hits. |
| AC-24 | `WeatherProvider#currentAt`, `ObservedConditions`, `CurrentEntry.fetchedAt` | A miss stores `now` (service clock, truncated to seconds). A hit returns the stored instant. |
| AC-25 | no contract change | No controller/DTO/spec/header change. `RealProviderCurrentIT#ac7` only gains a cache clear between its two requests (setup). All other existing tests compile and pass unchanged (the port change is a default method). |

## Components & packages
All under `com.example.weather`.

### `cache` (new / changed)
| Class | Change | Responsibility |
|-------|--------|----------------|
| `cache.CacheKeys` | changed | Keeps `key(...)` and `pattern()`. `key(...)` now also rejects any stripped part that doesn't match `[A-Za-z0-9._-]+`, which existing callers (`probe`, `forecast`, `berlin`) already satisfy. New methods: `geocoding(String city)` → `key("geocoding", "v1", sha256Hex(normaliseCity(city)))`; `current(Location l)` → `key("current", "v1", plain(lat), plain(lon))`; `forecast(Location l, LocalDate start, int days)` → `key("forecast", "v1", plain(lat), plain(lon), start.toString(), Integer.toString(days))`; `static String normaliseCity(String)` (AC-7 rules; package-visible for tests); `static String plain(double)` = `BigDecimal.valueOf(v).toPlainString()` (the same text the adapter sends upstream). SHA-256 via `MessageDigest` + `HexFormat.of()` (JDK). |
| `cache.WeatherCacheProperties` | **new** | `@ConfigurationProperties("weather.cache") record WeatherCacheProperties(Boolean enabled, Duration geocodingTtl, Duration notFoundTtl, Duration currentTtl, Duration forecastTtl)`. It coexists with `CacheKeys` on the same prefix: each binds only its own components. A null `enabled` → `true`. Null TTLs → defaults 7d/1h/10m/1h. A zero/negative TTL → IAE naming the property and env var (the value is not echoed). Accessor `boolean isEnabled()`. |
| `cache.CacheStore` | **new** | Port: `String get(String key)` (null = absent) and `void set(String key, String value, Duration ttl)`. Implementations may throw `DataAccessException`. |
| `cache.RedisCacheStore` | **new** | `CacheStore` on `StringRedisTemplate`: `opsForValue().get(key)`, `opsForValue().set(key, value, ttl)` (Spring Data sends `SET … PX`). No logic and no logging. Public constructor `(StringRedisTemplate)` so tests can build it on their own connection. |
| `cache.WeatherCache` | **new** | Interface: `Session session()`, `static WeatherCache disabled()`. Nested `interface Session` with `Optional<GeocodingEntry> geocoding(String city)`, `void putLocation(String city, Location l)`, `void putNotFound(String city)`, `Optional<CurrentEntry> current(Location l)`, `void putCurrent(Location l, CurrentConditions c, Instant fetchedAt)`, `Optional<List<DailyForecast>> forecast(Location l, LocalDate start, int days)`, `void putForecast(Location l, LocalDate start, int days, List<DailyForecast> forecastDays)`. |
| `cache.GeocodingEntry` | **new** | `sealed interface GeocodingEntry` with `record Found(Location location)` and `record NotFound()`. |
| `cache.CurrentEntry` | **new** | `record CurrentEntry(CurrentConditions conditions, Instant fetchedAt)` (domain values, metric). |
| `cache.RedisWeatherCache` | **new** | `WeatherCache` implementation: `(CacheStore, CacheKeys, WeatherCacheProperties, CacheCodec)`. `session()` returns a new, non-thread-shared `Session` with a `boolean bypass` flag. For each op: if `bypass` → log at DEBUG `skipped` and return empty / no-op. Otherwise call the store and decode, applying the catch set (Error handling). Logs `Cache operation failed …` WARN lines. Hits and misses go to DEBUG (`cache=<name> outcome=hit|miss`, never the key). |
| `cache.CacheCodec` | **new** (package-private) | Dedicated `JsonMapper` (`FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_NULL_FOR_PRIMITIVES` on; no modules, no default typing). Encodes and decodes the three value formats (Data model) through package-private DTO records with boxed/`String` fields only. `decode*` validates and throws `UnreadableCacheEntryException(reason)` (a fixed reason text, never value content). Forecast decode also checks `days.size() == days` and `date[i] == start + i`. Current/forecast decode is given the geocoded `Location` to build `CurrentConditions` / days. |
| `cache.UnreadableCacheEntryException` | **new** (package-private) | `RuntimeException` with a fixed reason and no cause. |
| `cache.WeatherCacheConfiguration` | **new** | `@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties(WeatherCacheProperties.class)`. `@Bean CacheStore cacheStore(StringRedisTemplate)` → `RedisCacheStore`. `@Bean WeatherCache weatherCache(WeatherCacheProperties p, CacheKeys keys, ObjectProvider<CacheStore> store)` → `p.isEnabled() ? new RedisWeatherCache(store.getObject(), keys, p, new CacheCodec()) : WeatherCache.disabled()`. `getObject()` honours `@Primary`, which ITs rely on. |

### `weather` / `provider` (changed)
| Class | Change | Responsibility |
|-------|--------|----------------|
| `weather.ObservedConditions` | **new** | `record ObservedConditions(CurrentConditions conditions, Instant observedAt)`. |
| `provider.WeatherProvider` | changed | Adds `default ObservedConditions currentAt(String city, Instant now) { return new ObservedConditions(current(city), now); }`. Javadoc: `now` is the service-clock time truncated to seconds. The result's `observedAt` is when the conditions were fetched upstream: `now`, unless an adapter serves them from a cache (ADR 0007 §3). The failure contract is unchanged. |
| `weather.WeatherService` | changed | `current`: `Instant now = Instant.now(clock).truncatedTo(SECONDS); ObservedConditions o = provider.currentAt(city, now);`, then `o.observedAt()` and `o.conditions()` are used exactly as today. `forecast` is unchanged. |
| `provider.stub.StubWeatherProvider` | unchanged | Inherits the default `currentAt`, so it is never cached. `ProviderFailureIT`'s `doThrow(...).when(provider).current(anyString())` still applies, because the spy's default method calls `current` on the spy. |
| `provider.WeatherProviderConfiguration` | changed | Its factory takes an additional `WeatherCache weatherCache` parameter and passes it only to `OpenMeteoWeatherProvider.create(properties, builder, objectMapper, weatherCache)`. |
| `provider.openmeteo.OpenMeteoWeatherProvider` | changed | Adds a `WeatherCache cache` field. The existing 5-arg package-private constructor delegates with `WeatherCache.disabled()`, so existing unit tests are untouched, and a new 6-arg constructor takes the cache. `create(...)` takes the cache. Flow (each public call opens **one** `Session s`): **`geocode(s, city)`**: `s.geocoding(city)` → `Found` → location; `NotFound` → `throw new LocationNotFoundException(city)`; miss → upstream call as today; on success `s.putLocation(city, loc)`; on `LocationNotFoundException` `s.putNotFound(city)` and rethrow; `WeatherProviderUnavailableException` propagates with no put. **`currentAt(city, now)`**: `loc = geocode(s, city)`; `s.current(loc)` hit → `ObservedConditions(entry.conditions(), entry.fetchedAt())`; miss → upstream current call → `s.putCurrent(loc, c, now)` → `ObservedConditions(c, now)`. **`forecast(city, start, days)`**: days check first (unchanged), then `geocode`, then `s.forecast(loc, start, days)` hit → `new LocationForecast(loc, days)`; miss → upstream daily call → `s.putForecast(...)`. **`current(city)`** (only reachable by direct calls, e.g. `RealProviderFailureIT`): geocoding through the cache, conditions always fetched, because without `now` there is no fetch instant to store. On a hit, the location always comes from the geocoding step: current/forecast entries don't store a location. |

### Test support (integration, new / changed)
| Class | Change | Responsibility |
|-------|--------|----------------|
| `IntegrationTestBase` | changed | `deleteRunRedisKeys()` becomes `protected` (still `@AfterEach`) so subclasses can clear the cache mid-test. No other change. |
| `support.MutableClock` | **new** | `Clock` in UTC with `set(Instant)`; thread-safe (`AtomicReference`). |
| `support.CachingIntegrationTestBase` | **new** | `extends RealProviderIntegrationTestBase`. `@TestPropertySource` sets `weather.cache.enabled=true` and the four TTLs explicitly to `7d/1h/10m/1h` (A-13). `@TestBean Clock clock` → one static `MutableClock`. `@BeforeEach` sets the clock to `2026-11-15T10:00:00Z` and calls `deleteRunRedisKeys()`. Helpers: `runKeys()` (SCAN of `cacheKeys.pattern()`), `pttl(key)`. All subclasses share one context. |
| `support.IsolatedCacheStore` | **new** | Test `CacheStore` + `DisposableBean`. It builds a private `LettuceConnectionFactory` (`RedisStandaloneConfiguration("127.0.0.1", port)`, `LettuceClientConfiguration` with `commandTimeout = redisProperties.getTimeout()` and `ClientOptions`/`SocketOptions.connectTimeout = redisProperties.getConnectTimeout()`), calls `afterPropertiesSet()` and `start()`, and delegates to `new RedisCacheStore(new StringRedisTemplate(factory))`. It is **never registered as a `RedisConnectionFactory` or `StringRedisTemplate` bean**, because Boot's Redis auto-configuration would back off on those types. |
| `support.SilentRedisServer` | **new** | JVM-wide `ServerSocket(0, 50, 127.0.0.1)` with a daemon thread that accepts connections and holds them open without reading or writing. It exposes `port()`. |

## API
No change. Paths, parameters, bodies, status codes, the six error codes, headers and
`openapi-v1.json` stay byte-identical. A cached response is produced by the same
`WeatherService` → controller → DTO path, so it is indistinguishable from a fresh one. The only
difference is `observedAt`, which is the fetch time (A-8).

## Data model
**PostgreSQL**: no change, no Flyway migration.

**Configuration** (final names; the requirements' proposals are kept):
| Property | Env var | Default | Notes |
|---|---|---|---|
| `weather.cache.enabled` | `WEATHER_CACHE_ENABLED` | `true` | `false` → no Redis access by the app's code |
| `weather.cache.geocoding-ttl` | `WEATHER_CACHE_GEOCODING_TTL` | `7d` | found locations |
| `weather.cache.not-found-ttl` | `WEATHER_CACHE_NOT_FOUND_TTL` | `1h` | negative geocoding entries |
| `weather.cache.current-ttl` | `WEATHER_CACHE_CURRENT_TTL` | `10m` | |
| `weather.cache.forecast-ttl` | `WEATHER_CACHE_FORECAST_TTL` | `1h` | |
| `spring.data.redis.timeout` | `REDIS_TIMEOUT` | `500ms` | Lettuce command timeout (global, A-12) |
| `spring.data.redis.connect-timeout` | `REDIS_CONNECT_TIMEOUT` | `500ms` | Lettuce connect/handshake timeout (global) |

`application-it.yml` is unchanged. The new properties come from `application.yml`, and ITs that
depend on a value set it with `@TestPropertySource`. In `.env.example`, each variable gets a
comment line and a commented-out `# VAR=default`, under a "Weather cache (ADR 0007)" header.
`compose.yaml` passes each one to `app` as `VAR: ${VAR:-default}`.

**Redis keys** (all through `CacheKeys`; `<prefix>` = `weather.cache.key-prefix`, `it:<id>` in ITs):
| Kind | Key | TTL |
|---|---|---|
| geocoding (found / not found) | `<prefix>:geocoding:v1:<sha256hex(normaliseCity(city))>` | `geocoding-ttl` / `not-found-ttl` |
| current | `<prefix>:current:v1:<lat>:<lon>` e.g. `weather:current:v1:51.50853:-0.12574` | `current-ttl` |
| forecast | `<prefix>:forecast:v1:<lat>:<lon>:<yyyy-MM-dd>:<days>` | `forecast-ttl` |

**Values** (UTF-8 JSON strings; `v` must equal `1`, and every listed field is required unless marked):
```json
{"v":1,"found":true,"name":"London","country":"GB","latitude":51.50853,"longitude":-0.12574}
{"v":1,"found":false}
{"v":1,"fetchedAt":"2026-11-15T10:00:00Z","temperature":11.2,"feelsLike":9.8,"humidity":81,
 "windSpeed":4.1,"windDirection":230,"condition":{"code":"CLOUDY","description":"Overcast"}}
{"v":1,"days":[{"date":"2026-11-15","minTemperature":6.0,"maxTemperature":12.5,
 "precipitationProbability":40,"condition":{"code":"RAIN","description":"Slight rain"}}]}
```
Read validation:
- `found` is a boolean. For a found entry, `name` is non-blank, `country` matches `^[A-Z]{2}$`,
  and lat/lon are finite and in range.
- Numbers are finite, humidity and precipitation are in 0..100, and wind direction is in 0..359.
- `condition.code` is a `ConditionCode` name and `description` is non-blank.
- `fetchedAt` parses with `Instant.parse`, and `date` with `LocalDate.parse`.
- The forecast has exactly `days` entries with consecutive dates from `start`.

Any violation → `UnreadableCacheEntryException`. Unknown extra fields are ignored.

## External integrations
- **Redis 7** (compose `redis`), via Boot's auto-configured Lettuce `LettuceConnectionFactory`
  (shared native connection, lazy init). Command timeout `REDIS_TIMEOUT` (500 ms) and
  connect/handshake timeout `REDIS_CONNECT_TIMEOUT` (500 ms). No retries. Lettuce's
  auto-reconnect stays on, and commands issued while disconnected wait at most the command
  timeout.
- **Failure behaviour**:
  - Redis unreachable, timing out or erroring → miss / skipped write and one WARN, after
    which the rest of that provider call bypasses Redis.
  - Readiness is unaffected. `/actuator/health` (aggregate) reports Redis `DOWN` as today, now
    within 500 ms instead of up to 60 s.
- **Open-Meteo**: request formats, timeouts and failure translation are unchanged (ADR 0006). The
  cache only removes calls.

## Error handling
Catch set in `RedisWeatherCache` (ADR 0007 §7):
| Where | Caught | Classification | Effect |
|---|---|---|---|
| `store.get` | `DataAccessException`, `io.lettuce.core.RedisException`, where the cause chain holds a Lettuce `RedisCommandExecutionException` whose message starts with `WRONGTYPE` | `unreadable` | miss, WARN, session stays active (the put rewrites the key) |
| `store.get` | the same types, any other cause | `unavailable` | miss, WARN, `bypass = true` |
| `codec.decode*` | `com.fasterxml.jackson.core.JacksonException`, `UnreadableCacheEntryException` | `unreadable` | miss, WARN |
| `store.set` | `DataAccessException`, `io.lettuce.core.RedisException` | `unavailable` | write skipped, WARN, `bypass = true` |
| anywhere | any other `RuntimeException` / `Error` | not caught | a bug → 500 `INTERNAL_ERROR`, as today |

`codec.encode*` failing (`JsonProcessingException` on our own records) is a bug. It is wrapped in
`IllegalStateException` and not caught.

WARN format (one line per failed op, no throwable argument):
`Cache operation failed op=get cache=geocoding reason=unavailable exception=org.springframework.data.redis.RedisConnectionFailureException cause=io.netty.channel.AbstractChannel$AnnotatedConnectException`.
`exception` is the caught class and `cause` is `NestedExceptionUtils.getMostSpecificCause(e)`'s
class. The request id is added by the log pattern. The line never contains the exception
message, the city, the key or the value.

The API-visible outcome is always the uncached one: 200, 404 `LOCATION_NOT_FOUND` (the city as
sent in this request) or 503 `UPSTREAM_UNAVAILABLE`.

## Security considerations
- **Key injection / globbing**: cities are only ever hashed (SHA-256 hex), and `CacheKeys`
  rejects any part outside `[A-Za-z0-9._-]`. Raw input can't add `:` segments or `*?[]`
  (AC-10).
- **Privacy**: Redis holds no city as typed by a user, only digests in keys and the upstream's
  canonical names and coordinates in values. Logs never contain the city, the key or a value
  (AC-20). Every key has a TTL: at most 7 days by default.
- **Deserialisation**: a dedicated mapper reads into concrete DTO records only, with no default
  typing, no `@JsonTypeInfo` and no Java serialisation. Strict validation means a tampered entry
  is either well-formed weather data or a miss (AC-23). Residual risk: someone with write access
  to Redis can serve plausible fake values. Redis auth/TLS is out of scope (as stated in the
  requirements).
- **Availability**: bounded timeouts plus the per-call short circuit mean Redis can't add more
  than about one timeout per request. No startup or readiness dependency on Redis.

## Test strategy
Unit tests live in `src/test` (no Spring context, no Docker). Integration tests live in
`src/integrationTest` and use the compose Redis plus `FakeOpenMeteo`, through profile `it`
(ADR 0001). They extend `IntegrationTestBase`, mostly via `CachingIntegrationTestBase`.
Testcontainers is not used, and no test starts or stops a container. Every test is tagged
`@DisplayName("AC-n: …")`.

### Unit tests
| Test | ACs | Notes |
|---|---|---|
| `cache.WeatherCachePropertiesTest` | AC-1, AC-2 | Defaults. A parameterized test over the 4 TTL properties × {`0s`, `-5m`, `abc`} binds with `new Binder(new MapConfigurationPropertySource(map)).bind("weather.cache", WeatherCacheProperties.class)`. It asserts that a `BindException` is thrown and that its message or root cause contains the property name. |
| `cache.CacheConfigFilesTest` | AC-1 | Same style as `ProviderConfigFilesTest`: `application.yml` has exact placeholders (e.g. `${WEATHER_CACHE_GEOCODING_TTL:7d}`, `${REDIS_TIMEOUT:500ms}`), `.env.example` has `# VAR=` lines and no live `VAR=` lines, and `compose.yaml` passes `${VAR:-…}` to `app`. |
| `cache.CacheKeysTest` (extended) | AC-7, AC-10 | Normalisation pairs (`London`/`LONDON`, `New York`/`new   york`, NFC/NFD `São Paulo`, tabs/NBSP), `Sao Paulo` ≠ `São Paulo`, 6 special-char cities give distinct keys that all match `^weather:[A-Za-z0-9:._-]+$`, coordinates `-23.5475`/`-46.63611` appear verbatim, an invalid part is rejected, and existing behaviour still works. |
| `cache.RedisWeatherCacheTest` | AC-11, 12, 14, 20, 22, 23 | Uses an in-test `InMemoryCacheStore` (map + recorded TTLs, programmable throw on get/set). Checks: round trips for each kind (exact doubles, non-ASCII); TTL per kind; not-found entry; each unreadable form (`not-json`, `{}`, `"v":0`, a wrong field type, a short forecast) → empty + `reason=unreadable`; a WRONGTYPE-shaped `InvalidDataAccessApiUsageException(…, new RedisCommandExecutionException("WRONGTYPE …"))` → unreadable and no bypass; `RedisConnectionFailureException` on get → empty, WARN, and later ops skipped (store not called again); on set → no throw; WARN text has no key, city or value (`OutputCaptureExtension`). |
| `cache.CacheCodecTest` | AC-11, AC-23 | Exact JSON shape (`"v":1`), rejection cases, unknown fields tolerated. |
| `provider.openmeteo.OpenMeteoWeatherProviderTest` (new cases) | AC-5, 8, 14, 16, 17, 24 | 6-arg constructor with `RedisWeatherCache` over `InMemoryCacheStore` and `MockRestServiceServer`: a 2nd `currentAt` makes no request; a hit returns the stored `fetchedAt`; not found is cached and rethrown with the second caller's city; 5xx/invalid geocoding writes nothing; a current failure leaves only the geocoding key. Existing cases are unchanged (5-arg constructor). |
| `weather.WeatherServiceTest` (new case) | AC-24 | A provider whose `currentAt` returns an older `observedAt` → `CurrentWeather.observedAt` is that instant. Existing cases are unchanged (the stub uses the default method). |

### Integration tests (`src/integrationTest/java/com/example/weather/cache/`)
| IT | Base / context | ACs |
|---|---|---|
| `StubNotCachedIT` | `ApiIntegrationTestBase` (default stub context) | AC-3: the requests from the AC are sent, then `runKeys()` is empty. |
| `CacheDisabledIT` | `RealProviderIntegrationTestBase` + `@TestPropertySource(weather.cache.enabled=false)` | AC-4 |
| `CacheHitIT` | `CachingIntegrationTestBase` | AC-5 (also: the miss and hit responses have the same header-name set, and no `X-Cache`/`Age`; AC-25), AC-6, AC-7 (both cases; the second stubs geocoding to `LONDON_GEOCODING` for any name), AC-8, AC-9 (via `MutableClock`), AC-11, AC-24 |
| `CacheKeysIT` | `CachingIntegrationTestBase` | AC-10 (geocoding stubbed to a distinct lat/lon per `name`), AC-12 (`PTTL` ranges) |
| `NegativeCacheIT` | `CachingIntegrationTestBase` | AC-14 (default fake + a no-`country_code` variant) |
| `CacheUpstreamFailureIT` | `CachingIntegrationTestBase` | AC-16 (500, delay > 1 s read timeout, invalid JSON), AC-17 (CURRENT 500; DAILY 500 / short array) |
| `CacheUnreadableEntryIT` | `CachingIntegrationTestBase` + `OutputCaptureExtension` | AC-23: for each kind × {`not-json`, `{}`, `"v":0` valid shape, a hash}, seed under `cacheKeys.geocoding("London")` / `.current(LONDON)` / `.forecast(LONDON, 2026-11-15, 3)` with a 5-minute TTL (the hash via `opsForHash().put` + `expire`). Then: 200, the upstream call made, the WARN `reason=unreadable` with no value text, `TYPE` = string, `PTTL` > 0, and the next request makes no upstream call. |
| `CacheExpiryIT` | `RealProviderIntegrationTestBase` + `@TestPropertySource(current-ttl=1s, forecast-ttl=1s, not-found-ttl=1s, geocoding-ttl=7d)` | AC-13, AC-15 (`Thread.sleep(1500)`) |
| `CacheStoreFailureIT` | `CachingIntegrationTestBase` + `@MockitoSpyBean CacheStore` | AC-22: `doThrow(new RedisConnectionFailureException("simulated")).when(store).get(anyString())` (then `.set(...)` in a 2nd case). Asserts 200 + AC-6 values + WARN. |
| `RedisUnreachableIT` | `RealProviderIntegrationTestBase` + `@Import(UnreachableCacheConfig)`, a nested `@TestConfiguration` with `@Bean @Primary CacheStore` = `IsolatedCacheStore` on a port taken from `new ServerSocket(0)` and closed immediately (static, before the context) | AC-18 (the context starts; `/actuator/health/readiness` → 200 `UP`), AC-19 (the 4 requests with `X-Request-Id: it-rwc-19`; status/body equal to the expected uncached bodies; upstream counts; each < 2 s), AC-20 (`OutputCaptureExtension`: 1..4 `Cache operation failed` WARN lines per request containing `requestId=it-rwc-19`, `op=`, `cache=`, `exception=`; no INFO+ line from `com.example.weather.cache` contains `London`, `Atlantis`, `<prefix>:` or `11.2`; no `\tat ` stack-trace line after these WARNs). Also `runKeys()` on the real Redis is empty. |
| `RedisSilentIT` | as above, but the `@Primary CacheStore` points at `SilentRedisServer.port()`, plus `@TestPropertySource(spring.data.redis.timeout=250ms, spring.data.redis.connect-timeout=250ms)` | AC-21: 200 with AC-6 values in < 3 s |
| `RedisTimeoutConfigIT` | `ApiIntegrationTestBase` (default context) | AC-1: the auto-configured `LettuceConnectionFactory#getClientConfiguration()` has `commandTimeout` 500 ms and `clientOptions.socketOptions.connectTimeout` 500 ms. |
| Existing ITs | unchanged contexts | AC-25: all pass. `RealProviderCurrentIT#ac7` calls `deleteRunRedisKeys()` between its two requests (setup only). `ContractConformanceIT`, `OpenApiDriftIT` and the `RealProvider*` subclasses run with caching enabled (the default). `git diff main -- src/main/resources/openapi/` is checked to be empty during review. |

Context count: this adds about 6 new cached contexts (caching base, disabled, expiry, spy,
unreachable, silent). They keep `spring.datasource.hikari.maximum-pool-size=3` via
`RealProviderInitializer`.

## Implementation plan
1. `feat: add weather.cache TTL/enabled properties and Redis timeouts`: add
   `WeatherCacheProperties`, the `application.yml` placeholders, `.env.example` and the
   `compose.yaml` pass-through, plus `WeatherCachePropertiesTest` and `CacheConfigFilesTest`
   (AC-1, AC-2).
2. `feat: typed weather cache keys in CacheKeys`: add the part validation, `normaliseCity`,
   the SHA-256 token and the `geocoding/current/forecast` builders, and extend `CacheKeysTest`
   (AC-7, AC-10).
3. `refactor: service passes observation time to the provider`: add `ObservedConditions`, the
   default `WeatherProvider#currentAt` and the `WeatherService` change, and a new
   `WeatherServiceTest` case. No behaviour change.
4. `feat: Redis-backed WeatherCache with versioned JSON and fail-open handling`: add
   `CacheStore`, `RedisCacheStore`, `CacheCodec`, `UnreadableCacheEntryException`,
   `GeocodingEntry`, `CurrentEntry`, `WeatherCache` (+ `disabled()`), `RedisWeatherCache`
   (session, short circuit, WARN format) and `WeatherCacheConfiguration`, plus the unit tests
   with `InMemoryCacheStore`.
5. `feat: cache geocoding, current and forecast results in the Open-Meteo adapter`: wire the
   cache through `WeatherProviderConfiguration`/`create`, add the 6-arg constructor, the
   `currentAt` override and the cached `geocode`/`forecast`, and new
   `OpenMeteoWeatherProviderTest` cases.
6. `test: integration test support for caching`: make `IntegrationTestBase#deleteRunRedisKeys`
   protected; add `MutableClock`, `CachingIntegrationTestBase`, `IsolatedCacheStore` and
   `SilentRedisServer`; add the setup-only fix in `RealProviderCurrentIT#ac7`.
7. `test: cache hit, key, TTL, negative and upstream-failure ITs`: `CacheHitIT`, `CacheKeysIT`,
   `NegativeCacheIT`, `CacheUpstreamFailureIT`, `CacheExpiryIT`, `CacheDisabledIT`,
   `StubNotCachedIT` (AC-3..17, 24).
8. `test: Redis failure and unreadable-entry ITs`: `CacheUnreadableEntryIT`,
   `CacheStoreFailureIT`, `RedisUnreachableIT`, `RedisSilentIT`, `RedisTimeoutConfigIT`
   (AC-1, 18..23).
9. `docs: …`: Javadoc on `WeatherProvider`/`CacheKeys`. Run `scripts/test-env.sh all` and check
   AC-25 (`git diff main -- src/main/resources/openapi/` is empty).

## Risks / open questions
- **A-3 assumption (upstream insensitivity)**: two spellings share whichever result the first
  requester's spelling produced. This is acceptable per the requirements, but needs human
  confirmation together with A-1 (TTLs) and A-8 (`observedAt` semantics).
- **Lettuce behaviour against a silent server (AC-21)**: we expect the RESP3 `HELLO` handshake
  to be bounded by the connect timeout, and commands by the command timeout. If a Lettuce
  version doesn't bound the handshake that way, AC-21 fails visibly. A fix would be to set
  `protocolVersion RESP2` via a `LettuceClientConfigurationBuilderCustomizer`, which must
  **not** replace Boot's `ClientOptions`. Verify during step 8.
- **Windows connection refusal latency**: on Windows, a refused loopback connect can take about
  1–2 s because SYNs are retried. `connect-timeout` (500 ms) bounds this, and the short circuit
  keeps it to one attempt per request, which leaves a margin under AC-19's 2 s. If CI shows
  flakiness, `RedisUnreachableIT` may set `connect-timeout=250ms` explicitly.
- **Lettuce's own logging**: AC-20 only constrains lines written by the cache. If Lettuce logs a
  WARN with a stack trace on connect failure (not expected; it logs at DEBUG), the test's "no
  stack trace" check would catch it. The mitigation is a `logging.level.io.lettuce.core`
  setting, which should be flagged before it is added.
- **AC-18/AC-21 test seam**: the failing connection is the cache's own `CacheStore` connection
  built in the test. It is not Boot's auto-configured factory, but it uses Boot's
  `RedisProperties` timeouts, and `RedisTimeoutConfigIT` proves that the auto-configured
  factory gets the same values. Readiness independence from Redis relies on Boot's default
  readiness group, which this feature doesn't change. The test does not cover Boot's own
  factory being down, because that would break `IntegrationTestBase` cleanup (A-10).
- **`OpenMeteoWeatherProvider#current(city)`** (no time argument) does not cache conditions.
  Only `currentAt` does. `WeatherService` only calls `currentAt`, so the API is fully cached.
- **Mockito spies and default methods** (`ProviderFailureIT`): the spy's real default
  `currentAt` calls the stubbed `current`. This is standard Mockito behaviour, and step 3 runs
  `ProviderFailureIT` to confirm it.
- **Stampede** (A-11) and stale-if-error remain out of scope.
