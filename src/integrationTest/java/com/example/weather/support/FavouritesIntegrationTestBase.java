package com.example.weather.support;

import com.example.weather.provider.WeatherProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Shared support for the favourites ITs (design steps 8-9): stub provider wrapped in a Mockito spy
 * (reset after every test by Spring), a service clock that tests can move (reset to {@link #T0}
 * before every test), per-test client ids, and cleanup of every favourite row those clients own.
 *
 * <p>The limit is pinned to 20 so an exported {@code WEATHER_FAVOURITES_MAX_PER_CLIENT} can't change
 * outcomes; the pool has 4 connections so AC-12's two concurrent requests plus the test's own
 * {@code jdbc} never starve. All subclasses without further properties share one context.
 */
@TestPropertySource(properties = {
    "weather.favourites.max-per-client=20",
    "spring.datasource.hikari.maximum-pool-size=4",
})
public abstract class FavouritesIntegrationTestBase extends ApiIntegrationTestBase {

    public static final String BASE = "/api/v1/favourites";
    public static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    protected static final MutableClock CLOCK = new MutableClock(T0);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @MockitoSpyBean
    protected WeatherProvider provider;

    @LocalServerPort
    protected int port;

    private final Set<String> clientIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void resetClock() {
        CLOCK.set(T0);
    }

    @AfterEach
    void deleteFavourites() {
        for (String id : clientIds) {
            jdbc.update("DELETE FROM favourite WHERE client_id = ?", id);
        }
        clientIds.clear();
    }

    /** A fresh, valid client id ({@code it-fav-<uuid>}) whose rows are deleted after the test. */
    protected String newClientId() {
        return track("it-fav-" + UUID.randomUUID());
    }

    /** Registers a fixed client id for cleanup. */
    protected String track(String clientId) {
        clientIds.add(clientId);
        return clientId;
    }

    // ---------------------------------------------------------------- HTTP

    /** Sends {@code body} verbatim as {@code application/json}; a null client id omits the header. */
    protected ResponseEntity<String> send(HttpMethod method, String path, String clientId, String body,
                                          Map<String, String> extraHeaders) {
        HttpHeaders h = new HttpHeaders();
        if (clientId != null) {
            h.set("X-Client-Id", clientId);
        }
        if (body != null) {
            h.set(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        extraHeaders.forEach(h::set);
        return http.exchange(URI.create(path), method, new HttpEntity<>(body, h), String.class);
    }

    protected ResponseEntity<String> post(String clientId, String body) {
        return send(HttpMethod.POST, BASE, clientId, body, Map.of());
    }

    protected ResponseEntity<String> post(String clientId, String body, Map<String, String> extraHeaders) {
        return send(HttpMethod.POST, BASE, clientId, body, extraHeaders);
    }

    protected ResponseEntity<String> list(String clientId) {
        return send(HttpMethod.GET, BASE, clientId, null, Map.of());
    }

    protected ResponseEntity<String> getOne(String clientId, Object id) {
        return send(HttpMethod.GET, BASE + "/" + id, clientId, null, Map.of());
    }

    protected ResponseEntity<String> patch(String clientId, Object id, String body) {
        return send(HttpMethod.PATCH, BASE + "/" + id, clientId, body, Map.of());
    }

    protected ResponseEntity<String> reorder(String clientId, String body) {
        return send(HttpMethod.PUT, BASE + "/order", clientId, body, Map.of());
    }

    protected ResponseEntity<String> delete(String clientId, Object id) {
        return send(HttpMethod.DELETE, BASE + "/" + id, clientId, null, Map.of());
    }

    /** Creates {@code city} (no label) and returns the 201 body; fails on any other status. */
    protected JsonNode create(String clientId, String city) {
        ResponseEntity<String> res = post(clientId, "{\"city\": \"" + city + "\"}");
        if (res.getStatusCode().value() != 201) {
            throw new AssertionError("create " + city + " gave " + res.getStatusCode() + ": " + res.getBody());
        }
        return json(res);
    }

    protected static String reorderBody(String... ids) {
        StringBuilder b = new StringBuilder("{\"ids\": [");
        for (int i = 0; i < ids.length; i++) {
            b.append(i == 0 ? "" : ", ").append('"').append(ids[i]).append('"');
        }
        return b.append("]}").toString();
    }

    /** Ids of the client's list, in list order, read through the API. */
    protected List<String> listedIds(String clientId) {
        List<String> ids = new ArrayList<>();
        json(list(clientId)).get("items").forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    // ---------------------------------------------------------------- assertions

    /** Asserts a Problem Details response with {@code status} and {@code code}; returns the body. */
    protected static JsonNode assertProblem(ResponseEntity<String> res, int status, String code) {
        if (res.getStatusCode().value() != status) {
            throw new AssertionError("expected " + status + " " + code + " but got " + res.getStatusCode()
                    + ": " + res.getBody());
        }
        String contentType = String.valueOf(res.getHeaders().getContentType());
        if (!contentType.startsWith("application/problem+json")) {
            throw new AssertionError("expected application/problem+json but got " + contentType);
        }
        JsonNode body = json(res);
        if (!code.equals(body.path("code").asText()) || body.path("status").asInt() != status) {
            throw new AssertionError("expected code " + code + " / status " + status + " in " + body);
        }
        return body;
    }

    /** The {@code errors} of a Problem body as {@code "field: message"} strings, in body order. */
    protected static List<String> errors(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.path("errors").forEach(e -> out.add(e.path("field").asText() + ": " + e.path("message").asText()));
        return out;
    }

    // ---------------------------------------------------------------- DB

    protected List<Map<String, Object>> rows(String clientId) {
        return jdbc.queryForList("SELECT * FROM favourite WHERE client_id = ? ORDER BY position", clientId);
    }

    protected int rowCount(String clientId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM favourite WHERE client_id = ?", Integer.class, clientId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- raw socket

    /** A raw HTTP/1.1 response: status code, header block (CRLF-joined) and body. */
    public record RawResponse(int status, String headers, String body) {}

    /**
     * Sends one request over a plain socket, header values as raw bytes. Used only where the JDK
     * {@code HttpClient} would transcode the value (it sends non-ASCII header characters as {@code ?}).
     */
    protected RawResponse rawRequest(String method, String path, byte[] headerBlock, String body) {
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.writeBytes((method + " " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Connection: close\r\n").getBytes(StandardCharsets.US_ASCII));
            req.writeBytes(headerBlock);
            if (body != null) {
                req.writeBytes(("Content-Type: application/json\r\nContent-Length: " + bodyBytes.length + "\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            req.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            req.writeBytes(bodyBytes);
            out.write(req.toByteArray());
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            String head = split < 0 ? raw : raw.substring(0, split);
            String rest = split < 0 ? "" : raw.substring(split + 4);
            int status = Integer.parseInt(head.substring(9, 12));
            if (head.toLowerCase().contains("transfer-encoding: chunked")) {
                rest = dechunk(rest);
            }
            return new RawResponse(status, head, rest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < chunked.length()) {
            int eol = chunked.indexOf("\r\n", i);
            if (eol < 0) {
                break;
            }
            int size = Integer.parseInt(chunked.substring(i, eol).trim(), 16);
            if (size == 0) {
                break;
            }
            // Sizes are in bytes; the bodies here are ASCII JSON, so chars == bytes.
            out.append(chunked, eol + 2, eol + 2 + size);
            i = eol + 2 + size + 2;
        }
        return out.toString();
    }
}
