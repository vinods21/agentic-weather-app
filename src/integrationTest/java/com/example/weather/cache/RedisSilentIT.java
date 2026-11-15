package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.IsolatedCacheStore;
import com.example.weather.support.SilentRedisServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The cache's Redis accepts TCP connections but never answers (an in-process
 * {@link SilentRedisServer}); Redis timeouts are 250 ms (AC-21). Only the cache's
 * {@link CacheStore} is redirected; no container is touched.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(RedisSilentIT.SilentCacheStoreConfig.class)
@TestPropertySource(properties = {
    "spring.data.redis.timeout=250ms",
    "spring.data.redis.connect-timeout=250ms",
})
class RedisSilentIT extends CachingIntegrationTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class SilentCacheStoreConfig {
        @Bean
        @Primary
        IsolatedCacheStore silentCacheStore(RedisProperties redisProperties) {
            return new IsolatedCacheStore(SilentRedisServer.instance().port(), redisProperties);
        }
    }

    @Test
    @DisplayName("AC-21: a silent Redis (accepts, never answers) with 250 ms timeouts: current is 200 with AC-6 values in < 3 s")
    void ac21_silentRedisIsBoundedByTimeouts(CapturedOutput output) {
        String id = "it-rwc-21";

        long start = System.nanoTime();
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.at("/location/name").asText()).isEqualTo("London");
        assertThat(body.get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(body.get("feelsLike").asDouble()).isEqualTo(9.8);
        assertThat(body.get("humidity").asInt()).isEqualTo(81);
        assertThat(body.get("windSpeed").asDouble()).isEqualTo(4.1);
        assertThat(body.get("windDirection").asInt()).isEqualTo(230);
        assertThat(body.at("/condition/code").asText()).isEqualTo("CLOUDY");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));

        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
        // The silent server was really contacted (not a refused connection).
        assertThat(SilentRedisServer.instance().accepted()).isPositive();
        assertThat(output.getAll().lines()
                .filter(l -> l.contains("requestId=" + id + "]") && l.contains("Cache operation failed")))
                .hasSizeBetween(1, 4)
                .allSatisfy(l -> assertThat(l).contains("reason=unavailable"));
        assertThat(runKeys()).isEmpty();
    }
}
