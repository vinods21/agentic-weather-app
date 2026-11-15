package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** AC-26: 405 on unmapped methods, with the Allow header and a Problem body. Creates no rows. */
class FavouritesRoutingIT extends ApiIntegrationTestBase {

    private static final Map<String, String> CLIENT = Map.of("X-Client-Id", "it-fav-routing");

    private static void assertMethodNotAllowed(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(405);
        assertThat(String.valueOf(res.getHeaders().getContentType())).startsWith("application/problem+json");
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(body.get("status").asInt()).isEqualTo(405);
        assertThat(body.get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("AC-26: PUT /api/v1/favourites is 405 with Allow exactly GET, POST and a Problem body")
    void ac26_putOnCollection() {
        ResponseEntity<String> res = exchange(HttpMethod.PUT, "/api/v1/favourites", CLIENT);

        assertMethodNotAllowed(res);
        assertThat(res.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    @DisplayName("AC-26: POST /api/v1/favourites/<uuid> is 405 with Allow containing GET, PATCH and DELETE")
    void ac26_postOnItem() {
        ResponseEntity<String> res = exchange(HttpMethod.POST, "/api/v1/favourites/" + UUID.randomUUID(), CLIENT);

        assertMethodNotAllowed(res);
        assertThat(res.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("AC-26 (edge): the 405 bodies conform to the Problem schema; DELETE on the collection is 405 too")
    void ac26_edge_bodiesConform() {
        OpenApiSchemaAssert schemas = new OpenApiSchemaAssert(json(get("/api/v1/openapi.json")));

        ResponseEntity<String> deleteCollection = exchange(HttpMethod.DELETE, "/api/v1/favourites", CLIENT);
        assertMethodNotAllowed(deleteCollection);
        schemas.assertConforms(json(deleteCollection), "Problem");
        schemas.assertConforms(json(exchange(HttpMethod.PUT, "/api/v1/favourites", CLIENT)), "Problem");
        schemas.assertConforms(json(exchange(HttpMethod.POST, "/api/v1/favourites/" + UUID.randomUUID(), CLIENT)),
                "Problem");
    }

    @Test
    @DisplayName("AC-26 (edge, documented): GET /api/v1/favourites/order matches {id} and is 400 on field id")
    void ac26_edge_getOrderIsBadId() {
        ResponseEntity<String> res = get("/api/v1/favourites/order", CLIENT);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("id");
    }
}
