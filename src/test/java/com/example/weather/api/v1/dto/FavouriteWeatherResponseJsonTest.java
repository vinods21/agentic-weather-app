package com.example.weather.api.v1.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.api.error.ApiExceptionHandler;
import com.example.weather.api.error.ErrorCode;
import com.example.weather.favourites.Favourite;
import com.example.weather.favourites.FavouriteWeather;
import com.example.weather.favourites.FavouriteWeatherFailure;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentWeather;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: JSON shape of the per-favourite weather items with a Boot-like ObjectMapper. No Spring, no Docker. */
class FavouriteWeatherResponseJsonTest {

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
    @DisplayName("AC-1: a weather item has id, city, label, location and weather, and no error")
    void weatherItem() {
        Favourite london = favourite("London", "Home");
        CurrentWeather weather = new WeatherService(STUB, Clock.fixed(NOW, ZoneOffset.UTC)).current("London", Units.METRIC);

        JsonNode item = json(FavouriteWeatherResponse.from(FavouriteWeather.ok(london, weather)));

        assertThat(item.fieldNames()).toIterable().containsExactly("id", "city", "label", "location", "weather");
        assertThat(item.get("id").asText()).isEqualTo(london.id().toString());
        assertThat(item.get("label").asText()).isEqualTo("Home");
        assertThat(item.get("location")).isEqualTo(json(LocationDto.from(london.location())));
        assertThat(item.get("weather")).isEqualTo(json(CurrentWeatherResponse.from(weather)));
        assertThat(item.get("weather").get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(item.get("weather").get("units").asText()).isEqualTo("metric");
    }

    @Test
    @DisplayName("AC-11: an error item has no label when unset, no weather, and the fixed UPSTREAM detail")
    void errorItem() {
        Favourite paris = favourite("Paris", null);

        JsonNode item = json(FavouriteWeatherResponse.from(
                FavouriteWeather.failed(paris, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE)));

        assertThat(item.fieldNames()).toIterable().containsExactly("id", "city", "location", "error");
        assertThat(item.get("error").get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(item.get("error").get("detail").asText())
                .isEqualTo(ApiExceptionHandler.UPSTREAM_DETAIL)
                .isEqualTo("The weather provider is temporarily unavailable.");
    }

    @Test
    @DisplayName("AC-14: LOCATION_NOT_FOUND has a fixed detail without the city")
    void notFoundDetail() {
        FavouriteWeatherErrorDto dto = FavouriteWeatherErrorDto.from(FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        assertThat(dto.code()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(dto.detail()).isEqualTo("No weather data is available for this location.");
    }

    @Test
    @DisplayName("AC-19: every per-item failure name is an ErrorCode name")
    void failureNamesAreErrorCodes() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        assertThat(Arrays.stream(FavouriteWeatherFailure.values()).map(Enum::name)).allMatch(codes::contains);
    }

    @Test
    @DisplayName("AC-4: the list wraps items, and an empty list is {\"items\":[]}")
    void list() throws Exception {
        assertThat(JSON.writeValueAsString(FavouriteWeatherListResponse.from(List.of()))).isEqualTo("{\"items\":[]}");
    }
}
