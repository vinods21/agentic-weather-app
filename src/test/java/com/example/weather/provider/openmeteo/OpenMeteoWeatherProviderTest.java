package com.example.weather.provider.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Edge cases of {@link OpenMeteoWeatherProvider} without a Spring context or network: the
 * {@link RestClient} is bound to a {@link MockRestServiceServer} (in-memory request factory).
 */
class OpenMeteoWeatherProviderTest {

    private static final String BASE = "http://fake.test";
    private static final String GEOCODING_OK = """
            {"results":[{"name":"London","latitude":51.50853,"longitude":-0.12574,"country_code":"GB"}]}""";
    private static final String CURRENT_OK = """
            {"current":{"temperature_2m":11.2,"apparent_temperature":9.8,"relative_humidity_2m":81,\
            "wind_speed_10m":4.1,"wind_direction_10m":230,"weather_code":3}}""";

    private MockRestServiceServer server;
    private OpenMeteoWeatherProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        provider = new OpenMeteoWeatherProvider(client, "fake.test", client, "fake.test", new ObjectMapper());
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(ints = {0, 8, -1})
    @DisplayName("Edge: forecast days outside 1..7 is a programming error (IllegalArgumentException) and calls no upstream")
    void daysOutOfRange(int days) {
        assertThatThrownBy(() -> provider.forecast("London", LocalDate.of(2026, 11, 15), days))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    @DisplayName("AC-13 (edge): a not-found geocoding result passes through as LocationNotFoundException; no forecast call")
    void notFoundPassesThrough() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.current("Atlantis")).isInstanceOf(LocationNotFoundException.class);
        server.verify();
    }

    @Test
    @DisplayName("AC-15..17 (edge): a failed geocoding call makes no forecast call")
    void geocodingFailureStopsBeforeForecast() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.current("London"))
                .isInstanceOf(WeatherProviderUnavailableException.class)
                .hasNoCause()
                .hasMessage("Open-Meteo geocoding call failed: status=503");
        server.verify();
    }

    @Test
    @DisplayName("AC-18 (edge): a 2xx with an empty body is an invalid response (503)")
    void emptyBody() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.current("London"))
                .isInstanceOf(WeatherProviderUnavailableException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    @DisplayName("Edge: a body of exactly 1 MiB is read; 1 MiB + 1 byte is rejected as too large")
    void bodyCap() {
        byte[] exact = paddedJson(OpenMeteoWeatherProvider.MAX_BODY_BYTES);
        byte[] over = paddedJson(OpenMeteoWeatherProvider.MAX_BODY_BYTES + 1);
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(exact, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(over, MediaType.APPLICATION_JSON));

        // Exactly at the cap: parsed, and the padded body has no results, so not found.
        assertThatThrownBy(() -> provider.current("London")).isInstanceOf(LocationNotFoundException.class);
        assertThatThrownBy(() -> provider.current("London"))
                .isInstanceOf(WeatherProviderUnavailableException.class)
                .hasMessageContaining("body too large");
        server.verify();
    }

    @Test
    @DisplayName("Edge: tiny coordinates are sent in plain notation, never scientific (1.0E-5)")
    void coordinatesInPlainNotation() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Null Island","latitude":0.00001,"longitude":-1.0E-7,"country_code":"XX"}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("latitude", Matchers.not(Matchers.containsString("E"))))
                .andExpect(queryParam("longitude", Matchers.not(Matchers.containsString("E"))))
                .andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));

        assertThat(provider.current("Null Island").location().name()).isEqualTo("Null Island");
        server.verify();
    }

    @Test
    @DisplayName("AC-7/AC-8 (edge): the daily request carries start/end dates, UTC and metric units; end = start + days - 1 across a year boundary")
    void dailyRequestParameters() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andExpect(queryParam("start_date", "2026-12-30"))
                .andExpect(queryParam("end_date", "2027-01-01"))
                .andExpect(queryParam("timezone", "UTC"))
                .andExpect(queryParam("temperature_unit", "celsius"))
                .andExpect(queryParam("wind_speed_unit", "ms"))
                .andRespond(withSuccess("""
                        {"daily":{"time":["2026-12-30","2026-12-31","2027-01-01"],
                        "temperature_2m_min":[1,2,3],"temperature_2m_max":[4,5,6],
                        "precipitation_probability_max":[0,50,100],"weather_code":[0,1,2]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.forecast("London", LocalDate.of(2026, 12, 30), 3).days()).hasSize(3);
        server.verify();
    }

    @Test
    @DisplayName("AC-21 (edge): a 2xx with a body that maps to an invalid response has a fixed reason, no cause, no upstream text")
    void invalidResponseMessageIsFixed() {
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/search?")))
                .andRespond(withSuccess(GEOCODING_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/forecast?")))
                .andRespond(withSuccess("{\"current\":{\"temperature_2m\":\"SECRET-upstream-text\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.current("London"))
                .isInstanceOf(WeatherProviderUnavailableException.class)
                .hasNoCause()
                .hasMessage("Open-Meteo forecast call failed: invalid-response reason=missing current.temperature_2m")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    }

    private static byte[] paddedJson(int size) {
        String prefix = "{\"p\":\"";
        String suffix = "\"}";
        return (prefix + "x".repeat(size - prefix.length() - suffix.length()) + suffix)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
