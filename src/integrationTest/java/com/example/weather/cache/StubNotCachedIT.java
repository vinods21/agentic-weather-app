package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.support.ApiIntegrationTestBase;
import com.example.weather.support.RunRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** The stub provider (the {@code it} profile default) is never cached, even with caching enabled (AC-3). */
@TestPropertySource(properties = "weather.cache.enabled=true")
class StubNotCachedIT extends ApiIntegrationTestBase {

    @Autowired
    WeatherProvider provider;

    @Test
    @DisplayName("AC-3: with the stub, repeated current/forecast and a 404 leave no key under <prefix>:* and respond as before")
    void ac3_stubIsNeverCached() {
        assertThat(provider).isInstanceOf(StubWeatherProvider.class);

        ResponseEntity<String> c1 = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> c2 = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> f1 = get("/api/v1/weather/forecast?city=London&days=3");
        ResponseEntity<String> f2 = get("/api/v1/weather/forecast?city=London&days=3");
        ResponseEntity<String> missing = get("/api/v1/weather/current?city=Atlantis");

        for (ResponseEntity<String> r : new ResponseEntity[] {c1, c2, f1, f2}) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(json(c1).at("/location/name").asText()).isEqualTo("London");
        assertThat(json(c2).get("temperature")).isEqualTo(json(c1).get("temperature"));
        assertThat(json(f1).get("days")).hasSize(3);
        assertThat(json(f2).get("days")).isEqualTo(json(f1).get("days"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(missing).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");

        assertThat(RunRedisKeys.scan(redis, cacheKeys)).isEmpty();
    }
}
