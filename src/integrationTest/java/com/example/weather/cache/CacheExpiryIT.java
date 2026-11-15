package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Entries expire on their own TTL (AC-13, AC-15). Uses real Redis expiry, so it sleeps 1.5 s
 * against 1 s TTLs (timing-sensitive, but with a 500 ms margin).
 */
@TestPropertySource(properties = {
    "weather.cache.geocoding-ttl=7d",
    "weather.cache.current-ttl=1s",
    "weather.cache.forecast-ttl=1s",
    "weather.cache.not-found-ttl=1s",
})
class CacheExpiryIT extends CachingIntegrationTestBase {

    private static final long PAST_TTL_MS = 1500;

    @Test
    @DisplayName("AC-13: with current-ttl=1s the current entry expires (2 CURRENT calls) while geocoding stays cached (1 GEOCODING call)")
    void ac13_currentEntryExpires() throws InterruptedException {
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.CURRENT)).hasSize(1);

        Thread.sleep(PAST_TTL_MS);
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(received(Route.CURRENT)).hasSize(2);
        assertThat(received(Route.GEOCODING)).hasSize(1);
    }

    @Test
    @DisplayName("AC-13: with forecast-ttl=1s the forecast entry expires (2 DAILY calls) while geocoding stays cached (1 GEOCODING call)")
    void ac13_forecastEntryExpires() throws InterruptedException {
        assertThat(get("/api/v1/weather/forecast?city=London&days=3").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/forecast?city=London&days=3").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.DAILY)).hasSize(1);

        Thread.sleep(PAST_TTL_MS);
        assertThat(get("/api/v1/weather/forecast?city=London&days=3").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(received(Route.DAILY)).hasSize(2);
        assertThat(received(Route.GEOCODING)).hasSize(1);
    }

    @Test
    @DisplayName("AC-15: with not-found-ttl=1s a newly known place is found after the not-found entry expires")
    void ac15_notFoundEntryExpires() throws InterruptedException {
        assertThat(get("/api/v1/weather/current?city=Atlantis").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runKeys()).containsExactly(cacheKeys.geocoding("Atlantis"));

        fake.stub(Route.GEOCODING, Response.ok(
                "{\"results\":[{\"name\":\"Atlantis\",\"latitude\":36.4,\"longitude\":25.4,\"country_code\":\"GR\"}]}"));
        // Still within the TTL: the cached not-found answers.
        assertThat(get("/api/v1/weather/current?city=Atlantis").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(received(Route.GEOCODING)).hasSize(1);

        Thread.sleep(PAST_TTL_MS);
        var res = get("/api/v1/weather/current?city=Atlantis");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).at("/location/name").asText()).isEqualTo("Atlantis");
        assertThat(received(Route.GEOCODING)).hasSize(2);
    }
}
