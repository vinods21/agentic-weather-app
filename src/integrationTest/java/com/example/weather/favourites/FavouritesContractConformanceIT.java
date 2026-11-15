package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * AC-28: real favourites bodies conform to the schemas the spec declares for their operation and
 * status (resolved through {@code components.responses}). The 409 FAVOURITES_LIMIT_REACHED body is
 * checked in {@code FavouritesLimitIT}, which has the max=3 context.
 */
class FavouritesContractConformanceIT extends FavouritesIntegrationTestBase {

    private JsonNode spec;
    private OpenApiSchemaAssert schemas;

    @BeforeEach
    void loadSpec() {
        spec = json(get("/api/v1/openapi.json"));
        schemas = new OpenApiSchemaAssert(spec);
    }

    private JsonNode declared(String path, String method, String status, String mediaType) {
        JsonNode response = schemas.resolve(spec.path("paths").path(path).path(method).path("responses").path(status));
        JsonNode schema = response.path("content").path(mediaType).path("schema");
        assertThat(schema.isMissingNode()).as("%s %s %s %s declared", method, path, status, mediaType).isFalse();
        return schema;
    }

    private void conforms(ResponseEntity<String> res, int status, String path, String method) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(status);
        String mediaType = status < 400 ? "application/json" : "application/problem+json";
        schemas.assertConforms(json(res), declared(path, method, String.valueOf(status), mediaType));
    }

    @Test
    @DisplayName("AC-28: 201 create, 200 get/list/rename/reorder bodies conform to Favourite / FavouriteList")
    void ac28_successBodiesConform() {
        String client = newClientId();
        ResponseEntity<String> created = post(client, "{\"city\": \"London\", \"label\": \"Home\"}");
        conforms(created, 201, BASE, "post");
        String id = json(created).get("id").asText();
        ResponseEntity<String> noLabel = post(client, "{\"city\": \"Paris\"}");
        conforms(noLabel, 201, BASE, "post");
        String p = json(noLabel).get("id").asText();

        conforms(getOne(client, id), 200, BASE + "/{id}", "get");
        conforms(list(client), 200, BASE, "get");
        conforms(list(newClientId()), 200, BASE, "get");
        conforms(patch(client, id, "{\"label\": \"Work\"}"), 200, BASE + "/{id}", "patch");
        conforms(patch(client, id, "{\"label\": null}"), 200, BASE + "/{id}", "patch");
        conforms(reorder(client, reorderBody(p, id)), 200, BASE + "/order", "put");
    }

    @Test
    @DisplayName("AC-28: 400 favourites bodies (body, header, path id, reorder) conform to Problem")
    void ac28_validationProblemsConform() {
        String client = newClientId();
        conforms(post(client, "{}"), 400, BASE, "post");
        conforms(post(client, "{\"city\":"), 400, BASE, "post");
        conforms(post(null, "{\"city\": \"London\"}"), 400, BASE, "post");
        conforms(list("a b"), 400, BASE, "get");
        conforms(getOne(client, "not-a-uuid"), 400, BASE + "/{id}", "get");
        conforms(patch(client, UUID.randomUUID(), "{}"), 400, BASE + "/{id}", "patch");
        conforms(reorder(client, "{\"ids\": [\"x\"]}"), 400, BASE + "/order", "put");
        conforms(delete(client, "not-a-uuid"), 400, BASE + "/{id}", "delete");
    }

    @Test
    @DisplayName("AC-28: 404 NOT_FOUND (get/rename/delete) and 404 LOCATION_NOT_FOUND (create) conform to Problem")
    void ac28_notFoundProblemsConform() {
        String client = newClientId();
        UUID missing = UUID.randomUUID();
        conforms(getOne(client, missing), 404, BASE + "/{id}", "get");
        conforms(patch(client, missing, "{\"label\": \"x\"}"), 404, BASE + "/{id}", "patch");
        conforms(delete(client, missing), 404, BASE + "/{id}", "delete");

        ResponseEntity<String> lnf = post(client, "{\"city\": \"Atlantis\"}");
        assertThat(json(lnf).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        conforms(lnf, 404, BASE, "post");
    }

    @Test
    @DisplayName("AC-28: 409 FAVOURITE_ALREADY_EXISTS and 503 UPSTREAM_UNAVAILABLE create bodies conform to Problem")
    void ac28_conflictAndUpstreamConform() {
        String client = newClientId();
        create(client, "London");
        ResponseEntity<String> dup = post(client, "{\"city\": \"london\"}");
        assertThat(json(dup).get("code").asText()).isEqualTo("FAVOURITE_ALREADY_EXISTS");
        conforms(dup, 409, BASE, "post");

        doThrow(new WeatherProviderUnavailableException("down", null)).when(provider).locate(anyString());
        conforms(post(client, "{\"city\": \"Paris\"}"), 503, BASE, "post");
    }

    @Test
    @DisplayName("AC-28 (edge): the 201 response declares a Location header and every favourites operation requires X-Client-Id")
    void ac28_edge_headersDocumented() {
        JsonNode created = spec.at("/paths/~1api~1v1~1favourites/post/responses/201/headers");
        assertThat(created.has("Location")).isTrue();
        spec.path("paths").fieldNames().forEachRemaining(path -> {
            if (!path.startsWith(BASE)) {
                return;
            }
            spec.path("paths").path(path).properties().forEach(op -> {
                if (op.getKey().equals("parameters")) {
                    return;
                }
                boolean hasClientId = false;
                java.util.List<JsonNode> params = new java.util.ArrayList<>();
                spec.path("paths").path(path).path("parameters").forEach(params::add);
                op.getValue().path("parameters").forEach(params::add);
                for (JsonNode param : params) {
                    JsonNode resolved = schemas.resolve(param);
                    hasClientId |= "X-Client-Id".equals(resolved.path("name").asText())
                            && "header".equals(resolved.path("in").asText())
                            && resolved.path("required").asBoolean();
                }
                assertThat(hasClientId).as("%s %s requires X-Client-Id", op.getKey(), path).isTrue();
            });
        });
    }
}
