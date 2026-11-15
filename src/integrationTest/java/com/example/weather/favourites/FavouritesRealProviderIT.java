package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * AC-32: with the real adapter against FakeOpenMeteo (cache on, empty cache), a create costs one
 * GEOCODING call and no weather calls, and the geocoding entry is shared with the weather endpoints.
 * Reuses the CachingIntegrationTestBase context.
 */
class FavouritesRealProviderIT extends CachingIntegrationTestBase {

    private final String clientId = "it-fav-real-" + UUID.randomUUID();

    @AfterEach
    void deleteRows() {
        jdbc.update("DELETE FROM favourite WHERE client_id = ?", clientId);
    }

    private ResponseEntity<String> create(String body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Client-Id", clientId);
        h.set(HttpHeaders.CONTENT_TYPE, "application/json");
        return http.exchange(URI.create("/api/v1/favourites"), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @Test
    @DisplayName("AC-32: create is 201 with the fake's geocoding location after 1 GEOCODING and 0 CURRENT/DAILY calls; a following current request makes no further GEOCODING call")
    void ac32_createUsesGeocodingOnlyAndSharesTheCache() {
        assertThat(runKeys()).isEmpty();

        ResponseEntity<String> res = create("{\"city\": \"London\"}");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode location = json(res).get("location");
        assertThat(location.get("name").asText()).isEqualTo(LONDON.name());
        assertThat(location.get("country").asText()).isEqualTo(LONDON.country());
        assertThat(location.get("latitude").asDouble()).isEqualTo(LONDON.latitude());
        assertThat(location.get("longitude").asDouble()).isEqualTo(LONDON.longitude());
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        assertThat(received(Route.DAILY)).isEmpty();
        assertThat(runKeys()).as("only the geocoding entry is written").containsExactly(cacheKeys.geocoding("London"));

        ResponseEntity<String> current = get("/api/v1/weather/current?city=London");

        assertThat(current.getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
    }

    @Test
    @DisplayName("AC-32 (edge): a create after a weather request reuses the cached geocoding entry (0 further upstream calls)")
    void ac32_edge_createAfterWeatherRequestIsCacheHit() {
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode().value()).isEqualTo(200);
        int before = fake.requests().size();

        assertThat(create("{\"city\": \"  LONDON \"}").getStatusCode().value()).isEqualTo(201);

        assertThat(fake.requests()).hasSize(before);
    }

    @Test
    @DisplayName("AC-32 (edge): an unknown city with the real adapter is 404 LOCATION_NOT_FOUND after 1 GEOCODING call; no row")
    void ac32_edge_unknownCity() {
        ResponseEntity<String> res = create("{\"city\": \"Atlantis\"}");

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(json(res).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM favourite WHERE client_id = ?", Integer.class, clientId);
        assertThat(rows).isZero();
    }
}
