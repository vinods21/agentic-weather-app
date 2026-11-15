package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.api.error.ErrorCode;
import com.example.weather.weather.ConditionCode;
import com.example.weather.weather.Units;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: parses the classpath spec with Jackson, no Spring context, no Docker. */
class OpenApiDocumentTest {

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

    @Test
    void hasVersionAndPaths() {
        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("version").asText()).isEqualTo("1.0.0");
        assertThat(spec.path("paths").has("/api/v1/weather/current")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/weather/forecast")).isTrue();
    }

    @Test
    void problemCodeEnumEqualsErrorCodes() {
        JsonNode codes = spec.at("/components/schemas/Problem/properties/code/enum");
        assertThat(texts(codes)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void conditionCodeEnumEqualsDomainCodes() {
        JsonNode codes = spec.at("/components/schemas/Condition/properties/code/enum");
        assertThat(texts(codes)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ConditionCode.values()).map(Enum::name).toList());
    }

    @Test
    void unitsEnumEqualsDomainUnits() {
        List<String> units = Arrays.stream(Units.values()).map(Units::value).toList();
        assertThat(texts(spec.at("/components/parameters/Units/schema/enum"))).containsExactlyElementsOf(units);
        assertThat(texts(spec.at("/components/schemas/CurrentWeather/properties/units/enum")))
                .containsExactlyElementsOf(units);
    }

    @Test
    @DisplayName("AC-28: the six favourites operations are documented and require X-Client-Id")
    void favouritesOperations() {
        JsonNode paths = spec.path("paths");
        assertThat(paths.at("/~1api~1v1~1favourites/get/operationId").asText()).isEqualTo("listFavourites");
        assertThat(paths.at("/~1api~1v1~1favourites/post/operationId").asText()).isEqualTo("createFavourite");
        assertThat(paths.at("/~1api~1v1~1favourites~1{id}/get/operationId").asText()).isEqualTo("getFavourite");
        assertThat(paths.at("/~1api~1v1~1favourites~1{id}/patch/operationId").asText()).isEqualTo("updateFavourite");
        assertThat(paths.at("/~1api~1v1~1favourites~1{id}/delete/operationId").asText()).isEqualTo("deleteFavourite");
        assertThat(paths.at("/~1api~1v1~1favourites~1order/put/operationId").asText()).isEqualTo("reorderFavourites");

        JsonNode clientId = spec.at("/components/parameters/ClientId");
        assertThat(clientId.path("name").asText()).isEqualTo("X-Client-Id");
        assertThat(clientId.path("in").asText()).isEqualTo("header");
        assertThat(clientId.path("required").asBoolean()).isTrue();
        assertThat(clientId.at("/schema/pattern").asText()).isEqualTo("^[A-Za-z0-9._-]{1,64}$");
        assertThat(clientId.at("/schema/maxLength").asInt()).isEqualTo(64);
    }

    @Test
    @DisplayName("AC-28: Favourite.location references Location; info.description is no longer read-only")
    void favouriteSchemasAndDescription() {
        assertThat(spec.at("/components/schemas/Favourite/properties/location/$ref").asText())
                .isEqualTo("#/components/schemas/Location");
        assertThat(texts(spec.at("/components/schemas/Favourite/required")))
                .containsExactlyInAnyOrder("id", "city", "location", "createdAt", "updatedAt");
        assertThat(spec.at("/components/schemas/FavouriteList/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/Favourite");
        for (String schema : List.of("CreateFavouriteRequest", "UpdateFavouriteRequest", "ReorderFavouritesRequest")) {
            assertThat(spec.at("/components/schemas/" + schema).isObject()).as(schema).isTrue();
        }
        String description = spec.at("/info/description").asText();
        assertThat(description).doesNotContain("Read-only").doesNotContain("GET and OPTIONS");
        assertThat(spec.at("/components/schemas/FieldError/properties/field/description").asText())
                .contains("header").contains("body");
    }

    @Test
    void missingDocumentFailsFast() {
        assertThatThrownBy(() -> OpenApiController.load("openapi/does-not-exist.json"))
                .isInstanceOf(UncheckedIOException.class);
    }
}
