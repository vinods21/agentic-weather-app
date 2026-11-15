package com.example.weather.favourites;

import com.example.weather.concurrent.BoundedFanOut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fan-out of {@code GET /api/v1/favourites/weather} (ADR 0013 §3), also shared by
 * {@code GET /api/v1/favourites/forecast} (ADR 0016 §1; one bean only). Its executor is owned by the
 * {@link BoundedFanOut} bean and is not itself a bean, so Boot's {@code applicationTaskExecutor}
 * auto-configuration does not back off; it is shut down when the context closes.
 */
@Configuration(proxyBeanMethods = false)
public class FavouriteWeatherConfiguration {

    @Bean(destroyMethod = "close")
    public BoundedFanOut favouriteWeatherFanOut() {
        return BoundedFanOut.virtualThreads("fav-weather-");
    }
}
