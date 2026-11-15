package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC-19: everything except create works while the provider is down, and never calls it. */
class FavouritesNoUpstreamIT extends FavouritesIntegrationTestBase {

    @Test
    @DisplayName("AC-19: with the provider throwing on every call, list, get, rename, reorder and delete succeed without calling it")
    void ac19_noUpstreamDependency() {
        String client = newClientId();
        String l = create(client, "London").get("id").asText();
        String p = create(client, "Paris").get("id").asText();

        WeatherProviderUnavailableException down = new WeatherProviderUnavailableException("down", null);
        doThrow(down).when(provider).locate(anyString());
        doThrow(down).when(provider).current(anyString());
        doThrow(down).when(provider).currentAt(anyString(), any());
        doThrow(down).when(provider).forecast(anyString(), any(), anyInt());
        clearInvocations(provider);

        assertThat(list(client).getStatusCode().value()).isEqualTo(200);
        assertThat(getOne(client, l).getStatusCode().value()).isEqualTo(200);
        assertThat(patch(client, l, "{\"label\": \"Home\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(reorder(client, reorderBody(p, l)).getStatusCode().value()).isEqualTo(200);
        assertThat(delete(client, p).getStatusCode().value()).isEqualTo(204);

        verifyNoInteractions(provider);
        assertThat(listedIds(client)).containsExactly(l);
    }

    @Test
    @DisplayName("AC-19 (edge): with the provider down, a create is 503 but a duplicate create is still 409 (checked first, no provider call)")
    void ac19_edge_duplicateCheckedBeforeProvider() {
        String client = newClientId();
        create(client, "London");
        doThrow(new WeatherProviderUnavailableException("down", null)).when(provider).locate(anyString());
        clearInvocations(provider);

        assertProblem(post(client, "{\"city\": \"LONDON\"}"), 409, "FAVOURITE_ALREADY_EXISTS");
        verifyNoInteractions(provider);
        assertProblem(post(client, "{\"city\": \"Paris\"}"), 503, "UPSTREAM_UNAVAILABLE");
        assertThat(rowCount(client)).isEqualTo(1);
    }
}
