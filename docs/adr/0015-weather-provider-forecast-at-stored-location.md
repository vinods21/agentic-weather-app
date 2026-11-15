# 0015. WeatherProvider gains a daily-forecast lookup by stored location (no geocoding) (amends 0012)
Date: 2026-11-15 · Status: Accepted · Feature: forecast-for-all-favourites

Amends [0012](0012-weather-provider-current-at-stored-location.md), extending its stored-location
rule from current conditions to daily forecasts. It also refines ADR 0005 §1 (port; the service
owns the start date) and applies ADR 0007 §4 (the existing `forecast` key). It supersedes none of
them: 0005, 0007, 0011 and 0012 stay Accepted.

## Context
`GET /api/v1/favourites/forecast` returns a daily forecast for every saved favourite of a client.
Each favourite already stores a resolved location snapshot (ADR 0011). The port only offers
`forecast(String city, LocalDate startDate, int days)`, so with the Open-Meteo adapter every cold
favourite would cost a `GEOCODING` call plus a `DAILY` call, and the result could drift from the
saved place if geocoding changes. The requirements (A-5, AC-9..AC-11, AC-17) ask for:
- exactly one `DAILY` call per cold favourite, and 0 `GEOCODING`/`CURRENT` calls;
- the `latitude`/`longitude` sent upstream to be exactly the stored coordinates, with
  `start_date`/`end_date` from the request's single UTC start date;
- the existing `forecast` cache entry (`<prefix>:forecast:v1:<lat>:<lon>:<date>:<days>`) to be
  shared with `/api/v1/weather/forecast` in both directions, and no new key kind;
- the stub to serve its fixed days for a known stored location, and `LOCATION_NOT_FOUND` to stay
  possible.

ADR 0012 solved the same problem for current conditions with `currentAtLocation`. This ADR
applies the same pattern to forecasts.

## Decision
1. `provider.WeatherProvider` gains
   `default LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days)`.
   - It has a distinct name instead of overloading `forecast(String, LocalDate, int)`, for the
     same reason as ADR 0012 §1: Mockito matchers such as `forecast(any(), any(), anyInt())` and
     `null` first arguments in tests are never ambiguous.
   - `startDate` and `days` have the same meaning as in `forecast`: the service computes the UTC
     start date from its `Clock` and adapters never read a clock (ADR 0005 §1). `days` outside
     1..7 is an `IllegalArgumentException` (a bug, because the API validates it first), as in
     `forecast`.
   - The failure contract is the same as `forecast` (ADR 0005 §2): `LocationNotFoundException`
     when the adapter has no data for the location, `WeatherProviderUnavailableException` for any
     upstream problem, anything else is a bug.
   - The default implementation is `forecast(location.name(), startDate, days)`. An adapter that
     cannot look up by coordinates falls back to resolving the stored canonical name, so any
     adapter or test double that implements only the abstract methods stays correct.
2. `OpenMeteoWeatherProvider` overrides it without geocoding. It checks `days`, opens one
   `WeatherCache` session and calls a new private step `forecast(Session, Location, LocalDate, int)`:
   - read `session.forecast(location, startDate, days)` (key `CacheKeys.forecast(location,
     startDate, days)`, coordinates as `BigDecimal.valueOf(double).toPlainString()`);
   - on a miss, make one `DAILY` call with exactly those coordinate texts, `start_date=startDate`
     and `end_date=startDate + days - 1`, then `putForecast` with the existing `forecast-ttl`;
   - upstream failures are never cached (ADR 0007 §6).

   The existing `forecast(city, startDate, days)` is refactored to check `days`, `geocode`, and
   then call the same private step with the same session. Both paths share one code path and one
   key, and the public behaviour of `forecast` is unchanged.
3. The returned `LocationForecast` carries the **location that was passed in**: a fresh fetch maps
   the upstream body onto it (`mapper.toDaily(location, …)`), and a cache hit is wrapped as
   `new LocationForecast(location, cachedDays)`. A favourite's forecast therefore always describes
   the saved place.
4. `StubWeatherProvider` overrides it by **exact coordinate match**, the same rule as its
   `currentAtLocation` (ADR 0012 §4): the city whose fixed location has the same `latitude` and
   `longitude` (as `double`s). It returns that city's first `days` fixed days, dated from
   `startDate`, with the **passed-in location** (decision 3). Any other coordinate pair throws
   `LocationNotFoundException`. The stub is still never cached. Rows created through the API under
   the stub store the stub's location exactly, so for them the result equals `forecast(city, …)`.
5. Callers pass the favourite's stored `Location` unchanged and compute the start date **once per
   request**, so every item in one response covers the same dates even across UTC midnight. They
   never re-geocode `city` or `location.name()` themselves.

## Consequences
- A cold favourite costs exactly 1 `DAILY` call (0 on a hit). The entry is shared with
  `/api/v1/weather/forecast?city=<same place>&days=<same n>` on the same UTC date, in both
  directions. Redis gains no new key kind.
- `days` stays part of the key (ADR 0007 §4), so `days=3` and `days=7` don't share entries, and a
  new UTC date starts cold (requirements A-7).
- If Open-Meteo's geocoding of a city changes later, the favourite keeps its old coordinates.
  Refreshing stored locations is out of scope.
- Two favourites with identical coordinates share one entry, but on a cold cache in the same
  request each may still call upstream (no de-duplication, requirements A-8; no stampede
  protection, ADR 0007).
- Tests that make the provider fail for this endpoint must stub `forecastAtLocation` (stubbing
  `forecast` alone does not affect the stub's or Open-Meteo's override). Its `days` parameter is
  an `int`, so Mockito matchers must use `anyInt()`, not `any()`.
- The stub's `forecastAtLocation` returns the passed-in location, whereas its `currentAtLocation`
  returns its fixed location. The two differ only for rows inserted directly with a stub
  coordinate pair under another name, which happens only in tests.
- A stored coordinate that is not finite (only possible through a direct database write) makes
  `BigDecimal.valueOf` throw, which is a `500`, as in ADR 0012.

## Alternatives considered
- **Re-geocode `favourite.city` and call `forecast(city, …)`**: no port change, but 1 extra
  upstream call per cold favourite (violates AC-9), and the result can drift from the saved place.
- **Overload `forecast(Location, LocalDate, int)`**: reads naturally, but `forecast(any(), any(),
  anyInt())` in Mockito-based tests would no longer compile, and a `null` first argument becomes
  ambiguous.
- **One generic `lookupAtLocation(Location, Kind, …)` method for both current and forecast**:
  fewer port methods, but a weaker type per kind and a refactor of the ADR 0012 method, which is
  already released.
- **Call `WeatherCache` and the Open-Meteo HTTP code directly from the favourites feature**:
  bypasses the port, ties favourites to one vendor and breaks the stub path (ADR 0011, 0012).
- **Stub returns its fixed location (like its `currentAtLocation`)**: consistent within the stub,
  but it contradicts the port contract in decision 3 that the real adapter honours.
