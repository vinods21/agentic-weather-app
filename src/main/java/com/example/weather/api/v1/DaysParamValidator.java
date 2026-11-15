package com.example.weather.api.v1;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Validator for {@link DaysParam}. */
public class DaysParamValidator implements ConstraintValidator<DaysParam, String> {

    /** ASCII decimal digits with an optional sign; {@code Integer.parseInt} alone would accept any Unicode digit. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        OptionalInt parsed = parse(value);
        if (parsed.isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(DaysParam.NOT_AN_INTEGER).addConstraintViolation();
            return false;
        }
        return parsed.getAsInt() >= DaysParam.MIN && parsed.getAsInt() <= DaysParam.MAX;
    }

    /**
     * Parses {@code [+-]?[0-9]+} (ASCII digits only) after stripping surrounding whitespace; empty if
     * the value does not match or does not fit in an {@code int}.
     */
    static OptionalInt parse(String value) {
        String stripped = value.strip();
        if (!DECIMAL.matcher(stripped).matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(stripped));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
