package com.example.weather;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Smoke test of the compose environment: migrated per-run schema, Redis, and app health. */
class InfrastructureIT extends IntegrationTestBase {

    @Value("${it.run-id}")
    String runId;

    @Autowired
    TestRestTemplate http;

    @AfterEach
    void cleanRows() {
        jdbc.update("DELETE FROM app_metadata WHERE key LIKE 'it-%'");
    }

    @Test
    void flywayMigratedThePerRunSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("it_" + runId);
        jdbc.update("INSERT INTO app_metadata(key, value) VALUES (?, ?)", "it-probe", "ok");
        assertThat(jdbc.queryForObject("SELECT value FROM app_metadata WHERE key = ?", String.class, "it-probe"))
                .isEqualTo("ok");
    }

    @Test
    void redisKeysAreNamespacedPerRun() {
        String key = cacheKeys.key("probe");
        assertThat(key).startsWith("it:" + runId + ":");
        redis.opsForValue().set(key, "pong", Duration.ofMinutes(5));
        assertThat(redis.opsForValue().get(key)).isEqualTo("pong");
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> res = http.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }
}
