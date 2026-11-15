package com.example.weather.api.v1;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The PATCH body must contain {@code label}; the violation is reported on property {@code label}. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LabelPresentValidator.class)
public @interface LabelPresent {

    String message() default "must be present (null clears the label)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
