package com.example.weather.provider.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.weather.cache.CacheKeys;
import com.example.weather.cache.InMemoryCacheStore;
import com.example.weather.cache.RedisWeatherCache;
import com.example.weather.cache.WeatherCacheProperties;
import com.example.weather.weather.Location;
import com.example.weather.weather.ObservedConditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Adapter-level cache edge cases (tester): no Spring context, no network, no Docker. */
class OpenMeteoWeatherProviderCachingEdgeCaseTest {

    private static final String BASE = "http://fake.test";
    private static final String GEOCODING_OK = """
            {"results":[{"name":"London","latitude":51.50853,"longitude":-0.12574,"country_code":"GB"}]}""";
    private static final String CURRENT_OK = """
            {"current":{"temperature_2m":11.2,"apparent_temperature":9.8,"relative_humidity_2m":81,\
            "wind_speed_10m":4.1,"wind_direction_10m":230,"weather_code":3}}""";
    private static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);
    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    private final CacheKeys keys = new CacheKeys("weather");
    private final InMemoryCacheStore store = new InMemoryCacheStore();
    private MockRestServiceServer server;
    private OpenMeteoWeatherProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        provider = new OpenMeteoWeatherProvider(client, "fake.test", client, "fake.test", new ObjectMapper(),
                new RedisWeatherCache(store, keys, WeatherCacheProperties.defaults()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, -1})
    @DisplayName("AC-9 (edge): an out-of-range day count is rejected before any cache or upstream access")
    void invalidDaysTouchesNothing(int days) {
        assertThatThrownBy(() -> provider.forecast("London", LocalDate.of(2026, 11, 15), days))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.calls()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("AC-19: with every cache read timing out, currentAt still makes both upstream calls and returns now as observedAt")
    void redisDownMakesFullUpstreamCalls() {
        store.failGets(() -> new QueryTimeoutException("Redis command timed out"));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        ObservedConditions o = provider.currentAt("London", T0);

        server.verify();
        assertThat(o.observedAt()).isEqualTo(T0);
        assertThat(o.conditions().temperature()).isEqualTo(11.2);
        // One failed get, then the session bypasses Redis (no put, no second get).
        assertThat(store.calls()).hasSize(1);
        assertThat(store.values()).isEmpty();
    }

    @Test
    @DisplayName("AC-5 (edge): a cached geocoding entry written by another spelling serves currentAt without a geocoding call")
    void geocodingHitForOtherSpelling() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        provider.currentAt("London", T0);
        ObservedConditions again = provider.currentAt("  LONDON ", T0.plusSeconds(60));

        server.verify();
        assertThat(again.observedAt()).isEqualTo(T0);
        assertThat(again.conditions().location()).isEqualTo(LONDON);
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"), keys.current(LONDON));
    }
}
