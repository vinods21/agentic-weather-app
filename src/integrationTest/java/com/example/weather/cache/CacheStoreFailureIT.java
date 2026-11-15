package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Redis reachable, but a cache read or write fails with a data-access exception (AC-22). */
@ExtendWith(OutputCaptureExtension.class)
class CacheStoreFailureIT extends CachingIntegrationTestBase {

    @MockitoSpyBean
    CacheStore store;

    @Test
    @DisplayName("AC-22: a failed cache read is a miss: 200 with AC-6 values, full upstream calls, one 'Cache operation failed' WARN")
    void ac22_failedReadIsMiss(CapturedOutput output) {
        doThrow(new RedisConnectionFailureException("simulated")).when(store).get(anyString());
        String id = "it-rwc-22r-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertAc6(res);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
        List<String> warns = warns(output, id);
        assertThat(warns).singleElement().satisfies(l -> assertThat(l)
                .contains("op=get").contains("cache=geocoding").contains("reason=unavailable")
                .contains("exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("simulated"));
        // The short circuit skipped the rest of the call.
        verify(store, times(1)).get(anyString());
        verify(store, never()).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("AC-22: a failed cache write does not change the response: 200 with AC-6 values and one 'Cache operation failed' WARN")
    void ac22_failedWriteIsIgnored(CapturedOutput output) {
        doThrow(new RedisConnectionFailureException("simulated")).when(store).set(anyString(), anyString(), any());
        String id = "it-rwc-22w-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertAc6(res);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
        assertThat(warns(output, id)).singleElement().satisfies(l -> assertThat(l)
                .contains("op=put").contains("cache=geocoding").contains("reason=unavailable"));
        verify(store, atLeastOnce()).set(anyString(), anyString(), any());
        assertThat(runKeys()).isEmpty();
    }

    @Test
    @DisplayName("AC-22 (edge): failed reads on every request never break the endpoint; forecast is 200 too")
    void ac22_failedReadsOnForecast(CapturedOutput output) {
        doThrow(new RedisConnectionFailureException("simulated")).when(store).get(anyString());

        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("days")).hasSize(3);
        assertThat(received(Route.DAILY)).hasSize(1);
        assertThat(output.getAll()).contains("Cache operation failed op=get cache=geocoding reason=unavailable");
    }

    private static List<String> warns(CapturedOutput output, String id) {
        return output.getAll().lines()
                .filter(l -> l.contains("requestId=" + id + "]") && l.contains("Cache operation failed"))
                .toList();
    }

    private static void assertAc6(ResponseEntity<String> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.at("/location/name").asText()).isEqualTo("London");
        assertThat(body.get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(body.get("feelsLike").asDouble()).isEqualTo(9.8);
        assertThat(body.get("humidity").asInt()).isEqualTo(81);
        assertThat(body.get("windSpeed").asDouble()).isEqualTo(4.1);
        assertThat(body.get("windDirection").asInt()).isEqualTo(230);
        assertThat(body.at("/condition/code").asText()).isEqualTo("CLOUDY");
    }
}
