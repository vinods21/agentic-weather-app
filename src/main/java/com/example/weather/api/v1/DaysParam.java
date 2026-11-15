package com.example.weather.api.v1;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates the raw {@code days} query parameter: absent is valid, otherwise it must be a decimal
 * integer between {@link #MIN} and {@link #MAX}. The range is fixed by the v1 contract, so it is
 * not configurable per use and the messages are built from the same constants. Binding {@code days} as a {@code String} and
 * validating it here (rather than letting Spring convert it to an {@code Integer}) means a
 * non-integer value is reported together with every other violation (AC-8, defect D-1).
 */
@Documented
@Constraint(validatedBy = DaysParamValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DaysParam {

    /** Smallest allowed number of days (inclusive). */
    int MIN = 1;

    /** Largest allowed number of days (inclusive). */
    int MAX = 7;

    /** Message for a value that is not an integer (fixed text, no class names, no echoed input). */
    String NOT_AN_INTEGER = "must be an integer between " + MIN + " and " + MAX;

    /** Message for an integer outside the allowed range. */
    String OUT_OF_RANGE = "must be between " + MIN + " and " + MAX;

    String message() default OUT_OF_RANGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
