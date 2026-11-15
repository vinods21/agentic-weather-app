package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Logging of GET /api/v1/favourites/weather: the partial-failure WARN (AC-11, stub path) and the
 * success path's single access line with no secrets (AC-21). Assertions look only at the output
 * captured after a mark taken just before the request.
 */
@ExtendWith(OutputCaptureExtension.class)
class FavouritesWeatherLoggingIT extends FavouritesIntegrationTestBase {

    private static final String WEATHER = BASE + "/weather";
    private static final Pattern INFO_OR_ABOVE = Pattern.compile("\\b(INFO|WARN|ERROR)\\b");
    private static final Pattern WARN_OR_ERROR = Pattern.compile("\\b(WARN|ERROR)\\b");

    private static Pattern accessLine(String requestId) {
        return Pattern.compile("\\bINFO\\b.* GET /api/v1/favourites/weather -> \\d{3} in \\d+ ms requestId="
                + Pattern.quote(requestId) + "$");
    }

    /** The captured lines after {@code mark}, once the request's access line has been written. */
    private static List<String> sliceAfterAccessLine(CapturedOutput output, int mark, String requestId)
            throws InterruptedException {
        Pattern access = accessLine(requestId);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && output.getAll().substring(mark).lines().noneMatch(l -> access.matcher(l.stripTrailing()).find())) {
            Thread.sleep(20);
        }
        Thread.sleep(100); // a second line, if any, would show up now
        return output.getAll().substring(mark).lines().toList();
    }

    @Test
    @DisplayName("AC-11: one failed item of 3 logs exactly one WARN with the request id, failed=1 and total=3, and no city, label, client id or coordinates")
    void ac11_partialFailureWarn(CapturedOutput output) throws Exception {
        String requestId = "it-faw-11";
        String client = newClientId();
        send(HttpMethod.POST, BASE, client, "{\"city\": \"London\", \"label\": \"LBL-SECRET11\"}", Map.of());
        create(client, "Paris");
        create(client, "Tokyo");
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).currentAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any());

        int mark = output.getAll().length();
        ResponseEntity<String> res = send(HttpMethod.GET, WEATHER, client, null, Map.of("X-Request-Id", requestId));
        List<String> lines = sliceAfterAccessLine(output, mark, requestId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<String> warns = lines.stream().filter(l -> WARN_OR_ERROR.matcher(l).find()).toList();
        assertThat(warns).singleElement().satisfies(l -> {
            assertThat(l).contains("WARN").contains("[requestId=" + requestId + "]")
                    .contains("Favourites weather partial failure failed=1 total=3 timedOut=0");
            assertThat(l).doesNotContain("London").doesNotContain("Paris").doesNotContain("Tokyo")
                    .doesNotContain("LBL-SECRET11").doesNotContain(client)
                    .doesNotContain("48.8566").doesNotContain("2.3522").doesNotContain("51.5074");
        });
    }

    @Test
    @DisplayName("AC-21: a fully successful request echoes X-Request-Id, writes exactly one INFO access line (path without query) and no WARN; client id and label never appear at INFO+")
    void ac21_successLogging(CapturedOutput output) throws Exception {
        String requestId = "it-faw-21";
        String client = track("cid-SECRET21");
        String label = "LBL-SECRET21";
        jdbc.update("DELETE FROM favourite WHERE client_id = ?", client);
        ResponseEntity<String> created = send(HttpMethod.POST, BASE, client,
                "{\"city\": \"London\", \"label\": \"" + label + "\"}", Map.of());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        create(client, "Paris");

        int mark = output.getAll().length();
        ResponseEntity<String> res = send(HttpMethod.GET, WEATHER + "?units=imperial&page=2", client, null,
                Map.of("X-Request-Id", requestId));
        List<String> lines = sliceAfterAccessLine(output, mark, requestId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains(label); // the label is in the body, so its absence from logs is meaningful
        assertThat(res.getHeaders().get("X-Request-Id")).containsExactly(requestId);
        Pattern access = accessLine(requestId);
        List<String> accessLines = lines.stream().filter(l -> access.matcher(l.stripTrailing()).find()).toList();
        assertThat(accessLines).hasSize(1);
        assertThat(accessLines.get(0)).doesNotContain("units").doesNotContain("page=").doesNotContain("?");
        assertThat(lines).as("no WARN/ERROR").noneMatch(l -> WARN_OR_ERROR.matcher(l).find());
        assertThat(output.getAll().lines().filter(l -> INFO_OR_ABOVE.matcher(l).find()))
                .noneMatch(l -> l.contains(client)).noneMatch(l -> l.contains(label));
    }
}
