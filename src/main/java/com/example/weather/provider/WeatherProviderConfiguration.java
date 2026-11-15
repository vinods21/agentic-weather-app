package com.example.weather.provider;

import com.example.weather.provider.openmeteo.OpenMeteoWeatherProvider;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Creates exactly one {@link WeatherProvider} from the validated {@code weather.provider.type}
 * (ADR 0005). An invalid type fails while binding {@link WeatherProviderProperties}, so startup
 * always reports the property and the allowed values.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeatherProviderProperties.class)
public class WeatherProviderConfiguration {

    @Bean
    WeatherProvider weatherProvider(WeatherProviderProperties properties,
                                    ObjectProvider<RestClient.Builder> restClientBuilder,
                                    ObjectMapper objectMapper) {
        return switch (properties.type()) {
            case WeatherProviderProperties.STUB -> new StubWeatherProvider();
            case WeatherProviderProperties.OPEN_METEO ->
                    OpenMeteoWeatherProvider.create(properties, restClientBuilder.getObject(), objectMapper);
            default -> throw new IllegalStateException("Unhandled weather.provider.type");
        };
    }
}
