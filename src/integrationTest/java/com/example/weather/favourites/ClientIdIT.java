package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** X-Client-Id validation (AC-15) and per-client isolation (AC-16). */
class ClientIdIT extends FavouritesIntegrationTestBase {

    private static final String CLIENT_ID_ERROR = "X-Client-Id: must match ^[A-Za-z0-9._-]{1,64}$";

    /** The six favourites operations; {@code {id}} is replaced with an existing favourite id. */
    enum Op {
        CREATE(HttpMethod.POST, BASE, "{\"city\": \"Tokyo\"}"),
        LIST(HttpMethod.GET, BASE, null),
        GET(HttpMethod.GET, BASE + "/{id}", null),
        RENAME(HttpMethod.PATCH, BASE + "/{id}", "{\"label\": \"Work\"}"),
        REORDER(HttpMethod.PUT, BASE + "/order", "{\"ids\": [\"{id}\"]}"),
        DELETE(HttpMethod.DELETE, BASE + "/{id}", null);

        final HttpMethod method;
        final String path;
        final String body;

        Op(HttpMethod method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        String path(String id) {
            return path.replace("{id}", id);
        }

        String body(String id) {
            return body == null ? null : body.replace("{id}", id);
        }
    }

    private String owner;
    private String ownerId;
    private List<Map<String, Object>> ownerRows;

    @BeforeEach
    void seedOwner() {
        owner = newClientId();
        ownerId = json(post(owner, "{\"city\": \"London\", \"label\": \"Home\"}")).get("id").asText();
        ownerRows = rows(owner);
        clearInvocations(provider);
    }

    private ResponseEntity<String> call(Op op, String clientId) {
        return send(op.method, op.path(ownerId), clientId, op.body(ownerId), Map.of());
    }

    static Stream<Arguments> invalidHeaders() {
        List<Arguments> args = new ArrayList<>();
        // null = header missing. (The non-ASCII case is sent over a raw socket below.)
        List<String> values = new ArrayList<>(java.util.Arrays.asList(null, "", "a".repeat(65), "a b", "a/b"));
        for (Op op : Op.values()) {
            for (String v : values) {
                args.add(Arguments.of(op, v == null ? "<missing>" : v.length() > 10 ? "65 chars" : "'" + v + "'", v));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("invalidHeaders")
    @DisplayName("AC-15: a missing, empty, 65-char or ill-formed X-Client-Id is 400 with the X-Client-Id error; nothing read or changed; no provider call")
    void ac15_invalidClientId(Op op, String label, String header) {
        JsonNode problem = assertProblem(call(op, header), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly(CLIENT_ID_ERROR);
        assertNothingChanged(header);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("nonAsciiCases")
    @DisplayName("AC-15: a non-ASCII X-Client-Id ('ü', sent as raw bytes) is 400 with the X-Client-Id error; nothing changed")
    void ac15_nonAsciiClientId(Op op, String charsetName) {
        // The JDK HttpClient (TestRestTemplate) would send 'ü' as '?', so the bytes go over a raw socket.
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.writeBytes("X-Client-Id: ".getBytes(StandardCharsets.US_ASCII));
        header.writeBytes("ü".getBytes(Charset.forName(charsetName)));
        header.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));

        RawResponse res = rawRequest(op.method.name(), op.path(ownerId), header.toByteArray(), op.body(ownerId));

        assertThat(res.status()).as(res.headers() + "\n" + res.body()).isEqualTo(400);
        assertThat(res.headers().toLowerCase()).contains("content-type: application/problem+json");
        JsonNode problem = parse(res.body());
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(errors(problem)).containsExactly(CLIENT_ID_ERROR);
        assertNothingChanged(null);
    }

    static Stream<Arguments> nonAsciiCases() {
        List<Arguments> args = new ArrayList<>();
        for (Op op : Op.values()) {
            args.add(Arguments.of(op, "UTF-8"));
            args.add(Arguments.of(op, "ISO-8859-1"));
        }
        return args.stream();
    }

    @Test
    @DisplayName("AC-15: a 64-character valid id is accepted by every operation")
    void ac15_sixtyFourCharsAccepted() {
        String id64 = track("it-fav-64-" + "x".repeat(54));
        assertThat(id64).hasSize(64);

        ResponseEntity<String> created = post(id64, "{\"city\": \"Paris\"}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String id = json(created).get("id").asText();
        assertThat(list(id64).getStatusCode().value()).isEqualTo(200);
        assertThat(getOne(id64, id).getStatusCode().value()).isEqualTo(200);
        assertThat(patch(id64, id, "{\"label\": \"Work\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(reorder(id64, reorderBody(id)).getStatusCode().value()).isEqualTo(200);
        assertThat(delete(id64, id).getStatusCode().value()).isEqualTo(204);
        assertThat(rowCount(id64)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Op.class, names = {"CREATE", "RENAME", "REORDER"})
    @DisplayName("AC-15: an invalid X-Client-Id with a malformed body still reports the X-Client-Id error")
    void ac15_invalidHeaderAndMalformedBody(Op op) {
        ResponseEntity<String> res = send(op.method, op.path(ownerId), "a b", "{\"broken\":", Map.of());

        JsonNode problem = assertProblem(res, 400, "VALIDATION_FAILED");
        assertThat(errors(problem)).contains(CLIENT_ID_ERROR);
        assertNothingChanged("a b");
    }

    @Test
    @DisplayName("AC-15 (edge): an invalid X-Client-Id with an invalid (but readable) create body reports the X-Client-Id error")
    void ac15_edge_invalidHeaderAndInvalidBody() {
        JsonNode problem = assertProblem(post("a/b", "{\"city\": \"   \"}"), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).contains(CLIENT_ID_ERROR);
        assertNothingChanged("a/b");
    }

    @Test
    @DisplayName("AC-15 (edge): client ids are case-sensitive partitions")
    void ac15_edge_caseSensitive() {
        String upper = track(owner.toUpperCase());

        assertThat(json(list(upper)).get("items")).isEmpty();
        assertProblem(getOne(upper, ownerId), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("AC-16: another client sees an empty list and 404 NOT_FOUND on get/rename/delete of A's id; reorder with it is 400; A's row unchanged")
    void ac16_isolation() {
        String b = newClientId();

        assertThat(json(list(b))).isEqualTo(JSON.createObjectNode().set("items", JSON.createArrayNode()));

        JsonNode missing = assertProblem(getOne(owner, java.util.UUID.randomUUID()), 404, "NOT_FOUND");
        for (ResponseEntity<String> res : List.of(
                getOne(b, ownerId),
                patch(b, ownerId, "{\"label\": \"Stolen\"}"),
                delete(b, ownerId))) {
            JsonNode problem = assertProblem(res, 404, "NOT_FOUND");
            assertThat(problem.get("detail")).isEqualTo(missing.get("detail"));
        }

        JsonNode reorder = assertProblem(reorder(b, reorderBody(ownerId)), 400, "VALIDATION_FAILED");
        assertThat(errors(reorder)).singleElement().asString().startsWith("ids: ");

        assertThat(rows(owner)).isEqualTo(ownerRows);
        assertThat(rowCount(b)).isZero();
    }

    @Test
    @DisplayName("AC-16 (edge): B's reorder that mixes its own id with A's id is 400 and changes neither list")
    void ac16_edge_mixedReorder() {
        String b = newClientId();
        String bId = create(b, "Paris").get("id").asText();
        List<Map<String, Object>> bRows = rows(b);

        assertProblem(reorder(b, reorderBody(ownerId, bId)), 400, "VALIDATION_FAILED");

        assertThat(rows(owner)).isEqualTo(ownerRows);
        assertThat(rows(b)).isEqualTo(bRows);
    }

    private void assertNothingChanged(String attemptedClientId) {
        assertThat(rows(owner)).isEqualTo(ownerRows);
        if (attemptedClientId != null) {
            assertThat(rowCount(attemptedClientId)).isZero();
        }
        verifyNoInteractions(provider);
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + body, e);
        }
    }
}
