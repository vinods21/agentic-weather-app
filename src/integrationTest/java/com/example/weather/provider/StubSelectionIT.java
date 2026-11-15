package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.support.ApiIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AC-2 over HTTP: with the stub selected ({@code it} profile literal) and both base URLs pointing
 * at the fake upstream, the stub answers and nothing reaches the fake.
 */
class StubSelectionIT extends ApiIntegrationTestBase {

    @DynamicPropertySource
    static void fakeUrls(DynamicPropertyRegistry registry) {
        FakeOpenMeteo.registerUrls(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @Autowired
    ApplicationContext context;

    private final FakeOpenMeteo fake = FakeOpenMeteo.instance();

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Test
    @DisplayName("AC-2: with WEATHER_PROVIDER=stub the single WeatherProvider bean is the stub")
    void ac2_singleStubBean() {
        assertThat(context.getBeansOfType(WeatherProvider.class)).hasSize(1);
        assertThat(context.getBean(WeatherProvider.class)).isInstanceOf(StubWeatherProvider.class);
    }

    @Test
    @DisplayName("AC-2: current?city=London returns the stub's fixed values and no request reaches the fake upstream")
    void ac2_stubAnswersWithoutUpstream() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(body.get("humidity").asInt()).isEqualTo(81);

        ResponseEntity<String> forecast = get("/api/v1/weather/forecast?city=London&days=3", Map.of());
        assertThat(forecast.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(fake.requests()).as("requests received by the fake upstream").isEmpty();
    }
}
