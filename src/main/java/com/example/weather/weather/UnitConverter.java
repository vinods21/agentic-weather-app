package com.example.weather.weather;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure unit-conversion helpers. */
public final class UnitConverter {

    /** Metres per second to miles per hour. */
    static final double MS_TO_MPH = 2.2369363;

    private UnitConverter() {}

    /** F = C x 9/5 + 32, rounded to one decimal. */
    public static double celsiusToFahrenheit(double celsius) {
        return round1(celsius * 9.0 / 5.0 + 32.0);
    }

    /** m/s to mph, rounded to one decimal. */
    public static double msToMph(double metresPerSecond) {
        return round1(metresPerSecond * MS_TO_MPH);
    }

    /** Rounds half-up to one decimal place. */
    public static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
