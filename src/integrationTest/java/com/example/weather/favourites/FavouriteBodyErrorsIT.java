package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** Unreadable bodies: AC-5 (create) and the malformed-body part of AC-22 (rename); reorder as an edge. */
class FavouriteBodyErrorsIT extends FavouritesIntegrationTestBase {

    private static final List<String> BODY_ERROR = List.of("body: must be a JSON object matching the request schema");

    private static void assertUnreadable(ResponseEntity<String> res) {
        JsonNode problem = assertProblem(res, 400, "VALIDATION_FAILED");
        assertThat(errors(problem)).isEqualTo(BODY_ERROR);
        assertThat(res.getBody())
                .doesNotContainIgnoringCase("jackson")
                .doesNotContain("com.")
                .doesNotContain("java.")
                .doesNotContain("London")
                .doesNotContain("{\"city\"")
                .doesNotContainIgnoringCase("exception");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "{\"city\":", "[]", "{\"city\": [\"London\"]}"})
    @DisplayName("AC-5: an empty, invalid-JSON, array or wrongly typed create body is 400 with one fixed 'body' error; no row")
    void ac5_unreadableCreateBody(String body) {
        String client = newClientId();

        assertUnreadable(post(client, body));

        assertThat(rowCount(client)).isZero();
        verifyNoInteractions(provider);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "{\"label\":", "[]", "{\"label\": [\"Work\"]}"})
    @DisplayName("AC-22: a malformed rename body behaves as in AC-5 and leaves the row unchanged")
    void ac22_unreadableRenameBody(String body) {
        String client = newClientId();
        String id = create(client, "London").get("id").asText();
        String before = getOne(client, id).getBody();

        assertUnreadable(patch(client, id, body));

        assertThat(getOne(client, id).getBody()).isEqualTo(before);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "{\"ids\":", "[]", "{\"ids\": \"abc\"}", "{\"ids\": {\"a\": 1}}"})
    @DisplayName("AC-5 (edge): a malformed reorder body is 400 with the fixed 'body' error and the order is unchanged")
    void ac5_edge_unreadableReorderBody(String body) {
        String client = newClientId();
        String l = create(client, "London").get("id").asText();
        String p = create(client, "Paris").get("id").asText();
        clearInvocations(provider);

        ResponseEntity<String> res = reorder(client, body);

        JsonNode problem = assertProblem(res, 400, "VALIDATION_FAILED");
        assertThat(errors(problem)).isEqualTo(BODY_ERROR);
        assertThat(listedIds(client)).containsExactly(l, p);
        verifyNoInteractions(provider);
    }
}
