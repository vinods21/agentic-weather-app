package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Delete: AC-25. */
class DeleteFavouriteIT extends FavouritesIntegrationTestBase {

    @Test
    @DisplayName("AC-25: delete is 204 with an empty body and X-Request-Id; the row is gone; get and a second delete are 404")
    void ac25_delete() {
        String client = newClientId();
        String id = create(client, "London").get("id").asText();
        String keep = create(client, "Paris").get("id").asText();

        ResponseEntity<String> res = send(HttpMethod.DELETE, BASE + "/" + id, client, null,
                Map.of("X-Request-Id", "it-fav-25"));

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        assertThat(res.getBody()).isNullOrEmpty();
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo("it-fav-25");
        assertThat(rows(client)).extracting(r -> r.get("id").toString()).containsExactly(keep);

        JsonNode afterGet = assertProblem(getOne(client, id), 404, "NOT_FOUND");
        assertThat(afterGet.get("detail").asText()).isEqualTo("No favourite exists with this id.");
        assertProblem(delete(client, id), 404, "NOT_FOUND");
        assertThat(rowCount(client)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-25: a non-UUID id on delete is 400 VALIDATION_FAILED on field id")
    void ac25_notAUuid() {
        JsonNode problem = assertProblem(delete(newClientId(), "not-a-uuid"), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly("id: has an invalid value");
    }

    @Test
    @DisplayName("AC-25 (edge): a deleted city can be created again (the unique key is freed)")
    void ac25_edge_recreateAfterDelete() {
        String client = newClientId();
        String id = create(client, "London").get("id").asText();
        assertThat(delete(client, id).getStatusCode().value()).isEqualTo(204);

        JsonNode again = create(client, "London");

        assertThat(again.get("id").asText()).isNotEqualTo(id);
        assertThat(rowCount(client)).isEqualTo(1);
    }
}
