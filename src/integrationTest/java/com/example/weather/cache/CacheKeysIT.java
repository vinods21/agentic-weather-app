package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Key structure and TTLs as stored in the compose Redis (AC-10, AC-12). */
class CacheKeysIT extends CachingIntegrationTestBase {

    private static final List<String> SPECIAL_CITIES = List.of("a:b", "a_b", "a*", "a?", "a[1]", "a b");

    /** One distinct location per geocoding {@code name}. */
    private static Response distinctLocation(RecordedRequest r) {
        String name = r.param("name");
        int i = SPECIAL_CITIES.indexOf(name);
        double lat = 10 + (i < 0 ? 50 : i);
        return Response.ok("{\"results\":[{\"name\":\"City " + (i < 0 ? "x" : i) + "\",\"latitude\":" + lat
                + ",\"longitude\":" + (20 + i) + ".5,\"country_code\":\"GB\"}]}");
    }

    @Test
    @DisplayName("AC-10: cities with key/glob characters never share an entry, and every key matches ^<prefix>:[A-Za-z0-9:._-]+$")
    void ac10_rawInputNeverFormsKeyStructure() {
        fake.stub(Route.GEOCODING, CacheKeysIT::distinctLocation);

        for (String city : SPECIAL_CITIES) {
            String q = URLEncoder.encode(city, StandardCharsets.UTF_8).replace("+", "%20");
            assertThat(get("/api/v1/weather/current?city=" + q).getStatusCode()).as(city).isEqualTo(HttpStatus.OK);
        }

        assertThat(received(Route.GEOCODING)).hasSize(6);
        assertThat(received(Route.GEOCODING)).extracting(r -> r.param("name"))
                .containsExactlyElementsOf(SPECIAL_CITIES);
        Set<String> keys = runKeys();
        Pattern allowed = Pattern.compile("^" + Pattern.quote(cacheKeys.keyPrefix()) + ":[A-Za-z0-9:._-]+$");
        // 6 geocoding + 6 current entries (one per distinct location).
        assertThat(keys).hasSize(12).allSatisfy(k -> assertThat(k).matches(allowed));
        for (String city : SPECIAL_CITIES) {
            assertThat(keys).contains(cacheKeys.geocoding(city));
        }
        // Repeating them all hits the cache.
        for (String city : SPECIAL_CITIES) {
            get("/api/v1/weather/current?city=" + URLEncoder.encode(city, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        assertThat(received(Route.GEOCODING)).hasSize(6);
    }

    @Test
    @DisplayName("AC-12: every key has a TTL: geocoding (6d23h, 7d], not-found (59m, 1h], current (9m, 10m], forecast (59m, 1h]")
    void ac12_everyKeyHasItsTtl() {
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/forecast?city=London&days=3").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/current?city=Atlantis").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String geocoding = cacheKeys.geocoding("London");
        String notFound = cacheKeys.geocoding("Atlantis");
        String current = cacheKeys.current(LONDON);
        String forecast = cacheKeys.forecast(LONDON, LocalDate.of(2026, 11, 15), 3);
        assertThat(runKeys()).containsExactlyInAnyOrder(geocoding, notFound, current, forecast);
        for (String key : runKeys()) {
            assertThat(pttl(key)).as(key).isPositive();
        }
        assertWithin(geocoding, Duration.ofDays(7).minusHours(1), Duration.ofDays(7));
        assertWithin(notFound, Duration.ofMinutes(59), Duration.ofHours(1));
        assertWithin(current, Duration.ofMinutes(9), Duration.ofMinutes(10));
        assertWithin(forecast, Duration.ofMinutes(59), Duration.ofHours(1));
    }

    private void assertWithin(String key, Duration exclusiveLow, Duration inclusiveHigh) {
        long ms = pttl(key);
        assertThat(ms).as(key).isGreaterThan(exclusiveLow.toMillis()).isLessThanOrEqualTo(inclusiveHigh.toMillis());
    }
}
