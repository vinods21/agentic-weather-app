package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit test: no Spring context, no Docker. */
class ApiCorsPropertiesTest {

    @Test
    void nullBecomesEmpty() {
        assertThat(new ApiCorsProperties(null).allowedOrigins()).isEmpty();
    }

    @Test
    void trimsEntriesAndDropsBlanks() {
        ApiCorsProperties props = new ApiCorsProperties(
                Arrays.asList(" http://localhost:5173 ", "", "  ", null, "https://ui.example.com"));
        assertThat(props.allowedOrigins()).containsExactly("http://localhost:5173", "https://ui.example.com");
    }

    @Test
    void listIsImmutable() {
        List<String> origins = new ApiCorsProperties(List.of("http://a")).allowedOrigins();
        assertThat(origins).isUnmodifiable();
    }
}
