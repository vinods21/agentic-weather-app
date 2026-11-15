package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * Logging of GET /api/v1/favourites/forecast: the partial-failure WARN (AC-14, stub path) and the
 * success path's single access line with no secrets (AC-24). Shares the
 * {@link FavouritesIntegrationTestBase} context ({@code OutputCaptureExtension} is an extension, not a
 * context customisation). Assertions look only at the output captured after a mark taken just before
 * the request.
 */
@ExtendWith(OutputCaptureExtension.class)
class FavouritesForecastLoggingIT extends FavouritesIntegrationTestBase {

    private static final String FORECAST = BASE + "/forecast";
    private static final Pattern INFO_OR_ABOVE = Pattern.compile("\\b(INFO|WARN|ERROR)\\b");
    private static final Pattern WARN_OR_ERROR = Pattern.compile("\\b(WARN|ERROR)\\b");

    private static Pattern accessLine(String requestId) {
        return Pattern.compile("\\bINFO\\b.* GET /api/v1/favourites/forecast -> \\d{3} in \\d+ ms requestId="
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
    @DisplayName("AC-14: one failed item of 3 logs exactly one endpoint WARN with [requestId=it-ffc-14], failed=1 total=3, no city/label/client id/coordinates, and no 'Favourites weather' line")
    void ac14_partialFailureWarn(CapturedOutput output) throws Exception {
        String requestId = "it-ffc-14";
        String client = newClientId();
        send(HttpMethod.POST, BASE, client, "{\"city\": \"London\", \"label\": \"LBL-SECRET14\"}", Map.of());
        create(client, "Paris");
        create(client, "Tokyo");
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any(), anyInt());

        int mark = output.getAll().length();
        ResponseEntity<String> res = send(HttpMethod.GET, FORECAST, client, null, Map.of("X-Request-Id", requestId));
        List<String> lines = sliceAfterAccessLine(output, mark, requestId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<String> warns = lines.stream().filter(l -> WARN_OR_ERROR.matcher(l).find()).toList();
        assertThat(warns).singleElement().satisfies(l -> {
            assertThat(l).contains("WARN").contains("[requestId=" + requestId + "]")
                    .contains("Favourites forecast partial failure failed=1 total=3 timedOut=0");
            assertThat(l).doesNotContain("London").doesNotContain("Paris").doesNotContain("Tokyo")
                    .doesNotContain("LBL-SECRET14").doesNotContain(client)
                    .doesNotContain("48.8566").doesNotContain("2.3522").doesNotContain("51.5074")
                    .doesNotContain("-0.1278").doesNotContain("35.6762").doesNotContain("139.6503");
        });
        assertThat(lines).noneMatch(l -> l.contains("Favourites weather partial failure"));
    }

    @Test
    @DisplayName("AC-14 (edge): the forecast WARN text differs from the weather endpoint's, so each endpoint's line can be told apart")
    void ac14_edge_distinctFromWeatherWarn(CapturedOutput output) throws Exception {
        String client = newClientId();
        create(client, "Paris");
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(any(), any(), anyInt());
        doThrow(new WeatherProviderUnavailableException("x", null)).when(provider).currentAtLocation(any(), any());

        int mark = output.getAll().length();
        send(HttpMethod.GET, BASE + "/weather", client, null, Map.of("X-Request-Id", "it-ffc-14w"));
        send(HttpMethod.GET, FORECAST, client, null, Map.of("X-Request-Id", "it-ffc-14f"));
        List<String> lines = sliceAfterAccessLine(output, mark, "it-ffc-14f");

        assertThat(lines.stream().filter(l -> l.contains("Favourites forecast partial failure")).toList())
                .singleElement().satisfies(l -> assertThat(l).contains("[requestId=it-ffc-14f]")
                        .contains("failed=1 total=1 timedOut=0"));
        assertThat(lines.stream().filter(l -> l.contains("Favourites weather partial failure")).toList())
                .singleElement().satisfies(l -> assertThat(l).contains("[requestId=it-ffc-14w]"));
    }

    @Test
    @DisplayName("AC-24: a fully successful request echoes X-Request-Id it-ffc-24, writes exactly one INFO access line (path without query) and no WARN; cid-SECRET24 and LBL-SECRET24 never appear at INFO+")
    void ac24_successLogging(CapturedOutput output) throws Exception {
        String requestId = "it-ffc-24";
        String client = track("cid-SECRET24");
        String label = "LBL-SECRET24";
        jdbc.update("DELETE FROM favourite WHERE client_id = ?", client);
        ResponseEntity<String> created = send(HttpMethod.POST, BASE, client,
                "{\"city\": \"London\", \"label\": \"" + label + "\"}", Map.of());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        create(client, "Paris");

        int mark = output.getAll().length();
        ResponseEntity<String> res = send(HttpMethod.GET, FORECAST + "?units=imperial&days=3&page=2", client, null,
                Map.of("X-Request-Id", requestId));
        List<String> lines = sliceAfterAccessLine(output, mark, requestId);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains(label); // the label is in the body, so its absence from logs is meaningful
        assertThat(res.getBody()).doesNotContain("\"error\"");
        assertThat(res.getHeaders().get("X-Request-Id")).containsExactly(requestId);
        Pattern access = accessLine(requestId);
        List<String> accessLines = lines.stream().filter(l -> access.matcher(l.stripTrailing()).find()).toList();
        assertThat(accessLines).hasSize(1);
        assertThat(accessLines.get(0)).doesNotContain("units").doesNotContain("days=").doesNotContain("page=")
                .doesNotContain("?");
        assertThat(lines.stream().filter(l -> l.contains(requestId) && INFO_OR_ABOVE.matcher(l).find()).toList())
                .as("only the access line at INFO+ for the request").hasSize(1);
        assertThat(lines).as("no WARN/ERROR").noneMatch(l -> WARN_OR_ERROR.matcher(l).find());
        assertThat(output.getAll().lines().filter(l -> INFO_OR_ABOVE.matcher(l).find()))
                .noneMatch(l -> l.contains(client)).noneMatch(l -> l.contains(label));
    }
}
