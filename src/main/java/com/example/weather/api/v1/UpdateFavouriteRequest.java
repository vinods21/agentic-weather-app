package com.example.weather.api.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/favourites/{id}} (ADR 0010 §3). A class, not a record, because it
 * must tell an absent {@code label} (400) from an explicit {@code null} (clears the label). Other
 * properties are ignored: the city and location can never change.
 */
@LabelPresent
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpdateFavouriteRequest {

    @Size(min = 1, message = "must not be blank")
    @Size(max = 50, message = "must be at most 50 characters")
    private String label;

    private boolean labelPresent;

    public UpdateFavouriteRequest() {}

    @JsonSetter("label")
    public void setLabel(String value) {
        this.label = value == null ? null : value.strip();
        this.labelPresent = true;
    }

    /** The stripped label, or null to clear it. */
    public String label() {
        return label;
    }

    /** Whether the body contained a {@code label} property (null included). */
    public boolean labelPresent() {
        return labelPresent;
    }

    /**
     * JavaBeans read access for Spring's binding result, which reads the rejected value of the
     * class-level {@link LabelPresent} violation reported on {@code label}.
     */
    public String getLabel() {
        return label;
    }
}
