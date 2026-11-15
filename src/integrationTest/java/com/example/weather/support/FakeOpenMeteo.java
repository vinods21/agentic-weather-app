package com.example.weather.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * In-process fake of the Open-Meteo geocoding and forecast APIs (design A-9 option (a)): a JDK
 * {@link HttpServer} on {@code 127.0.0.1} with a random port, one per JVM, stopped by a shutdown
 * hook. No container, no internet.
 *
 * <p>Defaults ({@link #reset()}): geocoding returns London for {@code name} equal to
 * {@code london} (ignoring case) and {@code {}} otherwise; {@code current} returns the AC-6
 * fixture; {@code daily} returns a fixture generated from the received {@code start_date} /
 * {@code end_date} whose first three days are the AC-8 values. Tests override a {@link Route}
 * with {@link #stub(Route, Response)} and inspect what was received with {@link #requests(Route)}.
 */
public final class FakeOpenMeteo {

    /** The three kinds of upstream request. */
    public enum Route { GEOCODING, CURRENT, DAILY }

    /** A canned response. {@code body == null} sends no body. */
    public record Response(int status, String body, Duration delay) {
        public static Response ok(String body) {
            return new Response(200, body, Duration.ZERO);
        }

        public static Response status(int status, String body) {
            return new Response(status, body, Duration.ZERO);
        }

        public Response delayed(Duration d) {
            return new Response(status, body, d);
        }
    }

    /** A request as received; {@code params} are decoded with {@link URLDecoder} (UTF-8). */
    public record RecordedRequest(Route route, String path, String rawQuery, Map<String, List<String>> params) {
        /** The single value of {@code name}; fails if the parameter is missing or repeated. */
        public String param(String name) {
            List<String> values = params.getOrDefault(name, List.of());
            if (values.size() != 1) {
                throw new AssertionError("expected exactly one '" + name + "' parameter but got " + values
                        + " in " + rawQuery);
            }
            return values.get(0);
        }
    }

    public static final String LONDON_GEOCODING = """
            {"results":[{"id":2643743,"name":"London","latitude":51.50853,"longitude":-0.12574,\
            "country_code":"GB","country":"United Kingdom","timezone":"Europe/London"}],"generationtime_ms":0.4}""";

    public static final String CURRENT_AC6 = """
            {"latitude":51.5,"longitude":-0.120000124,"timezone":"GMT","current":{"time":"2026-11-15T10:00",\
            "interval":900,"temperature_2m":11.2,"apparent_temperature":9.8,"relative_humidity_2m":81,\
            "wind_speed_10m":4.1,"wind_direction_10m":230,"weather_code":3}}""";

    // First three entries are the AC-8 fixture; the rest fill a 7-day window.
    static final double[] DAILY_MIN = {6.0, 5.5, 4.0, 3.0, 2.5, 2.0, 1.5};
    static final double[] DAILY_MAX = {12.5, 11.0, 9.5, 9.0, 8.5, 8.0, 7.5};
    static final Integer[] DAILY_PRECIPITATION = {40, 20, 10, 0, 5, 15, 30};
    static final int[] DAILY_CODES = {61, 2, 45, 0, 3, 71, 95};

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FakeOpenMeteo INSTANCE = start();

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<Route, Function<RecordedRequest, Response>> overrides = new ConcurrentHashMap<>();

    private FakeOpenMeteo(HttpServer server) {
        this.server = server;
    }

    public static FakeOpenMeteo instance() {
        return INSTANCE;
    }

    private static FakeOpenMeteo start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            // Cached pool: a delayed handler (AC-15) never blocks other requests.
            ExecutorService executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fake-open-meteo");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            FakeOpenMeteo fake = new FakeOpenMeteo(server);
            server.createContext("/", fake::handle);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                executor.shutdownNow();
            }, "fake-open-meteo-stop"));
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start FakeOpenMeteo", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Restores the default responses and clears the recorded requests. */
    public void reset() {
        overrides.clear();
        requests.clear();
    }

    public void stub(Route route, Response response) {
        overrides.put(route, r -> response);
    }

    public void stub(Route route, Function<RecordedRequest, Response> responder) {
        overrides.put(route, responder);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RecordedRequest> requests(Route route) {
        return requests.stream().filter(r -> r.route() == route).toList();
    }

    // ---------------------------------------------------------------- Spring wiring

    /**
     * Selects the real adapter and points both base URLs at the fake. Used as a context
     * initializer (not {@code @DynamicPropertySource}) so that every test class using it, including
     * subclasses of existing ITs (AC-23), shares one cached Spring context.
     */
    public static final class RealProviderInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = instance().baseUrl();
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "weather.provider.type=open-meteo",
                    "weather.provider.open-meteo.geocoding-base-url=" + url,
                    "weather.provider.open-meteo.forecast-base-url=" + url,
                    "weather.provider.connect-timeout=1s",
                    "weather.provider.read-timeout=1s",
                    // Each cached context keeps a pool open; keep the extra ones small.
                    "spring.datasource.hikari.maximum-pool-size=3");
        }
    }

    /** Points both base URLs at the fake without changing {@code weather.provider.type}. */
    public static void registerUrls(DynamicPropertyRegistry registry) {
        registry.add("weather.provider.open-meteo.geocoding-base-url", () -> instance().baseUrl());
        registry.add("weather.provider.open-meteo.forecast-base-url", () -> instance().baseUrl());
    }

    // ---------------------------------------------------------------- fixtures

    /** A daily body for {@code days} days from {@code start}, values from the default table. */
    public static ObjectNode daily(LocalDate start, int days) {
        ObjectNode body = JSON.createObjectNode();
        body.put("latitude", 51.5).put("longitude", -0.120000124).put("timezone", "GMT");
        ObjectNode daily = body.putObject("daily");
        ArrayNode time = daily.putArray("time");
        ArrayNode min = daily.putArray("temperature_2m_min");
        ArrayNode max = daily.putArray("temperature_2m_max");
        ArrayNode precipitation = daily.putArray("precipitation_probability_max");
        ArrayNode codes = daily.putArray("weather_code");
        for (int i = 0; i < days; i++) {
            int k = i % DAILY_MIN.length;
            time.add(start.plusDays(i).toString());
            min.add(DAILY_MIN[k]);
            max.add(DAILY_MAX[k]);
            precipitation.add(DAILY_PRECIPITATION[k]);
            codes.add(DAILY_CODES[k]);
        }
        return body;
    }

    public static String json(Object node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- server

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            Map<String, List<String>> params = parse(rawQuery);
            Route route = route(path, params);
            if (route == null) {
                send(exchange, Response.status(404, "{\"error\":true,\"reason\":\"unknown path\"}"));
                return;
            }
            RecordedRequest request = new RecordedRequest(route, path, rawQuery, params);
            requests.add(request);
            Function<RecordedRequest, Response> override = overrides.get(route);
            Response response = override != null ? override.apply(request) : defaultResponse(request);
            if (!response.delay().isZero()) {
                try {
                    Thread.sleep(response.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            send(exchange, response);
        } catch (IOException e) {
            // The client gave up (e.g. read timeout): nothing to do.
        }
    }

    private static Route route(String path, Map<String, List<String>> params) {
        if ("/v1/search".equals(path)) {
            return Route.GEOCODING;
        }
        if ("/v1/forecast".equals(path)) {
            if (params.containsKey("current")) {
                return Route.CURRENT;
            }
            if (params.containsKey("daily")) {
                return Route.DAILY;
            }
        }
        return null;
    }

    private static Response defaultResponse(RecordedRequest request) {
        return switch (request.route()) {
            case GEOCODING -> {
                List<String> names = request.params().getOrDefault("name", List.of());
                yield names.size() == 1 && names.get(0).equalsIgnoreCase("london")
                        ? Response.ok(LONDON_GEOCODING)
                        : Response.ok("{\"generationtime_ms\":0.2}");
            }
            case CURRENT -> Response.ok(CURRENT_AC6);
            case DAILY -> {
                try {
                    LocalDate start = LocalDate.parse(request.param("start_date"));
                    LocalDate end = LocalDate.parse(request.param("end_date"));
                    int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
                    yield days < 1 ? Response.status(400, "{\"error\":true,\"reason\":\"range\"}")
                            : Response.ok(json(daily(start, days)));
                } catch (AssertionError | DateTimeParseException e) {
                    yield Response.status(400, "{\"error\":true,\"reason\":\"dates\"}");
                }
            }
        };
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static Map<String, List<String>> parse(String rawQuery) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        params.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(params);
    }
}
