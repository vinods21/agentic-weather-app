package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.api.error.ErrorCode;
import com.example.weather.favourites.FavouriteWeatherFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: the spec documents GET /api/v1/favourites/weather (ADR 0002/0014). Jackson only, no Spring, no Docker. */
class FavouritesWeatherOpenApiDocumentTest {

    private static final String SCHEMAS = "#/components/schemas/";

    private final JsonNode spec = parse();

    private static JsonNode parse() {
        try {
            return new ObjectMapper().readTree(OpenApiController.load(OpenApiController.SPEC_LOCATION));
        } catch (Exception e) {
            throw new AssertionError("openapi-v1.json does not parse", e);
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private JsonNode schema(String name) {
        return spec.path("components").path("schemas").path(name);
    }

    @Test
    @DisplayName("AC-19: the operation has tag favourites, the ClientId/Units/RequestId parameters and 200/400/500")
    void operation() {
        JsonNode get = spec.path("paths").path("/api/v1/favourites/weather").path("get");
        assertThat(get.path("operationId").asText()).isEqualTo("listFavouritesWeather");
        assertThat(texts(get.path("tags"))).containsExactly("favourites");
        List<String> params = new ArrayList<>();
        get.path("parameters").forEach(p -> params.add(p.path("$ref").asText()));
        assertThat(params).containsExactly("#/components/parameters/ClientId", "#/components/parameters/Units",
                "#/components/parameters/RequestId");
        List<String> statuses = new ArrayList<>();
        get.path("responses").fieldNames().forEachRemaining(statuses::add);
        assertThat(statuses).containsExactly("200", "400", "500");
        assertThat(get.at("/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo(SCHEMAS + "FavouriteWeatherList");
        assertThat(get.at("/responses/200/headers/X-Request-Id/$ref").asText())
                .isEqualTo("#/components/headers/XRequestId");
        assertThat(spec.path("paths").path("/api/v1/favourites/weather").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-19: FavouriteWeatherList holds at most 100 FavouriteWeather items")
    void listSchema() {
        JsonNode list = schema("FavouriteWeatherList");
        assertThat(texts(list.path("required"))).containsExactly("items");
        assertThat(list.at("/properties/items/type").asText()).isEqualTo("array");
        assertThat(list.at("/properties/items/maxItems").asInt()).isEqualTo(100);
        assertThat(list.at("/properties/items/items/$ref").asText()).isEqualTo(SCHEMAS + "FavouriteWeather");
    }

    @Test
    @DisplayName("AC-19: FavouriteWeather requires id, city, location and refers to CurrentWeather and the error schema")
    void itemSchema() {
        JsonNode item = schema("FavouriteWeather");
        assertThat(texts(item.path("required"))).containsExactlyInAnyOrder("id", "city", "location");
        List<String> properties = new ArrayList<>();
        item.path("properties").fieldNames().forEachRemaining(properties::add);
        assertThat(properties).containsExactly("id", "city", "label", "location", "weather", "error");
        assertThat(item.at("/properties/location/$ref").asText()).isEqualTo(SCHEMAS + "Location");
        assertThat(item.at("/properties/weather/$ref").asText()).isEqualTo(SCHEMAS + "CurrentWeather");
        assertThat(item.at("/properties/error/$ref").asText()).isEqualTo(SCHEMAS + "FavouriteWeatherError");
        JsonNode favourite = schema("Favourite").path("properties");
        for (String copied : List.of("id", "city", "label")) {
            assertThat(item.path("properties").path(copied)).as(copied).isEqualTo(favourite.path(copied));
        }
    }

    @Test
    @DisplayName("AC-19: the error code enum equals FavouriteWeatherFailure and is a subset of ErrorCode")
    void errorSchema() {
        JsonNode error = schema("FavouriteWeatherError");
        assertThat(texts(error.path("required"))).containsExactlyInAnyOrder("code", "detail");
        List<String> codes = texts(error.at("/properties/code/enum"));
        assertThat(codes).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(FavouriteWeatherFailure.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name).toList()).containsAll(codes);
        assertThat(error.at("/properties/detail/type").asText()).isEqualTo("string");
    }
}
