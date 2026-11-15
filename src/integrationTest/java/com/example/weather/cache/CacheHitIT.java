package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Cache hits, key normalisation, shared geocoding, forecast keys and observedAt (AC-5..9, 11, 24, 25). */
class CacheHitIT extends CachingIntegrationTestBase {

    private static final String SAO_PAULO_GEOCODING = """
            {"results":[{"id":3448439,"name":"São Paulo","latitude":-23.5475,"longitude":-46.63611,\
            "country_code":"BR","country":"Brazil"}]}""";

    @Test
    @DisplayName("AC-5: a repeated current request is served from the cache: equal bodies, 1 GEOCODING + 1 CURRENT call")
    void ac5_repeatedCurrentIsCacheHit() {
        ResponseEntity<String> first = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> second = get("/api/v1/weather/current?city=London");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(json(first).get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
    }

    @Test
    @DisplayName("AC-25: a cache hit carries exactly the same response header names as the miss; no X-Cache/Age header")
    void ac25_cacheHitAddsNoHeaders() {
        ResponseEntity<String> miss = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> hit = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> forecastMiss = get("/api/v1/weather/forecast?city=London&days=3");
        ResponseEntity<String> forecastHit = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(received(Route.CURRENT)).hasSize(1);
        assertThat(received(Route.DAILY)).hasSize(1);
        assertThat(headerNames(hit)).isEqualTo(headerNames(miss));
        assertThat(headerNames(forecastHit)).isEqualTo(headerNames(forecastMiss));
        for (ResponseEntity<String> r : List.of(miss, hit, forecastMiss, forecastHit)) {
            assertThat(headerNames(r)).doesNotContain("x-cache", "age", "x-cache-status");
        }
    }

    @Test
    @DisplayName("AC-6: metric then imperial: the imperial response is converted from the cached metric entry; no key carries a unit")
    void ac6_unitsAreNotPartOfTheKey() {
        ResponseEntity<String> metric = get("/api/v1/weather/current?city=London&units=metric");
        int callsAfterFirst = fake.requests().size();
        ResponseEntity<String> imperial = get("/api/v1/weather/current?city=London&units=imperial");

        assertThat(metric.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imperial.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(imperial);
        assertThat(body.get("units").asText()).isEqualTo("imperial");
        assertThat(body.get("temperature").asDouble()).isCloseTo(52.2, within(0.1));
        assertThat(body.get("windSpeed").asDouble()).isCloseTo(9.2, within(0.1));
        assertThat(fake.requests()).hasSize(callsAfterFirst);
        assertThat(runKeys()).isNotEmpty()
                .allSatisfy(k -> assertThat(k.toLowerCase()).doesNotContain("metric").doesNotContain("imperial"));
    }

    @Test
    @DisplayName("AC-7: London, london, LONDON and '%20%20London%20' share one geocoding entry (1 GEOCODING call)")
    void ac7_caseAndPaddingShareOneEntry() {
        for (String city : List.of("London", "london", "LONDON", "%20%20London%20")) {
            ResponseEntity<String> res = get("/api/v1/weather/current?city=" + city);
            assertThat(res.getStatusCode()).as(city).isEqualTo(HttpStatus.OK);
            assertThat(json(res).at("/location/name").asText()).as(city).isEqualTo("London");
        }
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.GEOCODING).get(0).param("name")).isEqualTo("London");
    }

    @Test
    @DisplayName("AC-7: 'New York'/'new   york' and NFC/NFD 'São Paulo' each make exactly 1 GEOCODING call per pair")
    void ac7_whitespaceRunsAndUnicodeFormsShareOneEntry() {
        fake.stub(Route.GEOCODING, Response.ok(FakeOpenMeteo.LONDON_GEOCODING));

        assertThat(get("/api/v1/weather/current?city=New%20York").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/current?city=new%20%20%20york").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.GEOCODING).get(0).param("name")).isEqualTo("New York");

        // NFC: U+00E3; NFD: 'a' + U+0303 (combining tilde).
        assertThat(get("/api/v1/weather/current?city=S%C3%A3o%20Paulo").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/current?city=Sa%CC%83o%20Paulo").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(2);
    }

    @Test
    @DisplayName("AC-7 (edge): accents are kept: 'Sao Paulo' and 'São Paulo' are separate entries")
    void ac7_accentsAreNotStripped() {
        fake.stub(Route.GEOCODING, Response.ok(FakeOpenMeteo.LONDON_GEOCODING));

        get("/api/v1/weather/current?city=Sao%20Paulo");
        get("/api/v1/weather/current?city=S%C3%A3o%20Paulo");

        assertThat(received(Route.GEOCODING)).hasSize(2);
    }

    @Test
    @DisplayName("AC-8: after current?city=London, forecast reuses the geocoding entry: 0 GEOCODING + 1 DAILY call")
    void ac8_geocodingSharedBetweenEndpoints() {
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(1);

        ResponseEntity<String> forecast = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(forecast.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(forecast).get("days")).hasSize(3);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.DAILY)).hasSize(1);
    }

    @Test
    @DisplayName("AC-9: forecast entries are keyed by location, UTC start date and day count (days=5 and the next UTC day miss)")
    void ac9_forecastKeyedByStartDateAndDays() {
        ResponseEntity<String> a = get("/api/v1/weather/forecast?city=London&days=3");
        ResponseEntity<String> b = get("/api/v1/weather/forecast?city=London&days=3");
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody()).isEqualTo(a.getBody());
        assertThat(received(Route.DAILY)).hasSize(1);

        ResponseEntity<String> five = get("/api/v1/weather/forecast?city=London&days=5");
        assertThat(five.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(five).get("days")).hasSize(5);
        List<RecordedRequest> daily = received(Route.DAILY);
        assertThat(daily).hasSize(2);
        assertThat(daily.get(1).param("start_date")).isEqualTo("2026-11-15");
        assertThat(daily.get(1).param("end_date")).isEqualTo("2026-11-19");
        assertThat(received(Route.GEOCODING)).hasSize(1);

        CLOCK.set(Instant.parse("2026-11-16T00:00:01Z"));
        ResponseEntity<String> nextDay = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(nextDay.getStatusCode()).isEqualTo(HttpStatus.OK);
        daily = received(Route.DAILY);
        assertThat(daily).hasSize(3);
        assertThat(daily.get(2).param("start_date")).isEqualTo("2026-11-16");
        assertThat(json(nextDay).at("/days/0/date").asText()).isEqualTo("2026-11-16");
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(runKeys()).contains(
                cacheKeys.forecast(LONDON, java.time.LocalDate.parse("2026-11-15"), 3),
                cacheKeys.forecast(LONDON, java.time.LocalDate.parse("2026-11-15"), 5),
                cacheKeys.forecast(LONDON, java.time.LocalDate.parse("2026-11-16"), 3));
    }

    @Test
    @DisplayName("AC-11: a non-ASCII name with negative coordinates round-trips through the cache unchanged")
    void ac11_nonAsciiAndNegativeCoordinatesRoundTrip() {
        fake.stub(Route.GEOCODING, Response.ok(SAO_PAULO_GEOCODING));

        ResponseEntity<String> first = get("/api/v1/weather/current?city=S%C3%A3o%20Paulo");
        ResponseEntity<String> second = get("/api/v1/weather/current?city=S%C3%A3o%20Paulo");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
        assertThat(json(second)).isEqualTo(json(first));
        assertThat(second.getBody()).isEqualTo(first.getBody());
        JsonNode loc = json(second).get("location");
        assertThat(loc.get("name").asText()).isEqualTo("São Paulo");
        assertThat(loc.get("country").asText()).isEqualTo("BR");
        assertThat(loc.get("latitude").asDouble()).isEqualTo(-23.5475);
        assertThat(loc.get("longitude").asDouble()).isEqualTo(-46.63611);
        assertThat(json(second).at("/condition/description").asText())
                .isEqualTo(json(first).at("/condition/description").asText()).isNotBlank();
        // The coordinates are sent upstream and keyed in the same plain text.
        assertThat(received(Route.CURRENT).get(0).param("latitude")).isEqualTo("-23.5475");
        assertThat(runKeys()).anySatisfy(k -> assertThat(k).endsWith(":current:v1:-23.5475:-46.63611"));
    }

    @Test
    @DisplayName("AC-24: observedAt of a cache hit is the service-clock time the conditions were fetched, not the response time")
    void ac24_observedAtIsFetchTime() {
        ResponseEntity<String> first = get("/api/v1/weather/current?city=London");
        assertThat(json(first).get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");

        CLOCK.set(Instant.parse("2026-11-15T10:05:00Z"));
        ResponseEntity<String> hit = get("/api/v1/weather/current?city=London&units=imperial");

        assertThat(hit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.CURRENT)).hasSize(1);
        assertThat(json(hit).get("units").asText()).isEqualTo("imperial");
        assertThat(json(hit).get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
    }

    @Test
    @DisplayName("AC-24 (edge): after the current entry is gone, observedAt is the new fetch time")
    void ac24_freshFetchUsesNewClockTime() {
        get("/api/v1/weather/current?city=London");
        redis.delete(cacheKeys.current(LONDON));
        CLOCK.set(Instant.parse("2026-11-15T10:07:30.900Z"));

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London");

        assertThat(received(Route.CURRENT)).hasSize(2);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        // Truncated to seconds, as before the feature.
        assertThat(json(res).get("observedAt").asText()).isEqualTo("2026-11-15T10:07:30Z");
    }

    private static Set<String> headerNames(ResponseEntity<String> r) {
        Set<String> names = new TreeSet<>();
        r.getHeaders().keySet().forEach(n -> names.add(n.toLowerCase()));
        // Values that legitimately differ per response are fine; only the name set matters.
        return names;
    }
}
