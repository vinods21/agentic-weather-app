package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

/** Rename: AC-21, AC-22. */
class RenameFavouriteIT extends FavouritesIntegrationTestBase {

    private static final Instant T1 = Instant.parse("2026-11-15T11:00:00Z");

    @Test
    @DisplayName("AC-21: rename sets the label and updatedAt only; the row and a later get match; null clears; city can't change; no provider call")
    void ac21_rename() {
        String client = newClientId();
        JsonNode created = json(post(client, "{\"city\": \"London\", \"label\": \"Home\"}"));
        String id = created.get("id").asText();
        CLOCK.set(T1);
        clearInvocations(provider);

        ResponseEntity<String> res = patch(client, id, "{\"label\": \"Work\"}");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode renamed = json(res);
        ObjectNode expected = created.deepCopy();
        expected.put("label", "Work").put("updatedAt", "2026-11-15T11:00:00Z");
        assertThat(renamed).isEqualTo(expected);
        Map<String, Object> row = rows(client).get(0);
        assertThat(row.get("label")).isEqualTo("Work");
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(T1);
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(T0);
        assertThat(json(getOne(client, id))).isEqualTo(renamed);

        ResponseEntity<String> cleared = patch(client, id, "{\"label\": null}");
        assertThat(cleared.getStatusCode().value()).isEqualTo(200);
        assertThat(json(cleared).has("label")).isFalse();
        assertThat(rows(client).get(0).get("label")).isNull();

        ResponseEntity<String> withCity = patch(client, id,
                "{\"label\": \"Work\", \"city\": \"Paris\", \"location\": {\"name\": \"Paris\"}, \"createdAt\": \"2000-01-01T00:00:00Z\"}");
        assertThat(withCity.getStatusCode().value()).isEqualTo(200);
        assertThat(json(withCity)).isEqualTo(expected);
        Map<String, Object> after = rows(client).get(0);
        assertThat(after.get("city")).isEqualTo("London");
        assertThat(after.get("city_key")).isEqualTo("london");
        assertThat(after.get("location_name")).isEqualTo("London");

        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-21 (edge): a label is stripped on rename")
    void ac21_edge_labelStripped() {
        String client = newClientId();
        String id = create(client, "Paris").get("id").asText();

        JsonNode body = json(patch(client, id, "{\"label\": \"  Office  \"}"));

        assertThat(body.get("label").asText()).isEqualTo("Office");
    }

    static Stream<Arguments> invalidRenameBodies() {
        return Stream.of(
                Arguments.of("{}", List.of("label: must be present (null clears the label)")),
                Arguments.of("{\"city\": \"Paris\"}", List.of("label: must be present (null clears the label)")),
                Arguments.of("{\"label\": \"   \"}", List.of("label: must not be blank")),
                Arguments.of("{\"label\": \"" + "x".repeat(51) + "\"}", List.of("label: must be at most 50 characters")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRenameBodies")
    @DisplayName("AC-22: an absent, blank or too long label is 400 VALIDATION_FAILED with the listed errors; row unchanged")
    void ac22_invalidLabel(String body, List<String> expected) {
        String client = newClientId();
        String id = json(post(client, "{\"city\": \"London\", \"label\": \"Home\"}")).get("id").asText();
        List<Map<String, Object>> before = rows(client);
        CLOCK.set(T1);

        JsonNode problem = assertProblem(patch(client, id, body), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactlyElementsOf(expected);
        assertThat(rows(client)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-22: an unknown id or another client's id is 404 NOT_FOUND and no row changes")
    void ac22_unknownOrForeignId() {
        String owner = newClientId();
        String id = json(post(owner, "{\"city\": \"London\", \"label\": \"Home\"}")).get("id").asText();
        List<Map<String, Object>> before = rows(owner);
        CLOCK.set(T1);

        assertProblem(patch(owner, UUID.randomUUID(), "{\"label\": \"Work\"}"), 404, "NOT_FOUND");
        assertProblem(patch(newClientId(), id, "{\"label\": \"Work\"}"), 404, "NOT_FOUND");

        assertThat(rows(owner)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-22 (edge): a non-UUID id on PATCH is 400 on field id")
    void ac22_edge_notAUuid() {
        JsonNode problem = assertProblem(patch(newClientId(), "not-a-uuid", "{\"label\": \"Work\"}"), 400,
                "VALIDATION_FAILED");
        assertThat(errors(problem)).containsExactly("id: has an invalid value");
    }
}
