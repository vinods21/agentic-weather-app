package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** List and get: AC-17, AC-18, AC-20. */
class ListGetFavouritesIT extends FavouritesIntegrationTestBase {

    @Test
    @DisplayName("AC-17: with no favourites the list is 200 {\"items\": []}")
    void ac17_emptyList() {
        ResponseEntity<String> res = list(newClientId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res)).isEqualTo(JSON.createObjectNode().set("items", JSON.createArrayNode()));
    }

    @Test
    @DisplayName("AC-18: the list holds the favourites in creation order, each equal to its create body; page/size are ignored")
    void ac18_listInCreationOrder() {
        String client = newClientId();
        JsonNode london = create(client, "London");
        CLOCK.set(T0.plusSeconds(1));
        JsonNode paris = create(client, "Paris");
        CLOCK.set(T0.plusSeconds(2));
        JsonNode tokyo = create(client, "Tokyo");

        ResponseEntity<String> res = list(client);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("items")).containsExactly(london, paris, tokyo);

        ResponseEntity<String> paged = send(HttpMethod.GET, BASE + "?page=2&size=1", client, null, Map.of());
        assertThat(paged.getStatusCode().value()).isEqualTo(200);
        assertThat(json(paged)).isEqualTo(body);
    }

    @Test
    @DisplayName("AC-18 (edge): creation order holds even when every create has the same timestamp")
    void ac18_edge_orderWithEqualTimestamps() {
        String client = newClientId();
        String[] cities = {"Tokyo", "London", "Sydney", "Paris", "New York"};
        String[] ids = new String[cities.length];
        for (int i = 0; i < cities.length; i++) {
            ids[i] = create(client, cities[i]).get("id").asText();
        }

        assertThat(listedIds(client)).containsExactly(ids);
    }

    @Test
    @DisplayName("AC-20: get by id is 200 with the create body")
    void ac20_getById() {
        String client = newClientId();
        CLOCK.set(T0.plusSeconds(5));
        JsonNode created = json(post(client, "{\"city\": \"Sydney\", \"label\": \"Beach\"}"));

        ResponseEntity<String> res = getOne(client, created.get("id").asText());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(json(res)).isEqualTo(created);
    }

    @Test
    @DisplayName("AC-20: an unknown UUID is 404 NOT_FOUND with a favourite-specific detail, not the routing detail")
    void ac20_unknownId() {
        String client = newClientId();
        create(client, "London");

        JsonNode problem = assertProblem(getOne(client, UUID.randomUUID()), 404, "NOT_FOUND");

        assertThat(problem.get("detail").asText()).isEqualTo("No favourite exists with this id.")
                .isNotEqualTo("No endpoint exists at this path.");
        JsonNode routing = json(get("/api/v1/does-not-exist"));
        assertThat(problem.get("detail")).isNotEqualTo(routing.get("detail"));
    }

    @Test
    @DisplayName("AC-20: a non-UUID id is 400 VALIDATION_FAILED with errors [{id, has an invalid value}]")
    void ac20_notAUuid() {
        JsonNode problem = assertProblem(getOne(newClientId(), "not-a-uuid"), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly("id: has an invalid value");
        // Only `instance` (the request path) may carry the raw id; detail and errors never echo it.
        assertThat(problem.get("detail").asText()).doesNotContain("not-a-uuid");
        assertThat(problem.get("errors").toString()).doesNotContain("not-a-uuid");
    }

    @Test
    @DisplayName("AC-20 (edge): an upper-case form of an existing id finds the same favourite")
    void ac20_edge_upperCaseId() {
        String client = newClientId();
        JsonNode created = create(client, "London");

        ResponseEntity<String> res = getOne(client, created.get("id").asText().toUpperCase());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res)).isEqualTo(created);
    }
}
