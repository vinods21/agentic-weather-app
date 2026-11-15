package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Boot's auto-configured Lettuce factory really uses the bounded timeouts from application.yml
 * (AC-1, ADR 0007 §10). Assumes REDIS_TIMEOUT / REDIS_CONNECT_TIMEOUT are not exported (they are
 * commented out in .env.example).
 */
class RedisTimeoutConfigIT extends ApiIntegrationTestBase {

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Test
    @DisplayName("AC-1: the auto-configured LettuceConnectionFactory has a 500 ms command timeout and 500 ms connect timeout")
    void ac1_redisTimeoutsAreBounded() {
        var client = connectionFactory.getClientConfiguration();

        assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(client.getClientOptions()).isPresent();
        assertThat(client.getClientOptions().get().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(500));
    }
}
