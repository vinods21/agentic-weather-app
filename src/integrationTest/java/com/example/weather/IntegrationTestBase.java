package com.example.weather;

import com.example.weather.cache.CacheKeys;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests. Connects to the Docker Compose services on localhost
 * through the {@code it} profile; it never starts containers (no Testcontainers).
 *
 * <p>Each Gradle run uses its own Postgres schema and Redis key prefix. Subclasses clean up
 * the rows they create in their own {@code @AfterEach}; Redis keys under the run prefix are
 * removed here after every test. {@link IntegrationRunCleanup} drops the schema at the end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
public abstract class IntegrationTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected CacheKeys cacheKeys;

    @AfterEach
    protected void deleteRunRedisKeys() {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(cacheKeys.pattern()).count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
