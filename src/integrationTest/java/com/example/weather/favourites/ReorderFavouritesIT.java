package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

/** Reorder: AC-23, AC-24. */
class ReorderFavouritesIT extends FavouritesIntegrationTestBase {

    private static final String SET_MISMATCH = "ids: must contain every favourite id of this client exactly once";

    private String client;
    private String l;
    private String p;
    private String t;
    private JsonNode london;
    private JsonNode paris;
    private JsonNode tokyo;

    @BeforeEach
    void createLondonParisTokyo() {
        client = newClientId();
        london = create(client, "London");
        CLOCK.set(T0.plusSeconds(1));
        paris = create(client, "Paris");
        CLOCK.set(T0.plusSeconds(2));
        tokyo = create(client, "Tokyo");
        l = london.get("id").asText();
        p = paris.get("id").asText();
        t = tokyo.get("id").asText();
        clearInvocations(provider);
    }

    @Test
    @DisplayName("AC-23: reorder T, L, P is 200 with the list shape in that order; a later list agrees; createdAt/updatedAt unchanged")
    void ac23_reorder() {
        ResponseEntity<String> res = reorder(client, reorderBody(t, l, p));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("items")).containsExactly(tokyo, london, paris);
        assertThat(json(list(client))).isEqualTo(body);
        List<Map<String, Object>> rows = rows(client);
        assertThat(rows).extracting(r -> r.get("id").toString()).containsExactly(t, l, p);
        assertThat(rows).extracting(r -> ((Timestamp) r.get("created_at")).toInstant())
                .containsExactly(T0.plusSeconds(2), T0, T0.plusSeconds(1));
        verifyNoInteractions(provider);
    }

    static Stream<Arguments> invalidOrders() {
        // Each case builds the body from (l, p, t) of the current test, plus another client's id.
        return Stream.of(
                Arguments.of("an id is missing", (Function<String[], String>) ids -> reorderBody(ids[2], ids[0]),
                        SET_MISMATCH),
                Arguments.of("an unknown UUID is added",
                        (Function<String[], String>) ids -> reorderBody(ids[2], ids[0], ids[1], UUID.randomUUID().toString()),
                        SET_MISMATCH),
                Arguments.of("an id is listed twice",
                        (Function<String[], String>) ids -> reorderBody(ids[2], ids[0], ids[1], ids[0]),
                        "ids: must not contain duplicate ids"),
                Arguments.of("another client's id is included",
                        (Function<String[], String>) ids -> reorderBody(ids[2], ids[0], ids[1], ids[3]),
                        SET_MISMATCH),
                Arguments.of("another client's id replaces one",
                        (Function<String[], String>) ids -> reorderBody(ids[2], ids[0], ids[3]),
                        SET_MISMATCH),
                Arguments.of("an element is not a UUID",
                        (Function<String[], String>) ids -> reorderBody(ids[2], ids[0], "not-a-uuid"),
                        "ids: must contain only favourite ids (UUIDs)"),
                Arguments.of("an element is null",
                        (Function<String[], String>) ids -> "{\"ids\": [\"" + ids[2] + "\", \"" + ids[0] + "\", null]}",
                        "ids: must contain only favourite ids (UUIDs)"),
                Arguments.of("the ids key is missing", (Function<String[], String>) ids -> "{}", "ids: must not be null"),
                Arguments.of("ids is null", (Function<String[], String>) ids -> "{\"ids\": null}", "ids: must not be null"),
                Arguments.of("ids is [] while favourites exist", (Function<String[], String>) ids -> "{\"ids\": []}",
                        SET_MISMATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOrders")
    @DisplayName("AC-23: an invalid reorder is 400 VALIDATION_FAILED on field ids and the order stays L, P, T")
    void ac23_invalidReorder(String name, Function<String[], String> bodyOf, String expectedError) {
        String other = newClientId();
        String foreign = create(other, "Sydney").get("id").asText();
        List<Map<String, Object>> before = rows(client);

        JsonNode problem = assertProblem(reorder(client, bodyOf.apply(new String[] {l, p, t, foreign})), 400,
                "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly(expectedError);
        assertThat(listedIds(client)).containsExactly(l, p, t);
        assertThat(rows(client)).isEqualTo(before);
        assertThat(listedIds(other)).containsExactly(foreign);
    }

    @Test
    @DisplayName("AC-23: with no favourites, {\"ids\": []} is 200 {\"items\": []}")
    void ac23_emptyReorderWithNoFavourites() {
        ResponseEntity<String> res = reorder(newClientId(), "{\"ids\": []}");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res)).isEqualTo(JSON.createObjectNode().set("items", JSON.createArrayNode()));
    }

    @Test
    @DisplayName("AC-23 (edge): upper-case ids are accepted; the same id in two cases counts as a duplicate")
    void ac23_edge_caseOfIds() {
        ResponseEntity<String> res = reorder(client, reorderBody(t.toUpperCase(), l, p));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(listedIds(client)).containsExactly(t, l, p);

        JsonNode problem = assertProblem(reorder(client, reorderBody(t, l, p, l.toUpperCase())), 400,
                "VALIDATION_FAILED");
        assertThat(errors(problem)).containsExactly("ids: must not contain duplicate ids");
        assertThat(listedIds(client)).containsExactly(t, l, p);
    }

    @Test
    @DisplayName("AC-23 (edge): reorder does not change updatedAt, even with the clock moved")
    void ac23_edge_updatedAtUnchanged() {
        CLOCK.set(T0.plusSeconds(3600));

        JsonNode body = json(reorder(client, reorderBody(p, t, l)));

        assertThat(body.get("items")).containsExactly(paris, tokyo, london);
    }

    @Test
    @DisplayName("AC-24: after T, L, P a new favourite goes at the end; deleting L leaves T, P, Sydney")
    void ac24_appendAndDeleteKeepOrder() {
        assertThat(reorder(client, reorderBody(t, l, p)).getStatusCode().value()).isEqualTo(200);

        String s = create(client, "Sydney").get("id").asText();
        assertThat(listedIds(client)).containsExactly(t, l, p, s);

        assertThat(delete(client, l).getStatusCode().value()).isEqualTo(204);
        assertThat(listedIds(client)).containsExactly(t, p, s);
    }

    @Test
    @DisplayName("AC-24 (edge): a create after deleting the last favourite still appends at the end")
    void ac24_edge_appendAfterDeletingLast() {
        assertThat(delete(client, t).getStatusCode().value()).isEqualTo(204);

        String s = create(client, "Sydney").get("id").asText();

        assertThat(listedIds(client)).containsExactly(l, p, s);
    }
}
