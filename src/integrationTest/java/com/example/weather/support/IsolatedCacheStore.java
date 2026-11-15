package com.example.weather.support;

import com.example.weather.cache.CacheStore;
import com.example.weather.cache.RedisCacheStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A {@link CacheStore} on its own Lettuce connection to {@code 127.0.0.1:<port>} (a closed port or
 * a {@link SilentRedisServer}), with Boot's Redis timeouts from {@link RedisProperties}. It is
 * registered only as a {@code @Primary CacheStore}, never as a {@code RedisConnectionFactory} or
 * {@code StringRedisTemplate} bean, so the context's own template (and {@code IntegrationTestBase}
 * cleanup) stays on the compose Redis (design A-10). Nothing connects until the first command.
 */
public final class IsolatedCacheStore implements CacheStore, DisposableBean {

    private final LettuceConnectionFactory factory;
    private final RedisCacheStore delegate;

    public IsolatedCacheStore(int port, RedisProperties redisProperties) {
        Duration commandTimeout = redisProperties.getTimeout();
        Duration connectTimeout = redisProperties.getConnectTimeout();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                        .build())
                .build();
        this.factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), client);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        this.delegate = new RedisCacheStore(template);
    }

    @Override
    public String get(String key) {
        return delegate.get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        delegate.set(key, value, ttl);
    }

    @Override
    public void destroy() {
        factory.destroy();
    }
}
