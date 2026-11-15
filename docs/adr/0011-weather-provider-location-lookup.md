# 0011. WeatherProvider gains a location-only lookup sharing the geocoding cache
Date: 2026-11-15 · Status: Accepted · Feature: saved-favourite-locations

Refines ADR 0005 §1 (port) and ADR 0007 §2 (caching sits inside the adapter). It supersedes
neither.

## Context
Creating a favourite resolves the submitted city to a canonical location (requirements A-2).
The port only offers `current(city)`, `currentAt(city, now)` and `forecast(...)`. With the
Open-Meteo adapter, `current(city)` costs a geocoding call plus a `CURRENT` forecast call, which
wastes the quota. AC-32 requires a create to make exactly 1 `GEOCODING` call and 0 `CURRENT`/
`DAILY` calls on a cache miss, and the following `/api/v1/weather/current?city=…` to reuse the
geocoding cache entry.

## Decision
1. `provider.WeatherProvider` gains
   `default Location locate(String city) { return current(city).location(); }`. It has the same
   failure contract as `current` (`LocationNotFoundException` → 404, `WeatherProviderUnavailableException`
   → 503). The default keeps any adapter or test double that implements only the abstract methods
   correct.
2. `OpenMeteoWeatherProvider` overrides it as `geocode(cache.session(), city)`. That is exactly
   the existing geocoding step, through the same `WeatherCache` session and the same
   `CacheKeys.geocoding(city)` key, including negative caching of "not found". No forecast call
   is made.
3. `StubWeatherProvider` overrides it to return its fixed location. Then a `@MockitoSpyBean`
   records exactly one interaction per create, and the stub is still never cached.
4. Callers pass the city as the user sent it (stripped). They never pass a pre-normalised form,
   so the upstream receives the same text as the weather endpoints would send.

## Consequences
- A favourite create costs at most 1 upstream call (0 on a geocoding hit), and it warms the
  cache for the weather endpoints (and the other way round).
- Tests that make the provider fail for create must stub `locate` (stubbing `current` alone
  doesn't affect the stub's override).
- Adding another adapter means implementing `locate` if it can resolve locations more cheaply
  than fetching current conditions.

## Alternatives considered
- **Call `current(city)` and take the location**: 1 extra upstream call per create, which
  violates AC-32.
- **Call the cache directly from the favourites service**: it would bypass the port, make
  favourites depend on Open-Meteo specifics, and break the stub path.
- **A separate `Geocoder` port**: cleaner in theory, but it would split one adapter into two
  beans sharing a session and cache, and `WeatherProviderConfiguration`'s single-bean selection
  (ADR 0005 §4) would have to be duplicated.
