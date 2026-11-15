package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** The per-client limit with {@code max-per-client=3} (AC-13); its own Spring context. */
@TestPropertySource(properties = "weather.favourites.max-per-client=3")
class FavouritesLimitIT extends FavouritesIntegrationTestBase {

    private String client;
    private String parisId;

    @BeforeEach
    void fillToLimit() {
        client = newClientId();
        create(client, "London");
        parisId = create(client, "Paris").get("id").asText();
        create(client, "Tokyo");
        clearInvocations(provider);
    }

    private static JsonNode assertLimit(ResponseEntity<String> res) {
        JsonNode problem = assertProblem(res, 409, "FAVOURITES_LIMIT_REACHED");
        assertThat(problem.get("type").asText())
                .isEqualTo("https://weather.example.com/problems/favourites-limit-reached");
        assertThat(problem.get("detail").asText()).contains("3");
        return problem;
    }

    @Test
    @DisplayName("AC-13: a 4th create is 409 FAVOURITES_LIMIT_REACHED naming 3, without a provider call; 3 rows remain")
    void ac13_limitReached() {
        assertLimit(post(client, "{\"city\": \"Sydney\"}"));

        verifyNoInteractions(provider);
        assertThat(rowCount(client)).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-13: another client can still create Sydney (201)")
    void ac13_otherClientUnaffected() {
        assertLimit(post(client, "{\"city\": \"Sydney\"}"));

        assertThat(post(newClientId(), "{\"city\": \"Sydney\"}").getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("AC-13: after deleting Paris the client's Sydney create is 201")
    void ac13_deleteFreesASlot() {
        assertLimit(post(client, "{\"city\": \"Sydney\"}"));
        assertThat(delete(client, parisId).getStatusCode().value()).isEqualTo(204);

        assertThat(post(client, "{\"city\": \"Sydney\"}").getStatusCode().value()).isEqualTo(201);
        assertThat(rowCount(client)).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-13: a city that is both a duplicate and over the limit is 409 FAVOURITE_ALREADY_EXISTS")
    void ac13_duplicateWinsOverLimit() {
        assertProblem(post(client, "{\"city\": \" tokyo \"}"), 409, "FAVOURITE_ALREADY_EXISTS");

        verifyNoInteractions(provider);
        assertThat(rowCount(client)).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-28: the 409 FAVOURITES_LIMIT_REACHED body conforms to the Problem schema")
    void ac28_limitProblemConforms() {
        OpenApiSchemaAssert schemas = new OpenApiSchemaAssert(json(get("/api/v1/openapi.json")));

        schemas.assertConforms(assertLimit(post(client, "{\"city\": \"Sydney\"}")), "Problem");
    }

    @Test
    @DisplayName("AC-13 (edge): two concurrent creates for the last free slot, both past the pre-check, give [201, 409 FAVOURITES_LIMIT_REACHED]; 3 rows")
    void ac13_edge_concurrentCreatesAtTheLimit() throws Exception {
        assertThat(delete(client, parisId).getStatusCode().value()).isEqualTo(204);
        CyclicBarrier bothLocating = new CyclicBarrier(2);
        doAnswer(inv -> {
            bothLocating.await(10, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(provider).locate(anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (String city : List.of("Sydney", "New York")) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return post(client, "{\"city\": \"" + city + "\"}");
                }));
            }
            go.countDown();
            List<ResponseEntity<String>> results = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(results.stream().map(r -> r.getStatusCode().value()).sorted().toList())
                    .as("%s", results).containsExactly(201, 409);
            results.stream().filter(r -> r.getStatusCode().value() == 409).forEach(FavouritesLimitIT::assertLimit);
            assertThat(rowCount(client)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }
}
