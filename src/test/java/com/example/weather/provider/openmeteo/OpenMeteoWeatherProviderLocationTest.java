package com.example.weather.provider.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.weather.cache.CacheKeys;
import com.example.weather.cache.InMemoryCacheStore;
import com.example.weather.cache.RedisWeatherCache;
import com.example.weather.cache.WeatherCacheProperties;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.Location;
import com.example.weather.weather.ObservedConditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link OpenMeteoWeatherProvider#currentAtLocation} (ADR 0012 §2) with a {@link RedisWeatherCache}
 * over an in-memory store and a {@link MockRestServiceServer}: no geocoding, the stored coordinate
 * texts, and the shared {@code current} entry. Any request beyond the expected ones fails the test.
 */
class OpenMeteoWeatherProviderLocationTest {

    private static final String BASE = "http://fake.test";
    private static final String GEOCODING_OK = """
            {"results":[{"name":"London","latitude":51.50853,"longitude":-0.12574,"country_code":"GB"}]}""";
    private static final String CURRENT_OK = """
            {"current":{"temperature_2m":11.2,"apparent_temperature":9.8,"relative_humidity_2m":81,\
            "wind_speed_10m":4.1,"wind_direction_10m":230,"weather_code":3}}""";
    private static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);
    private static final Location STORED = new Location("Stored name", "GB", 48.8566, 2.3522);
    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-15T10:05:00Z");

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

    @Test
    @DisplayName("AC-7/AC-9: a miss makes one current call with the stored coordinate texts and writes only the current entry")
    void missFetchesByStoredCoordinates() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("latitude", "48.8566"))
                .andExpect(queryParam("longitude", "2.3522"))
                .andExpect(queryParam("current", Matchers.notNullValue(String.class)))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        ObservedConditions observed = provider.currentAtLocation(STORED, T0);

        server.verify();
        assertThat(observed.observedAt()).isEqualTo(T0);
        assertThat(observed.conditions().location()).isEqualTo(STORED);
        assertThat(observed.conditions().temperature()).isEqualTo(11.2);
        assertThat(store.values()).containsOnlyKeys(keys.current(STORED));
        assertThat(store.ttl(keys.current(STORED))).isEqualTo(WeatherCacheProperties.defaults().currentTtl());
    }

    @Test
    @DisplayName("AC-8: a hit makes no request and returns the stored fetch instant")
    void hitReturnsStoredFetchedAt() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        ObservedConditions first = provider.currentAtLocation(STORED, T0);
        ObservedConditions second = provider.currentAtLocation(STORED, T1);

        server.verify();
        assertThat(second.observedAt()).isEqualTo(T0);
        assertThat(second.conditions()).isEqualTo(first.conditions());
    }

    @Test
    @DisplayName("AC-8: the current entry is shared with currentAt(city) in both directions")
    void sharedWithCityLookup() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        ObservedConditions byCity = provider.currentAt("London", T0);
        ObservedConditions byLocation = provider.currentAtLocation(LONDON, T1);
        ObservedConditions byCityAgain = provider.currentAt("London", T1);

        server.verify();
        assertThat(byLocation.observedAt()).isEqualTo(T0);
        assertThat(byLocation.conditions()).isEqualTo(byCity.conditions());
        assertThat(byCityAgain.observedAt()).isEqualTo(T0);
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"), keys.current(LONDON));
    }

    @Test
    @DisplayName("AC-13: a failed current call writes nothing, and the next call fetches again")
    void failureIsNotCached() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?"))).andRespond(withServerError());
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.currentAtLocation(STORED, T0))
                .isInstanceOf(WeatherProviderUnavailableException.class);
        assertThat(store.values()).isEmpty();
        assertThat(provider.currentAtLocation(STORED, T1).observedAt()).isEqualTo(T1);

        server.verify();
    }
}
