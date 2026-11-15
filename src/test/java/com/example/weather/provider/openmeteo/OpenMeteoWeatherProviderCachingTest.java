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
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Caching behaviour of {@link OpenMeteoWeatherProvider} with a {@link RedisWeatherCache} over an
 * in-memory store and a {@link MockRestServiceServer}: no Spring context, no network, no Docker.
 * Any request beyond the expected ones fails the test.
 */
class OpenMeteoWeatherProviderCachingTest {

    private static final String BASE = "http://fake.test";
    private static final String GEOCODING_OK = """
            {"results":[{"name":"London","latitude":51.50853,"longitude":-0.12574,"country_code":"GB"}]}""";
    private static final String CURRENT_OK = """
            {"current":{"temperature_2m":11.2,"apparent_temperature":9.8,"relative_humidity_2m":81,\
            "wind_speed_10m":4.1,"wind_direction_10m":230,"weather_code":3}}""";
    private static final String DAILY_OK = """
            {"daily":{"time":["2026-11-15","2026-11-16","2026-11-17"],
            "temperature_2m_min":[6.0,5.5,4.0],"temperature_2m_max":[12.5,11.0,9.5],
            "precipitation_probability_max":[40,20,10],"weather_code":[61,2,45]}}""";
    private static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);
    private static final LocalDate START = LocalDate.of(2026, 11, 15);
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

    private void expectGeocoding(String body) {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectForecast(String body) {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("AC-5/AC-24: a second currentAt makes no request and returns the stored fetch instant")
    void currentIsCached() {
        expectGeocoding(GEOCODING_OK);
        expectForecast(CURRENT_OK);

        ObservedConditions first = provider.currentAt("London", T0);
        ObservedConditions second = provider.currentAt("LONDON", T1);

        server.verify();
        assertThat(first.observedAt()).isEqualTo(T0);
        assertThat(second.observedAt()).isEqualTo(T0);
        assertThat(second.conditions()).isEqualTo(first.conditions());
        assertThat(second.conditions().location()).isEqualTo(LONDON);
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"), keys.current(LONDON));
    }

    @Test
    @DisplayName("AC-8/AC-9: forecast reuses the geocoding entry; a repeat is a hit; another day count misses")
    void forecastSharesGeocoding() {
        expectGeocoding(GEOCODING_OK);
        expectForecast(CURRENT_OK);
        expectForecast(DAILY_OK);
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("end_date", "2026-11-15"))
                .andRespond(withSuccess("""
                        {"daily":{"time":["2026-11-15"],"temperature_2m_min":[6.0],"temperature_2m_max":[12.5],
                        "precipitation_probability_max":[40],"weather_code":[61]}}""", MediaType.APPLICATION_JSON));

        provider.currentAt("London", T0);
        LocationForecast first = provider.forecast("London", START, 3);
        LocationForecast second = provider.forecast("london", START, 3);
        LocationForecast oneDay = provider.forecast("London", START, 1);

        server.verify();
        assertThat(second).isEqualTo(first);
        assertThat(second.location()).isEqualTo(LONDON);
        assertThat(oneDay.days()).hasSize(1);
    }

    @Test
    @DisplayName("AC-14: not found is cached and rethrown with the second request's city")
    void notFoundIsCached() {
        expectGeocoding("{\"results\":[]}");

        assertThatThrownBy(() -> provider.currentAt("Atlantis", T0))
                .isInstanceOfSatisfying(LocationNotFoundException.class, e -> assertThat(e.city()).isEqualTo("Atlantis"));
        assertThatThrownBy(() -> provider.currentAt("ATLANTIS", T0))
                .isInstanceOfSatisfying(LocationNotFoundException.class, e -> assertThat(e.city()).isEqualTo("ATLANTIS"));
        assertThatThrownBy(() -> provider.forecast("atlantis", START, 3))
                .isInstanceOfSatisfying(LocationNotFoundException.class, e -> assertThat(e.city()).isEqualTo("atlantis"));

        server.verify();
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("Atlantis"));
        assertThat(store.ttl(keys.geocoding("Atlantis"))).isEqualTo(WeatherCacheProperties.defaults().notFoundTtl());
    }

    @Test
    @DisplayName("AC-16: a failed or invalid geocoding call writes nothing")
    void geocodingFailureWritesNothing() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?"))).andRespond(withServerError());
        expectGeocoding("not-json");

        assertThatThrownBy(() -> provider.currentAt("London", T0)).isInstanceOf(WeatherProviderUnavailableException.class);
        assertThatThrownBy(() -> provider.currentAt("London", T0)).isInstanceOf(WeatherProviderUnavailableException.class);

        server.verify();
        assertThat(store.values()).isEmpty();
    }

    @Test
    @DisplayName("AC-17: a failed current or daily call leaves only the geocoding entry")
    void forecastFailureKeepsGeocodingOnly() {
        expectGeocoding(GEOCODING_OK);
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?"))).andRespond(withServerError());
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?"))).andRespond(withServerError());

        assertThatThrownBy(() -> provider.currentAt("London", T0)).isInstanceOf(WeatherProviderUnavailableException.class);
        assertThatThrownBy(() -> provider.forecast("London", START, 3))
                .isInstanceOf(WeatherProviderUnavailableException.class);

        server.verify();
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"));
    }

    @Test
    @DisplayName("current(city) caches geocoding but always fetches the conditions")
    void plainCurrentCachesGeocodingOnly() {
        expectGeocoding(GEOCODING_OK);
        expectForecast(CURRENT_OK);
        expectForecast(CURRENT_OK);

        provider.current("London");
        provider.current("London");

        server.verify();
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"));
    }

    @Test
    @DisplayName("AC-32 (unit): locate makes only the geocoding call and shares the geocoding entry")
    void locateIsGeocodingOnly() {
        expectGeocoding(GEOCODING_OK);
        expectForecast(CURRENT_OK);

        Location located = provider.locate("London");
        ObservedConditions current = provider.currentAt("london", T0);
        Location again = provider.locate("LONDON");

        server.verify();
        assertThat(located).isEqualTo(LONDON);
        assertThat(again).isEqualTo(LONDON);
        assertThat(current.conditions().location()).isEqualTo(LONDON);
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"), keys.current(LONDON));
    }

    @Test
    @DisplayName("AC-32 (unit): locate caches not found and writes nothing on an upstream failure")
    void locateFailures() {
        expectGeocoding("{\"results\":[]}");
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?"))).andRespond(withServerError());

        assertThatThrownBy(() -> provider.locate("Atlantis")).isInstanceOf(LocationNotFoundException.class);
        assertThatThrownBy(() -> provider.locate("ATLANTIS")).isInstanceOf(LocationNotFoundException.class);
        assertThatThrownBy(() -> provider.locate("London")).isInstanceOf(WeatherProviderUnavailableException.class);

        server.verify();
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("Atlantis"));
    }
}
