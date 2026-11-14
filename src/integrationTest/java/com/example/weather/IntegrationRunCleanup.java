package com.example.weather;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Runs once after the whole integration test plan: drops the per-run Postgres schema and
 * deletes any leftover Redis keys under the per-run prefix. Registered through
 * META-INF/services/org.junit.platform.launcher.TestExecutionListener.
 */
public class IntegrationRunCleanup implements TestExecutionListener {

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        String runId = System.getProperty("it.run-id");
        if (runId == null || !runId.matches("[a-z0-9]{1,32}")) {
            return; // IDE run without Gradle: keep the shared "local" schema for debugging.
        }
        dropSchema("it_" + runId);
        deleteRedisKeys("it:" + runId + ":*");
    }

    private static void dropSchema(String schema) {
        String url = "jdbc:postgresql://%s:%s/%s".formatted(
                env("POSTGRES_HOST", "localhost"), env("POSTGRES_PORT", "5432"), env("POSTGRES_DB", "weather"));
        try (Connection c = DriverManager.getConnection(url, env("POSTGRES_USER", "weather"), env("POSTGRES_PASSWORD", ""));
                Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (Exception e) {
            System.err.println("[it-cleanup] could not drop schema " + schema + ": " + e.getMessage());
        }
    }

    private static void deleteRedisKeys(String pattern) {
        RedisURI uri = RedisURI.create(env("REDIS_HOST", "localhost"), Integer.parseInt(env("REDIS_PORT", "6379")));
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> cmd = conn.sync();
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> page = cmd.scan(cursor, ScanArgs.Builder.matches(pattern).limit(500));
                if (!page.getKeys().isEmpty()) {
                    cmd.del(page.getKeys().toArray(String[]::new));
                }
                cursor = page;
            } while (!cursor.isFinished());
        } catch (Exception e) {
            System.err.println("[it-cleanup] could not delete redis keys " + pattern + ": " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
