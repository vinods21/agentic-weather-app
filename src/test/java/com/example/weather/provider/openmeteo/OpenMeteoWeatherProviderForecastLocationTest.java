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
import com.example.weather.weather.LocationForecast;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link OpenMeteoWeatherProvider#forecastAtLocation} (ADR 0015 §2) with a {@link RedisWeatherCache}
 * over an in-memory store and a {@link MockRestServiceServer}: no geocoding, the stored coordinate
 * texts, and the shared {@code forecast} entry. Any request beyond the expected ones fails the test.
 */
class OpenMeteoWeatherProviderForecastLocationTest {

    private static final String BASE = "http://fake.test";
    private static final String GEOCODING_OK = """
            {"results":[{"name":"London","latitude":51.50853,"longitude":-0.12574,"country_code":"GB"}]}""";
    private static final String DAILY_3 = """
            {"daily":{"time":["2026-11-15","2026-11-16","2026-11-17"],
            "temperature_2m_min":[6.0,5.5,4.0],"temperature_2m_max":[12.5,11.0,9.5],
            "precipitation_probability_max":[40,20,10],"weather_code":[61,2,45]}}""";
    private static final String DAILY_4 = """
            {"daily":{"time":["2026-11-15","2026-11-16","2026-11-17","2026-11-18"],
            "temperature_2m_min":[6.0,5.5,4.0,3.5],"temperature_2m_max":[12.5,11.0,9.5,10.0],
            "precipitation_probability_max":[40,20,10,60],"weather_code":[61,2,45,51]}}""";
    private static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);
    private static final Location STORED = new Location("Stored name", "FR", 48.8566, 2.3522);
    private static final LocalDate START = LocalDate.of(2026, 11, 15);

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

    private void expectDaily(String body) {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("daily", Matchers.notNullValue(String.class)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectGeocoding() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("AC-9/AC-11: a miss makes one daily call with the stored coordinate texts and writes only the forecast entry")
    void missFetchesByStoredCoordinates() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("latitude", "48.8566"))
                .andExpect(queryParam("longitude", "2.3522"))
                .andExpect(queryParam("daily", Matchers.notNullValue(String.class)))
                .andExpect(queryParam("start_date", "2026-11-15"))
                .andExpect(queryParam("end_date", "2026-11-17"))
                .andRespond(withSuccess(DAILY_3, MediaType.APPLICATION_JSON));

        LocationForecast forecast = provider.forecastAtLocation(STORED, START, 3);

        server.verify();
        assertThat(forecast.location()).isEqualTo(STORED);
        assertThat(forecast.days()).hasSize(3);
        assertThat(forecast.days().getFirst().date()).isEqualTo(START);
        assertThat(forecast.days().getFirst().maxTemperature()).isEqualTo(12.5);
        assertThat(store.values()).containsOnlyKeys(keys.forecast(STORED, START, 3));
        assertThat(store.ttl(keys.forecast(STORED, START, 3)))
                .isEqualTo(WeatherCacheProperties.defaults().forecastTtl());
    }

    @Test
    @DisplayName("AC-10: a hit makes no request and carries the passed-in location")
    void hitMakesNoRequest() {
        expectDaily(DAILY_3);

        LocationForecast first = provider.forecastAtLocation(STORED, START, 3);
        Location renamed = new Location("Other name", "FR", STORED.latitude(), STORED.longitude());
        LocationForecast second = provider.forecastAtLocation(renamed, START, 3);

        server.verify();
        assertThat(second.days()).isEqualTo(first.days());
        assertThat(second.location()).isEqualTo(renamed);
    }

    @Test
    @DisplayName("AC-10: an entry written by forecast(city) is served to forecastAtLocation")
    void cityEntryServedToLocation() {
        expectGeocoding();
        expectDaily(DAILY_3);

        LocationForecast byCity = provider.forecast("London", START, 3);
        LocationForecast byLocation = provider.forecastAtLocation(LONDON, START, 3);

        server.verify();
        assertThat(byLocation.days()).isEqualTo(byCity.days());
        assertThat(byLocation.location()).isEqualTo(LONDON);
        assertThat(store.values()).containsOnlyKeys(keys.geocoding("London"), keys.forecast(LONDON, START, 3));
    }

    @Test
    @DisplayName("AC-10: an entry written by forecastAtLocation is served to forecast(city)")
    void locationEntryServedToCity() {
        expectDaily(DAILY_3);
        expectGeocoding();

        LocationForecast byLocation = provider.forecastAtLocation(LONDON, START, 3);
        LocationForecast byCity = provider.forecast("London", START, 3);

        server.verify();
        assertThat(byCity.days()).isEqualTo(byLocation.days());
    }

    @Test
    @DisplayName("AC-10: days is part of the key, so another days value misses")
    void otherDaysMisses() {
        expectDaily(DAILY_3);
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("end_date", "2026-11-18"))
                .andRespond(withSuccess(DAILY_4, MediaType.APPLICATION_JSON));

        provider.forecastAtLocation(STORED, START, 3);
        LocationForecast four = provider.forecastAtLocation(STORED, START, 4);

        server.verify();
        assertThat(four.days()).hasSize(4);
        assertThat(store.values())
                .containsOnlyKeys(keys.forecast(STORED, START, 3), keys.forecast(STORED, START, 4));
    }

    @Test
    @DisplayName("AC-16: a failed daily call writes nothing, and the next call fetches again")
    void failureIsNotCached() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?"))).andRespond(withServerError());
        expectDaily(DAILY_3);

        assertThatThrownBy(() -> provider.forecastAtLocation(STORED, START, 3))
                .isInstanceOf(WeatherProviderUnavailableException.class);
        assertThat(store.values()).isEmpty();
        assertThat(provider.forecastAtLocation(STORED, START, 3).days()).hasSize(3);

        server.verify();
    }

    @Test
    @DisplayName("AC-9: days outside 1..7 is a bug and touches neither the cache nor upstream")
    void daysOutOfRange() {
        assertThatThrownBy(() -> provider.forecastAtLocation(STORED, START, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.forecastAtLocation(STORED, START, 8))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
        assertThat(store.calls()).isEmpty();
    }
}
