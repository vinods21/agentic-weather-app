package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Route;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.DataType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Pre-seeded unreadable entries under their real keys (AC-23): each is a miss with one
 * {@code reason=unreadable} WARN, is rewritten as a readable entry with a TTL, and the next
 * request is a cache hit.
 */
@ExtendWith(OutputCaptureExtension.class)
class CacheUnreadableEntryIT extends CachingIntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final String VALID_GEOCODING =
            "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"GB\",\"latitude\":51.50853,\"longitude\":-0.12574}";
    private static final String HASH = "<hash>";

    enum Kind { GEOCODING, CURRENT, FORECAST }

    static Stream<Arguments> cases() {
        Map<Kind, String> foreignVersion = Map.of(
                Kind.GEOCODING, VALID_GEOCODING.replace("\"v\":1", "\"v\":0"),
                Kind.CURRENT, "{\"v\":0,\"fetchedAt\":\"2026-11-15T10:00:00Z\",\"temperature\":11.2,\"feelsLike\":9.8,"
                        + "\"humidity\":81,\"windSpeed\":4.1,\"windDirection\":230,"
                        + "\"condition\":{\"code\":\"CLOUDY\",\"description\":\"SeededOvercast\"}}",
                Kind.FORECAST, "{\"v\":0,\"days\":["
                        + day("2026-11-15") + "," + day("2026-11-16") + "," + day("2026-11-17") + "]}");
        List<Arguments> args = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            args.add(Arguments.of(kind, "not-json", "not-json"));
            args.add(Arguments.of(kind, "{}", "{}"));
            args.add(Arguments.of(kind, "v:0", foreignVersion.get(kind)));
            args.add(Arguments.of(kind, "hash (WRONGTYPE)", HASH));
        }
        return args.stream();
    }

    private static String day(String date) {
        return "{\"date\":\"" + date + "\",\"minTemperature\":6.0,\"maxTemperature\":12.5,"
                + "\"precipitationProbability\":40,\"condition\":{\"code\":\"RAIN\",\"description\":\"SeededRain\"}}";
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    @DisplayName("AC-23: an unreadable entry is a miss with a reason=unreadable WARN, is rewritten readable with a TTL, then served from cache")
    void ac23_unreadableEntryIsRewritten(Kind kind, String label, String raw, CapturedOutput output) {
        String key = switch (kind) {
            case GEOCODING -> cacheKeys.geocoding("London");
            case CURRENT -> cacheKeys.current(LONDON);
            case FORECAST -> cacheKeys.forecast(LONDON, TODAY, 3);
        };
        if (kind != Kind.GEOCODING) {
            // A readable geocoding entry, so only the entry under test needs the upstream.
            redis.opsForValue().set(cacheKeys.geocoding("London"), VALID_GEOCODING, Duration.ofMinutes(5));
        }
        if (raw.equals(HASH)) {
            redis.opsForHash().put(key, "field", "value");
            redis.expire(key, Duration.ofMinutes(5));
        } else {
            redis.opsForValue().set(key, raw, Duration.ofMinutes(5));
        }
        String path = kind == Kind.FORECAST ? "/api/v1/weather/forecast?city=London&days=3"
                : "/api/v1/weather/current?city=London";
        String id = "it-rwc-23-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> res = get(path, Map.of("X-Request-Id", id));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (kind == Kind.FORECAST) {
            assertThat(json(res).get("days")).hasSize(3);
            assertThat(json(res).at("/days/0/minTemperature").asDouble()).isEqualTo(6.0);
            assertThat(res.getBody()).doesNotContain("SeededRain");
            assertThat(received(Route.DAILY)).hasSize(1);
        } else {
            assertThat(json(res).at("/location/name").asText()).isEqualTo("London");
            assertThat(json(res).get("temperature").asDouble()).isEqualTo(11.2);
            assertThat(res.getBody()).doesNotContain("SeededOvercast");
            assertThat(received(Route.CURRENT)).hasSize(1);
        }
        assertThat(received(Route.GEOCODING)).hasSize(kind == Kind.GEOCODING ? 1 : 0);

        List<String> warns = output.getAll().lines()
                .filter(l -> l.contains("requestId=" + id + "]") && l.contains("Cache operation failed"))
                .toList();
        assertThat(warns).singleElement().satisfies(l -> assertThat(l)
                .contains(" WARN ")
                .contains("op=get")
                .contains("cache=" + kind.name().toLowerCase())
                .contains("reason=unreadable")
                .doesNotContain("not-json").doesNotContain("Seeded").doesNotContain(key).doesNotContain("London"));

        assertThat(redis.type(key)).isEqualTo(DataType.STRING);
        Duration ttl = Duration.ofMillis(pttl(key));
        assertThat(ttl).isPositive();
        // Rewritten with the kind's own TTL, not the 5-minute seed TTL.
        assertThat(ttl).isGreaterThan(Duration.ofMinutes(5));
        assertThat(redis.opsForValue().get(key)).contains("\"v\":1");

        int before = fake.requests().size();
        ResponseEntity<String> again = get(path);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fake.requests()).hasSize(before);
    }
}
