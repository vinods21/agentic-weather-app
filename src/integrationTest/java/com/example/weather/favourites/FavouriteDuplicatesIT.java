package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Duplicates: AC-9, AC-10; concurrent duplicates: AC-12. */
class FavouriteDuplicatesIT extends FavouritesIntegrationTestBase {

    /** Plain concurrent repetitions (AC-12 asks for 10; more make a lost race likelier to show). */
    private static final int REPETITIONS = 25;
    /** Repetitions in which both requests are held inside locate until both passed the pre-check. */
    private static final int FORCED_REPETITIONS = 10;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private static void assertDuplicate(ResponseEntity<String> res) {
        JsonNode problem = assertProblem(res, 409, "FAVOURITE_ALREADY_EXISTS");
        assertThat(problem.get("type").asText())
                .isEqualTo("https://weather.example.com/problems/favourite-already-exists");
        assertThat(problem.get("detail").asText()).isEqualTo("A favourite for this city already exists.");
    }

    @Test
    @DisplayName("AC-9: London then 'london' and '  LONDON ' are 409 FAVOURITE_ALREADY_EXISTS with a fixed detail; one row; one provider call")
    void ac9_caseAndWhitespaceDuplicates() {
        String client = newClientId();
        assertThat(post(client, "{\"city\": \"London\"}").getStatusCode().value()).isEqualTo(201);

        for (String city : List.of("london", "  LONDON ")) {
            ResponseEntity<String> res = post(client, "{\"city\": \"" + city + "\"}");
            assertDuplicate(res);
            assertThat(res.getBody()).doesNotContainIgnoringCase("london");
        }

        assertThat(rowCount(client)).isEqualTo(1);
        verify(provider, times(1)).locate(anyString());
    }

    @Test
    @DisplayName("AC-9: 'New York' then 'new   york' gives 201 then 409; the provider is called once")
    void ac9_collapsedWhitespaceDuplicate() {
        String client = newClientId();

        assertThat(post(client, "{\"city\": \"New York\"}").getStatusCode().value()).isEqualTo(201);
        assertDuplicate(post(client, "{\"city\": \"new   york\"}"));

        assertThat(rowCount(client)).isEqualTo(1);
        verify(provider, times(1)).locate(anyString());
    }

    @Test
    @DisplayName("AC-9 (edge): a duplicate with a different label is still a duplicate and does not change the stored label")
    void ac9_edge_duplicateWithOtherLabel() {
        String client = newClientId();
        assertThat(post(client, "{\"city\": \"Paris\", \"label\": \"Old\"}").getStatusCode().value()).isEqualTo(201);

        assertDuplicate(post(client, "{\"city\": \"PARIS\", \"label\": \"New\"}"));

        assertThat(rows(client)).singleElement().satisfies(r -> assertThat(r.get("label")).isEqualTo("Old"));
    }

    @Test
    @DisplayName("AC-10: client B can create a city that client A already has")
    void ac10_duplicatesArePerClient() {
        String a = newClientId();
        String b = newClientId();
        assertThat(post(a, "{\"city\": \"London\"}").getStatusCode().value()).isEqualTo(201);

        assertThat(post(b, "{\"city\": \"London\"}").getStatusCode().value()).isEqualTo(201);

        assertThat(rowCount(a)).isEqualTo(1);
        assertThat(rowCount(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-12: two concurrent creates of London for one client give exactly [201, 409], never 500, and one row (repeated)")
    void ac12_concurrentDuplicates() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            String client = newClientId();
            assertOneWinner(client, race(client, "London", "London"), i);
        }
    }

    @Test
    @DisplayName("AC-12: with both creates held inside locate until both passed the pre-check, the loser still gets 409 (lock + re-check), one row (repeated)")
    void ac12_forcedRaceAfterPreCheck() throws Exception {
        for (int i = 0; i < FORCED_REPETITIONS; i++) {
            CyclicBarrier bothLocating = new CyclicBarrier(2);
            reset(provider);
            doAnswer(inv -> {
                bothLocating.await(10, TimeUnit.SECONDS);
                return inv.callRealMethod();
            }).when(provider).locate(anyString());
            String client = newClientId();

            List<ResponseEntity<String>> results = race(client, "London", " london ");

            assertOneWinner(client, results, i);
            verify(provider, times(2)).locate(anyString());
        }
    }

    private List<ResponseEntity<String>> race(String client, String cityA, String cityB) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (String city : List.of(cityA, cityB)) {
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
        return results;
    }

    private void assertOneWinner(String client, List<ResponseEntity<String>> results, int repetition) {
        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).sorted().toList();
        assertThat(statuses).as("repetition %d: %s", repetition, results).containsExactly(201, 409);
        results.stream().filter(r -> r.getStatusCode().value() == 409).forEach(FavouriteDuplicatesIT::assertDuplicate);
        assertThat(rowCount(client)).as("repetition %d rows", repetition).isEqualTo(1);
    }
}
