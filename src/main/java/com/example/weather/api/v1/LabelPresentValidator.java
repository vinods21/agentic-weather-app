package com.example.weather.api.v1;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link LabelPresent} and attaches the violation to property {@code label}. */
public class LabelPresentValidator implements ConstraintValidator<LabelPresent, UpdateFavouriteRequest> {

    private String message;

    @Override
    public void initialize(LabelPresent annotation) {
        this.message = annotation.message();
    }

    @Override
    public boolean isValid(UpdateFavouriteRequest value, ConstraintValidatorContext context) {
        if (value == null || value.labelPresent()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addPropertyNode("label").addConstraintViolation();
        return false;
    }
}
