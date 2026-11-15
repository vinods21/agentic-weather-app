package com.example.weather.api.v1.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.api.error.ApiExceptionHandler;
import com.example.weather.favourites.Favourite;
import com.example.weather.favourites.FavouriteForecast;
import com.example.weather.favourites.FavouriteWeatherFailure;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.Forecast;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: JSON shape of the per-favourite forecast items with a Boot-like ObjectMapper. No Spring, no Docker. */
class FavouriteForecastResponseJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final StubWeatherProvider STUB = new StubWeatherProvider();

    private static Favourite favourite(String city, String label) {
        return new Favourite(UUID.randomUUID(), "client-1", city, label, STUB.locate(city), 1, NOW, NOW);
    }

    private static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    @Test
    @DisplayName("AC-1/AC-22: a forecast item has id, city, label, location and forecast, and no error")
    void forecastItem() {
        Favourite london = favourite("London", "Home");
        Forecast forecast = new WeatherService(STUB, Clock.fixed(NOW, ZoneOffset.UTC)).forecast("London", Units.METRIC, 5);

        JsonNode item = json(FavouriteForecastResponse.from(FavouriteForecast.ok(london, forecast)));

        assertThat(item.fieldNames()).toIterable().containsExactly("id", "city", "label", "location", "forecast");
        assertThat(item.get("id").asText()).isEqualTo(london.id().toString());
        assertThat(item.get("city").asText()).isEqualTo("London");
        assertThat(item.get("label").asText()).isEqualTo("Home");
        assertThat(item.get("location")).isEqualTo(json(LocationDto.from(london.location())));
        assertThat(item.get("forecast")).isEqualTo(json(ForecastResponse.from(forecast)));
        assertThat(item.get("forecast").get("units").asText()).isEqualTo("metric");
        assertThat(item.get("forecast").get("days")).hasSize(5);
        assertThat(item.get("forecast").get("days").get(0).get("date").asText()).isEqualTo("2026-11-15");
    }

    @Test
    @DisplayName("AC-14: an error item has no label when unset, no forecast, and the fixed UPSTREAM detail")
    void errorItem() {
        Favourite paris = favourite("Paris", null);

        JsonNode item = json(FavouriteForecastResponse.from(
                FavouriteForecast.failed(paris, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE)));

        assertThat(item.fieldNames()).toIterable().containsExactly("id", "city", "location", "error");
        assertThat(item.get("error").get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(item.get("error").get("detail").asText())
                .isEqualTo(ApiExceptionHandler.UPSTREAM_DETAIL)
                .isEqualTo("The weather provider is temporarily unavailable.");
    }

    @Test
    @DisplayName("AC-17: LOCATION_NOT_FOUND has the fixed detail without the city")
    void notFoundItem() {
        Favourite tokyo = favourite("Tokyo", null);

        JsonNode error = json(FavouriteForecastResponse.from(
                FavouriteForecast.failed(tokyo, FavouriteWeatherFailure.LOCATION_NOT_FOUND))).get("error");

        assertThat(error.get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(error.get("detail").asText())
                .isEqualTo(FavouriteWeatherErrorDto.LOCATION_NOT_FOUND_DETAIL)
                .doesNotContain("Tokyo");
    }

    @Test
    @DisplayName("AC-6: the list wraps items, and an empty list is {\"items\":[]}")
    void list() throws Exception {
        assertThat(JSON.writeValueAsString(FavouriteForecastListResponse.from(List.of()))).isEqualTo("{\"items\":[]}");
    }
}
