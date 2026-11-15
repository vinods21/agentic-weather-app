package com.example.weather.api.v1;

import com.example.weather.api.v1.dto.CurrentWeatherResponse;
import com.example.weather.api.v1.dto.ForecastResponse;
import com.example.weather.weather.WeatherService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Weather endpoints of the v1 API. Errors are mapped by {@code ApiExceptionHandler}. */
@RestController
@RequestMapping(value = "/api/v1/weather", produces = MediaType.APPLICATION_JSON_VALUE)
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/current")
    public CurrentWeatherResponse current(@Valid @ModelAttribute CurrentWeatherQuery query) {
        return CurrentWeatherResponse.from(weatherService.current(query.city(), query.resolvedUnits()));
    }

    @GetMapping("/forecast")
    public ForecastResponse forecast(@Valid @ModelAttribute ForecastQuery query) {
        return ForecastResponse.from(weatherService.forecast(query.city(), query.resolvedUnits(), query.resolvedDays()));
    }
}
