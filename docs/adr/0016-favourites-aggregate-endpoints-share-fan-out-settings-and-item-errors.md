# 0016. Favourites aggregate endpoints share one fan-out bean, the `weather.favourites.weather.*` settings and the per-item error schema
Date: 2026-11-15 · Status: Accepted · Feature: forecast-for-all-favourites

Refines [0013](0013-bounded-parallel-fan-out-with-deadline.md) (which settings bound a fan-out)
and applies [0014](0014-per-item-errors-in-aggregate-read-responses.md) to a second endpoint.
Both stay Accepted.

## Context
`GET /api/v1/favourites/forecast` is the second aggregate endpoint after
`GET /api/v1/favourites/weather`. It fans out one upstream lookup per favourite, against the same
upstream host, with a similar per-call latency. ADR 0013 introduced `concurrent.BoundedFanOut`,
one bean (`favouriteWeatherFanOut`) and the settings `weather.favourites.weather.max-concurrency`
(4, 1..20) and `weather.favourites.weather.timeout` (5 s, 1 s..30 s). ADR 0014 introduced the
per-item `error {code, detail}` shape, with the OpenAPI schema `FavouriteWeatherError`.

The requirements (A-3, A-6, AC-21, AC-22) ask that the new endpoint:
- adds no configuration property, no environment variable and no `compose.yaml`/`.env.example`
  change;
- references the existing error schema instead of copying it, and keeps it unchanged;
- adds no executor and no `Executor` bean.

This decides how later favourites aggregate endpoints are wired, so it is recorded once.

## Decision
1. **One fan-out bean.** Every favourites aggregate endpoint injects the existing `BoundedFanOut`
   bean. No second `BoundedFanOut` or `ExecutorService` is registered. All of its state is per
   `map` call and the executor is thread-per-task (virtual threads), so sharing it has no effect
   on the per-request bounds of ADR 0013. A second bean of the same type would make injection by
   type ambiguous for the existing service and add a second executor to shut down.
2. **Shared settings.** `weather.favourites.weather.max-concurrency` and
   `weather.favourites.weather.timeout` apply **per request** to every favourites aggregate
   endpoint (today `/favourites/weather` and `/favourites/forecast`). The names stay as they are.
   Renaming them would be a breaking configuration change. `application.yml` (comments only), the
   overview and the OpenAPI descriptions say that they also govern `/favourites/forecast`.
3. **Shared per-item errors.** All favourites aggregate endpoints use the same domain enum
   (`favourites.FavouriteWeatherFailure`), the same DTO (`api.v1.dto.FavouriteWeatherErrorDto`) and
   the same OpenAPI schema (`FavouriteWeatherError`, referenced with `$ref`), so they have the same
   codes (`UPSTREAM_UNAVAILABLE`, `LOCATION_NOT_FOUND`) and the same fixed `detail` texts. A code
   added to that enum later applies to every endpoint that references it, which is additive
   (ADR 0014 §5).
4. **Distinguishable logs.** Each endpoint writes its own ADR 0014 §7 WARN line with its own
   fixed prefix (`Favourites weather partial failure …`, `Favourites forecast partial failure …`)
   and the same counts (`failed`, `total`, `timedOut`).

## Consequences
- One knob limits per-request upstream pressure for both dashboard endpoints, and there are no
  new env vars, binder tests or compose entries.
- One endpoint can't be tuned without affecting the other. If that is needed later, add
  `weather.favourites.forecast.{max-concurrency,timeout}` whose defaults are the shared values.
  That change is additive.
- The property and schema names say "weather" but now also cover forecasts. The descriptions and
  comments say so.
- Worker threads of both endpoints carry the `fav-weather-` name prefix. This is cosmetic.
- Upstream concurrency is still bounded per request, not globally (ADR 0013 Consequences). A UI
  that loads both endpoints at once can have 2 × `max-concurrency` calls in flight for one client.

## Alternatives considered
- **Separate `weather.favourites.forecast.*` settings with the same defaults and ranges**
  (requirements A-6 alternative): independent tuning, but new env vars, `.env.example` and
  `compose.yaml` entries, and binder and config-file tests for a need nobody has yet.
- **A second `BoundedFanOut` bean with a `@Qualifier`**: separate thread names, but the existing
  service's injection point would need a qualifier (a change to working code), and there would be
  two executors with identical behaviour.
- **A new `FavouriteForecastError` schema with the same content**: duplication that can drift, and
  generated clients get two identical types.
- **Renaming `FavouriteWeatherError` to a neutral name**: not additive within v1 for generated
  clients (ADR 0003 §1).
