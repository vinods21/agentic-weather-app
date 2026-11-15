package com.example.weather.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for {@code /api/**} (ADR 0003): allow-list only, GET/OPTIONS, no credentials. */
@Configuration(proxyBeanMethods = false)
public class ApiWebConfig implements WebMvcConfigurer {

    private final ApiCorsProperties cors;

    public ApiWebConfig(ApiCorsProperties cors) {
        this.cors = cors;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // allowedOrigins is always set explicitly: an empty list denies every origin, whereas
        // leaving it unset would fall back to Spring's permissive defaults.
        registry.addMapping(ApiPaths.API_PREFIX + "**")
                .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(RequestIds.HEADER)
                .allowCredentials(false)
                .maxAge(3600);
    }
}
