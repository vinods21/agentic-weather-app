package com.example.weather.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the weather cache (ADR 0007). Nothing here touches Redis at construction, so startup and
 * readiness never depend on it. {@code weather.cache.enabled=false} gives the no-op cache.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeatherCacheProperties.class)
public class WeatherCacheConfiguration {

    @Bean
    CacheStore cacheStore(StringRedisTemplate redis) {
        return new RedisCacheStore(redis);
    }

    @Bean
    WeatherCache weatherCache(WeatherCacheProperties properties, CacheKeys keys, ObjectProvider<CacheStore> store) {
        // getObject() honours a @Primary CacheStore (integration tests break only the cache's connection).
        return properties.isEnabled()
                ? new RedisWeatherCache(store.getObject(), keys, properties, new CacheCodec())
                : WeatherCache.disabled();
    }
}
