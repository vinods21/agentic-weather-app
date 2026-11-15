package com.example.weather.api.v1;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.OptionalInt;

/** Validator for {@link DaysParam}. */
public class DaysParamValidator implements ConstraintValidator<DaysParam, String> {

    private int min;
    private int max;

    @Override
    public void initialize(DaysParam annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

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
        return parsed.getAsInt() >= min && parsed.getAsInt() <= max;
    }

    /** Parses a decimal integer, ignoring surrounding whitespace; empty if it is not one. */
    static OptionalInt parse(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
