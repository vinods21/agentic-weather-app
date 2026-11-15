package com.example.weather.weather;

import java.time.LocalDate;

/** One forecast day. Provider results are metric; service results are in the requested units. */
public record DailyForecast(
        LocalDate date,
        double minTemperature,
        double maxTemperature,
        int precipitationProbability,
        Condition condition) {}
