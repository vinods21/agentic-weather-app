package com.example.weather.api;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for {@code /api/**}: the {@link ClientId} argument resolver (ADR 0009) and CORS
 * (ADR 0003 as amended by ADR 0010): origin allow-list only (empty = deny all), GET, POST, PUT,
 * PATCH, DELETE and OPTIONS, any request header, X-Request-Id and Location exposed, no credentials.
 */
@Configuration(proxyBeanMethods = false)
public class ApiWebConfig implements WebMvcConfigurer {

    private final ApiCorsProperties cors;

    public ApiWebConfig(ApiCorsProperties cors) {
        this.cors = cors;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClientIdArgumentResolver());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // allowedOrigins is always set explicitly: an empty list denies every origin, whereas
        // leaving it unset would fall back to Spring's permissive defaults.
        registry.addMapping(ApiPaths.API_PREFIX + "**")
                .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(RequestIds.HEADER, HttpHeaders.LOCATION)
                .allowCredentials(false)
                .maxAge(3600);
    }
}
