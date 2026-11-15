package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.RealProviderIntegrationTestBase;
import com.example.weather.support.RunRedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** {@code weather.cache.enabled=false} with the real provider behaves exactly as before the feature (AC-4). */
@TestPropertySource(properties = "weather.cache.enabled=false")
class CacheDisabledIT extends RealProviderIntegrationTestBase {

    @Autowired
    WeatherCache weatherCache;

    @Test
    @DisplayName("AC-4: with caching disabled, two current requests make 2 GEOCODING + 2 CURRENT calls and write no key")
    void ac4_disabledCacheCallsUpstreamEveryTime() {
        assertThat(weatherCache).isSameAs(WeatherCache.disabled());

        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> res = get("/api/v1/weather/current?city=London");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(res);
            assertThat(body.at("/location/name").asText()).isEqualTo("London");
            assertThat(body.at("/location/country").asText()).isEqualTo("GB");
            assertThat(body.get("temperature").asDouble()).isEqualTo(11.2);
            assertThat(body.get("feelsLike").asDouble()).isEqualTo(9.8);
            assertThat(body.get("humidity").asInt()).isEqualTo(81);
            assertThat(body.get("windSpeed").asDouble()).isEqualTo(4.1);
            assertThat(body.get("windDirection").asInt()).isEqualTo(230);
            assertThat(body.at("/condition/code").asText()).isEqualTo("CLOUDY");
        }

        assertThat(received(Route.GEOCODING)).hasSize(2);
        assertThat(received(Route.CURRENT)).hasSize(2);
        assertThat(RunRedisKeys.scan(redis, cacheKeys)).isEmpty();
    }
}
