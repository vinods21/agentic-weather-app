package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: Bean Validation on the favourites request bodies, plain Hibernate Validator and Jackson. No Spring. */
class FavouriteRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    /** Sorted "field: message" entries, as the API reports them. */
    private static Set<String> errors(Object body) {
        return VALIDATOR.validate(body).stream()
                .map(FavouriteRequestValidationTest::format)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String format(ConstraintViolation<?> v) {
        return v.getPropertyPath() + ": " + v.getMessage();
    }

    private static CreateFavouriteRequest create(String json) throws Exception {
        return JSON.readValue(json, CreateFavouriteRequest.class);
    }

    private static UpdateFavouriteRequest update(String json) throws Exception {
        return JSON.readValue(json, UpdateFavouriteRequest.class);
    }

    @Test
    @DisplayName("AC-4: each invalid create body gives exactly the listed errors")
    void createTable() throws Exception {
        assertThat(errors(create("{}"))).containsExactly("city: must not be blank");
        assertThat(errors(create("{\"city\": \"   \"}"))).containsExactly("city: must not be blank");
        assertThat(errors(create("{\"city\": \"" + "a".repeat(101) + "\"}")))
                .containsExactly("city: must be at most 100 characters");
        assertThat(errors(create("{\"city\": \"London\", \"label\": \"   \"}")))
                .containsExactly("label: must not be blank");
        assertThat(errors(create("{\"city\": \"London\", \"label\": \"" + "a".repeat(51) + "\"}")))
                .containsExactly("label: must be at most 50 characters");
        assertThat(errors(create("{\"city\": \"\", \"label\": \"\"}")))
                .containsExactly("city: must not be blank", "label: must not be blank");
    }

    @Test
    @DisplayName("AC-3, AC-4: boundaries are inclusive and counted after stripping; a missing or null label is valid")
    void createBoundaries() throws Exception {
        assertThat(errors(create("{\"city\": \"  " + "a".repeat(100) + "  \"}"))).isEmpty();
        assertThat(errors(create("{\"city\": \"London\", \"label\": \" " + "a".repeat(50) + " \"}"))).isEmpty();
        assertThat(errors(create("{\"city\": \"Paris\"}"))).isEmpty();
        assertThat(errors(create("{\"city\": \"Paris\", \"label\": null}"))).isEmpty();

        CreateFavouriteRequest r = create("{\"city\": \"  london  \", \"label\": \" Home \"}");
        assertThat(r.city()).isEqualTo("london");
        assertThat(r.label()).isEqualTo("Home");
        assertThat(create("{\"city\": \"Paris\", \"label\": null}").label()).isNull();
    }

    @Test
    @DisplayName("AC-22: a PATCH body without label is invalid; null is present and valid")
    void updatePresence() throws Exception {
        assertThat(errors(update("{}"))).containsExactly("label: must be present (null clears the label)");
        assertThat(errors(update("{\"city\": \"Paris\"}")))
                .containsExactly("label: must be present (null clears the label)");

        UpdateFavouriteRequest cleared = update("{\"label\": null}");
        assertThat(cleared.labelPresent()).isTrue();
        assertThat(cleared.label()).isNull();
        assertThat(errors(cleared)).isEmpty();
    }

    @Test
    @DisplayName("AC-21, AC-22: PATCH labels are stripped and use the create rules; other properties are ignored")
    void updateRules() throws Exception {
        UpdateFavouriteRequest work = update("{\"label\": \" Work \", \"city\": \"Paris\"}");
        assertThat(work.label()).isEqualTo("Work");
        assertThat(errors(work)).isEmpty();
        assertThat(errors(update("{\"label\": \"   \"}"))).containsExactly("label: must not be blank");
        assertThat(errors(update("{\"label\": \"" + "a".repeat(51) + "\"}")))
                .containsExactly("label: must be at most 50 characters");
        assertThat(errors(update("{\"label\": \"" + "a".repeat(50) + "\"}"))).isEmpty();
    }

    @Test
    @DisplayName("AC-23: ids must be present and at most 100 elements")
    void reorder() {
        assertThat(errors(new ReorderFavouritesRequest(null))).containsExactly("ids: must not be null");
        assertThat(errors(new ReorderFavouritesRequest(List.of()))).isEmpty();
        assertThat(errors(new ReorderFavouritesRequest(Arrays.asList(new String[100])))).isEmpty();
        assertThat(errors(new ReorderFavouritesRequest(Arrays.asList(new String[101]))))
                .containsExactly("ids: must contain at most 100 ids");
    }
}
