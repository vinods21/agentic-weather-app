package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Negative caching of "not found" (AC-14). */
class NegativeCacheIT extends CachingIntegrationTestBase {

    @Test
    @DisplayName("AC-14: Atlantis, ATLANTIS (current) and atlantis (forecast) are 404 with their own city; 1 GEOCODING call, no CURRENT/DAILY")
    void ac14_notFoundIsCachedAndEchoesTheRequestCity() {
        assertNotFound(get("/api/v1/weather/current?city=Atlantis"), "Atlantis");
        assertNotFound(get("/api/v1/weather/current?city=ATLANTIS"), "ATLANTIS");
        assertNotFound(get("/api/v1/weather/forecast?city=atlantis&days=3"), "atlantis");

        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        assertThat(received(Route.DAILY)).isEmpty();
        assertThat(runKeys()).containsExactly(cacheKeys.geocoding("Atlantis"));
        assertThat(redis.opsForValue().get(cacheKeys.geocoding("Atlantis"))).doesNotContainIgnoringCase("atlantis");
    }

    @Test
    @DisplayName("AC-14: a first geocoding result without country_code is cached as not found the same way")
    void ac14_missingCountryCodeIsCachedAsNotFound() {
        String city = "Nowhere" + UUID.randomUUID().toString().substring(0, 6);
        fake.stub(Route.GEOCODING, Response.ok(
                "{\"results\":[{\"name\":\"Nowhere\",\"latitude\":1.5,\"longitude\":2.5}]}"));

        assertNotFound(get("/api/v1/weather/current?city=" + city), city);
        assertNotFound(get("/api/v1/weather/current?city=" + city.toUpperCase()), city.toUpperCase());
        assertNotFound(get("/api/v1/weather/forecast?city=" + city.toLowerCase() + "&days=3"), city.toLowerCase());

        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        assertThat(received(Route.DAILY)).isEmpty();
    }

    @Test
    @DisplayName("AC-14 (edge): a not-found entry for one city does not affect another city")
    void ac14_notFoundIsPerCity() {
        assertNotFound(get("/api/v1/weather/current?city=Atlantis"), "Atlantis");
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(2);
    }

    private static void assertNotFound(ResponseEntity<String> res, String city) {
        assertThat(res.getStatusCode()).as(city).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(body.get("detail").asText()).contains(city);
    }
}
