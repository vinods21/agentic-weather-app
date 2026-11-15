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
    void missingDocumentFailsFast() {
        assertThatThrownBy(() -> OpenApiController.load("openapi/does-not-exist.json"))
                .isInstanceOf(UncheckedIOException.class);
    }
}
