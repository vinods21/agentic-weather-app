package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FavouritesIntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** AC-30: one access-log line per request, request id echoed, client id and label never logged at INFO+. */
@ExtendWith(OutputCaptureExtension.class)
class FavouritesLoggingIT extends FavouritesIntegrationTestBase {

    private static final String REQUEST_ID = "it-fav-30";
    private static final String CLIENT_ID = "cid-SECRET30";
    private static final String LABEL = "LBL-SECRET30";
    private static final Map<String, String> HEADERS = Map.of("X-Request-Id", REQUEST_ID);
    /** The RequestIdFilter access line: "<METHOD> <path> -> <status> in <n> ms requestId=<id>". */
    private static final Pattern ACCESS_LINE =
            Pattern.compile("\\bINFO\\b.* (GET|POST|PATCH|PUT|DELETE) \\S+ -> \\d{3} in \\d+ ms requestId=" + REQUEST_ID + "$");
    private static final Pattern INFO_OR_ABOVE = Pattern.compile("\\b(INFO|WARN|ERROR)\\b");

    @Test
    @DisplayName("AC-30: create, list, rename and delete each echo X-Request-Id and log exactly one INFO access line; client id and label never appear at INFO+")
    void ac30_logging(CapturedOutput output) throws Exception {
        track(CLIENT_ID);
        jdbc.update("DELETE FROM favourite WHERE client_id = ?", CLIENT_ID);

        ResponseEntity<String> created = logged(output, () -> send(HttpMethod.POST, BASE, CLIENT_ID,
                "{\"city\": \"London\", \"label\": \"" + LABEL + "\"}", HEADERS));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String id = json(created).get("id").asText();

        assertThat(logged(output, () -> send(HttpMethod.GET, BASE, CLIENT_ID, null, HEADERS))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(logged(output, () -> send(HttpMethod.PATCH, BASE + "/" + id, CLIENT_ID,
                "{\"label\": \"" + LABEL + "-2\"}", HEADERS)).getStatusCode().value()).isEqualTo(200);
        assertThat(logged(output, () -> send(HttpMethod.DELETE, BASE + "/" + id, CLIENT_ID, null, HEADERS))
                .getStatusCode().value()).isEqualTo(204);

        List<String> infoPlus = output.getAll().lines().filter(l -> INFO_OR_ABOVE.matcher(l).find()).toList();
        assertThat(infoPlus).noneMatch(l -> l.contains(CLIENT_ID)).noneMatch(l -> l.contains(LABEL));
    }

    @Test
    @DisplayName("AC-30 (edge): error responses (409, 400 with a bad client id) don't log the client id or label either")
    void ac30_edge_errorsDoNotLogSecrets(CapturedOutput output) throws Exception {
        track(CLIENT_ID);
        jdbc.update("DELETE FROM favourite WHERE client_id = ?", CLIENT_ID);
        String body = "{\"city\": \"Paris\", \"label\": \"" + LABEL + "\"}";
        assertThat(logged(output, () -> send(HttpMethod.POST, BASE, CLIENT_ID, body, HEADERS))
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> dup = logged(output, () -> send(HttpMethod.POST, BASE, CLIENT_ID, body, HEADERS));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).doesNotContain(CLIENT_ID).doesNotContain(LABEL);
        ResponseEntity<String> bad = logged(output, () -> send(HttpMethod.POST, BASE, CLIENT_ID + "/x", body, HEADERS));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody()).doesNotContain(CLIENT_ID).doesNotContain(LABEL);

        assertThat(output.getAll().lines().filter(l -> INFO_OR_ABOVE.matcher(l).find()))
                .noneMatch(l -> l.contains(CLIENT_ID)).noneMatch(l -> l.contains(LABEL));
    }

    /**
     * Sends one request and asserts it echoed the request id and produced exactly one access line.
     * The filter logs after the body is flushed, so the line may arrive just after the response.
     */
    private ResponseEntity<String> logged(CapturedOutput output, Supplier<ResponseEntity<String>> request)
            throws InterruptedException {
        long before = accessLines(output);
        ResponseEntity<String> res = request.get();
        assertThat(res.getHeaders().get("X-Request-Id")).containsExactly(REQUEST_ID);
        long deadline = System.currentTimeMillis() + 3000;
        while (accessLines(output) < before + 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100); // a second line, if any, would show up now
        assertThat(accessLines(output) - before).as("access lines for this request").isEqualTo(1);
        return res;
    }

    private static long accessLines(CapturedOutput output) {
        return output.getAll().lines().filter(l -> ACCESS_LINE.matcher(l.stripTrailing()).find()).count();
    }
}
