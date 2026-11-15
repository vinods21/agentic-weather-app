package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * AC-15 (database failure): loading the favourites fails, so the whole request is 500 and no provider call is made.
 *
 * <p>{@code @DirtiesContext}: this class has its own context (no other class shares its configuration);
 * closing it after the class releases its Hikari pool, so the run stays under Postgres's
 * {@code max_connections} (each cached context keeps its pool open).
 */
@DirtiesContext
class FavouritesWeatherDbFailureIT extends FavouritesIntegrationTestBase {

    @MockitoSpyBean
    FavouriteRepository repository;

    @Test
    @DisplayName("AC-15: a database failure while loading the favourites is 500 INTERNAL_ERROR (no detail leaked) and the provider is not called")
    void ac15_databaseFailureIs500() {
        String client = newClientId();
        create(client, "London");
        create(client, "Paris");
        clearInvocations(provider);
        doThrow(new DataAccessResourceFailureException("db-SECRET15 down")).when(repository).findAll(anyString());

        ResponseEntity<String> res = send(HttpMethod.GET, BASE + "/weather", client, null, Map.of());

        JsonNode problem = assertProblem(res, 500, "INTERNAL_ERROR");
        assertThat(res.getBody()).doesNotContain("db-SECRET15").doesNotContain("DataAccess");
        assertThat(problem.get("requestId").asText()).isNotBlank();
        verifyNoInteractions(provider);
    }
}
