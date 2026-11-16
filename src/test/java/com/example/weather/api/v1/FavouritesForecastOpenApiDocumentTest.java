package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: the spec documents GET /api/v1/favourites/forecast (ADR 0002/0016). Jackson only, no Spring, no Docker. */
class FavouritesForecastOpenApiDocumentTest {

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
    @DisplayName("AC-22: the operation has tag favourites, the ClientId/Units/Days/RequestId parameters and 200/400/500")
    void operation() {
        JsonNode path = spec.path("paths").path("/api/v1/favourites/forecast");
        assertThat(path.size()).isEqualTo(1);
        JsonNode get = path.path("get");
        assertThat(get.path("operationId").asText()).isEqualTo("listFavouritesForecast");
        assertThat(texts(get.path("tags"))).containsExactly("favourites");
        List<String> params = new ArrayList<>();
        get.path("parameters").forEach(p -> params.add(p.path("$ref").asText()));
        assertThat(params).containsExactly("#/components/parameters/ClientId", "#/components/parameters/Units",
                "#/components/parameters/Days", "#/components/parameters/RequestId");
        List<String> statuses = new ArrayList<>();
        get.path("responses").fieldNames().forEachRemaining(statuses::add);
        assertThat(statuses).containsExactly("200", "400", "500");
        assertThat(get.at("/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo(SCHEMAS + "FavouriteForecastList");
        assertThat(get.at("/responses/200/headers/X-Request-Id/$ref").asText())
                .isEqualTo("#/components/headers/XRequestId");
    }

    @Test
    @DisplayName("AC-22: FavouriteForecastList holds at most 100 FavouriteForecast items")
    void listSchema() {
        JsonNode list = schema("FavouriteForecastList");
        assertThat(texts(list.path("required"))).containsExactly("items");
        assertThat(list.at("/properties/items/type").asText()).isEqualTo("array");
        assertThat(list.at("/properties/items/maxItems").asInt()).isEqualTo(100);
        assertThat(list.at("/properties/items/items/$ref").asText()).isEqualTo(SCHEMAS + "FavouriteForecast");
    }

    @Test
    @DisplayName("AC-22: FavouriteForecast requires id, city, location and refers to Forecast and the shared error schema")
    void itemSchema() {
        JsonNode item = schema("FavouriteForecast");
        assertThat(texts(item.path("required"))).containsExactlyInAnyOrder("id", "city", "location");
        List<String> properties = new ArrayList<>();
        item.path("properties").fieldNames().forEachRemaining(properties::add);
        assertThat(properties).containsExactly("id", "city", "label", "location", "forecast", "error");
        assertThat(item.at("/properties/location/$ref").asText()).isEqualTo(SCHEMAS + "Location");
        assertThat(item.at("/properties/forecast/$ref").asText()).isEqualTo(SCHEMAS + "Forecast");
        assertThat(item.at("/properties/error/$ref").asText()).isEqualTo(SCHEMAS + "FavouriteWeatherError");
        JsonNode weatherItem = schema("FavouriteWeather").path("properties");
        for (String copied : List.of("id", "city", "label")) {
            assertThat(item.path("properties").path(copied)).as(copied).isEqualTo(weatherItem.path(copied));
        }
    }

    @Test
    @DisplayName("AC-22/AC-23: the FavouriteId description notes that forecast is not an id")
    void favouriteIdDescription() {
        assertThat(spec.at("/components/parameters/FavouriteId/description").asText())
                .contains("\"forecast\"", "/api/v1/favourites/forecast");
    }
}
