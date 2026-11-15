package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.api.v1.dto.FavouriteResponse;
import com.example.weather.favourites.Favourite;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test (tester edge cases): Bean Validation and Jackson only. No Spring, no Docker. */
class FavouriteRequestEdgeCaseTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    /** Configured like Boot's mapper for java.time: ISO strings, not timestamps. */
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> errors(Object body) {
        return VALIDATOR.validate(body).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("AC-4 (edge): Unicode whitespace (EM SPACE, IDEOGRAPHIC SPACE, tab) is stripped from city and label")
    void unicodeWhitespaceIsStripped() throws Exception {
        CreateFavouriteRequest req = JSON.readValue(
                "{\"city\": \"\\u2003London\\t\", \"label\": \"\\u3000Home\\u3000\"}", CreateFavouriteRequest.class);

        assertThat(req.city()).isEqualTo("London");
        assertThat(req.label()).isEqualTo("Home");
        assertThat(errors(req)).isEmpty();
    }

    @Test
    @DisplayName("AC-4 (edge): a label of only Unicode whitespace is blank; a city of only Unicode whitespace is blank")
    void unicodeWhitespaceOnlyIsBlank() throws Exception {
        CreateFavouriteRequest req = JSON.readValue(
                "{\"city\": \"\\u2003\\u2003\", \"label\": \"\\u3000\"}", CreateFavouriteRequest.class);

        assertThat(errors(req)).containsExactly("city: must not be blank", "label: must not be blank");
    }

    @Test
    @DisplayName("AC-22 (edge): a PATCH label of only Unicode whitespace is blank, not a clear")
    void patchUnicodeWhitespaceLabelIsBlank() throws Exception {
        UpdateFavouriteRequest req = JSON.readValue("{\"label\": \"\\u2003 \"}", UpdateFavouriteRequest.class);

        assertThat(req.labelPresent()).isTrue();
        assertThat(errors(req)).containsExactly("label: must not be blank");
    }

    @Test
    @DisplayName("AC-2, AC-3 (edge): the response serialises the id in lower case, ISO-8601 Z timestamps, and omits a null label")
    void responseSerialisation() {
        UUID id = UUID.fromString("0B6F2C9E-3A1D-4C8E-9F12-5D7A8B9C0E1F");
        Instant t = Instant.parse("2026-11-15T10:00:00Z");
        Favourite f = new Favourite(id, "c", "london", null, new Location("London", "GB", 51.5074, -0.1278), 1, t, t);

        JsonNode body = JSON.valueToTree(FavouriteResponse.from(f));

        assertThat(body.get("id").asText()).isEqualTo("0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f");
        assertThat(body.has("label")).isFalse();
        assertThat(body.get("createdAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(body.get("location").get("latitude").asDouble()).isEqualTo(51.5074);
    }
}
