package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit test: no Spring context, no Docker. */
class RequestIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"ui-abc-123", "A.b_C-9", "x"})
    void validIdsAreEchoed(String id) {
        assertThat(RequestIds.resolve(id)).isEqualTo(id);
    }

    @Test
    void sixtyFourCharactersIsValid() {
        String id = "a".repeat(64);
        assertThat(RequestIds.resolve(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad id!", "line\r\nbreak", "semi;colon", "ünïcode"})
    void invalidIdsAreReplacedByUuid(String id) {
        String resolved = RequestIds.resolve(id);
        assertThat(resolved).isNotEqualTo(id);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    void sixtyFiveCharactersIsReplaced() {
        String id = "a".repeat(65);
        assertThat(RequestIds.resolve(id)).isNotEqualTo(id);
    }

    @Test
    void currentReturnsStoredAttributeOrStableGeneratedId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIds.ATTRIBUTE, "stored-id");
        assertThat(RequestIds.current(request)).isEqualTo("stored-id");

        MockHttpServletRequest empty = new MockHttpServletRequest();
        String generated = RequestIds.current(empty);
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(RequestIds.current(empty)).isEqualTo(generated);
    }
}
