# 0012. WeatherProvider gains a current-conditions lookup by stored location (no geocoding)
Date: 2026-11-15 · Status: Accepted · Feature: weather-for-all-favourites

Amends [0011](0011-weather-provider-location-lookup.md) and refines ADR 0005 §1 (port) and
ADR 0007 §3 (time stays with the service). It supersedes none of them; 0011 stays Accepted.

## Context
`GET /api/v1/favourites/weather` returns current weather for every saved favourite of a client.
Each favourite already stores a resolved location snapshot (name, country, latitude, longitude;
ADR 0011). The port only looks weather up by city text (`current(city)`, `currentAt(city, now)`),
so with the Open-Meteo adapter every cold favourite would cost a geocoding call plus a `CURRENT`
call, and the result could drift from the saved place if geocoding changes. The requirements
(A-4, AC-7..AC-9) ask for:
- exactly one `CURRENT` call per cold favourite and 0 `GEOCODING`/`DAILY` calls;
- the `latitude`/`longitude` sent upstream to be exactly the stored coordinates;
- the `current` cache entry (`<prefix>:current:v1:<lat>:<lon>`, ADR 0007 §4) to be shared with
  `/api/v1/weather/current` in both directions, and no new key kind;
- the stub to serve its fixed conditions for a known stored location, and `LOCATION_NOT_FOUND`
  to stay possible for the stub and for adapters that fall back to the city.

## Decision
1. `provider.WeatherProvider` gains
   `default ObservedConditions currentAtLocation(Location location, Instant now)`.
   - It has a distinct name instead of overloading `currentAt(String, Instant)`, so Mockito
     matchers such as `any()` and `null` arguments in tests are never ambiguous.
   - `now` has the same meaning as in `currentAt` (ADR 0007 §3): the service-clock time truncated
     to seconds. Adapters never read a clock.
   - The failure contract is the same as `current` (ADR 0005 §2): `LocationNotFoundException`
     when the adapter has no data for the location, `WeatherProviderUnavailableException` for
     any upstream problem, anything else is a bug.
   - The default implementation is `currentAt(location.name(), now)`: an adapter that cannot
     look up by coordinates falls back to resolving the stored canonical name. It stays correct
     for any adapter or test double that implements only the abstract methods.
2. `OpenMeteoWeatherProvider` overrides it without geocoding: it opens one `WeatherCache`
   session, reads `session.current(location)` (key `CacheKeys.current(location)`, built from
   `BigDecimal.valueOf(double).toPlainString()` of the stored coordinates), and on a miss makes
   one `CURRENT` call with exactly those coordinate texts, then writes the entry with
   `fetchedAt = now` and the existing `current-ttl`. A hit returns the stored `fetchedAt`.
   Upstream failures are never cached. The existing `currentAt(city, now)` is refactored to
   `geocode` and then call the same private step with the same session, so both paths share
   one code path and one key.
3. The returned conditions carry the **location that was passed in** (a fresh fetch maps the
   upstream body onto it, and a cache hit decodes onto it, as `CacheCodec.decodeCurrent(raw,
   location)` already does). The weather for a favourite therefore always describes the saved
   place.
4. `StubWeatherProvider` overrides it by **exact coordinate match**: the city whose fixed
   location has the same `latitude` and `longitude` (as `double`s) as the argument. It returns
   that city's fixed conditions with `observedAt = now`. Name and country are ignored. Any other
   coordinate pair throws `LocationNotFoundException`. The stub is still never cached.
   Favourites created through the API under the stub store the stub's coordinates exactly
   (`DOUBLE PRECISION` round-trips a Java `double`), so they always match.
5. Callers pass the favourite's stored `Location` unchanged. They never re-geocode the stored
   `city` or `location.name()` themselves.

## Consequences
- A cold favourite costs exactly 1 upstream call (0 on a `current` hit), the same key is shared
  with `/api/v1/weather/current?city=<same place>`, and Redis gains no new key kind.
- If Open-Meteo's geocoding of a city changes later, the favourite keeps its old coordinates.
  Refreshing stored locations is out of scope (requirements A-4).
- Two favourites with identical coordinates share one cache entry. On a cold cache in the same
  request each may still call upstream (no in-request de-duplication, requirements A-7, and no
  stampede protection, ADR 0007).
- Tests that make the provider fail for this endpoint must stub `currentAtLocation` (stubbing
  `current` or `currentAt` alone does not affect the stub's or Open-Meteo's override).
- A new adapter should override `currentAtLocation` if it can look up by coordinates. Otherwise
  the default re-resolves the canonical name, which costs whatever its `currentAt` costs.
- A stored coordinate that is not finite (only possible through a direct database write) makes
  `BigDecimal.valueOf` throw, which is a `500`. Rows written by the API always hold upstream
  coordinates.

## Alternatives considered
- **Re-geocode `favourite.city` and call `currentAt(city, now)`**: no port change, but 1 extra
  upstream call per cold favourite (violates AC-7), and the result can drift from the saved place.
- **Overload `currentAt(Location, Instant)`**: reads naturally, but `currentAt(any(), any())`
  in Mockito-based tests would no longer compile, and a `null` first argument becomes ambiguous.
- **Call `WeatherCache` and the Open-Meteo HTTP code directly from the favourites feature**:
  bypasses the port, ties favourites to one vendor, and breaks the stub path (same reason as
  ADR 0011).
- **Stub matching by location name**: works for API-created rows, but the real adapter looks up
  by coordinates and ignores the name. Matching on coordinates keeps the stub's behaviour
  aligned with it: a row's name has no effect, and only its coordinates decide the result.
