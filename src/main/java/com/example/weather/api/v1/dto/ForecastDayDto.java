package com.example.weather.api.v1.dto;

import com.example.weather.weather.DailyForecast;
import java.time.LocalDate;

public record ForecastDayDto(
        LocalDate date,
        double minTemperature,
        double maxTemperature,
        int precipitationProbability,
        ConditionDto condition) {

    public static ForecastDayDto from(DailyForecast d) {
        return new ForecastDayDto(
                d.date(), d.minTemperature(), d.maxTemperature(), d.precipitationProbability(),
                ConditionDto.from(d.condition()));
    }
}
