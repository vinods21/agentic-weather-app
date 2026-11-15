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
 * integer between {@link #min()} and {@link #max()}. Binding {@code days} as a {@code String} and
 * validating it here (rather than letting Spring convert it to an {@code Integer}) means a
 * non-integer value is reported together with every other violation (AC-8, defect D-1).
 */
@Documented
@Constraint(validatedBy = DaysParamValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DaysParam {

    /** Message for a value that is not an integer (fixed text, no class names, no echoed input). */
    String NOT_AN_INTEGER = "must be an integer between 1 and 7";

    /** Message for an integer outside the allowed range. */
    String OUT_OF_RANGE = "must be between 1 and 7";

    String message() default OUT_OF_RANGE;

    int min() default 1;

    int max() default 7;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
