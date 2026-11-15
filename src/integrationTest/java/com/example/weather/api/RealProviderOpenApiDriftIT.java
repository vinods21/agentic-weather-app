package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.openmeteo.OpenMeteoWeatherProvider;
import com.example.weather.support.FakeOpenMeteo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

/**
 * AC-23: the ui-backend-api-contracts AC-21 drift guard, run against the real adapter, plus a
 * check that the served OpenAPI document is the unchanged {@code openapi-v1.json}.
 */
@ContextConfiguration(initializers = FakeOpenMeteo.RealProviderInitializer.class)
class RealProviderOpenApiDriftIT extends OpenApiDriftIT {

    @Autowired
    WeatherProvider provider;

    @BeforeEach
    void resetFakeOpenMeteo() {
        FakeOpenMeteo.instance().reset();
    }

    @Test
    @DisplayName("AC-23: the drift and conformance checks really run with the Open-Meteo adapter")
    void ac23_realProviderIsActive() {
        assertThat(provider).isInstanceOf(OpenMeteoWeatherProvider.class);
    }

    @Test
    @DisplayName("AC-23: the openapi-v1.json on the classpath is byte-for-byte the checked-in source (no build-time rewrite)")
    void ac23_openApiDocumentUnchanged() throws IOException {
        byte[] source = Files.readAllBytes(Path.of("src/main/resources/openapi/openapi-v1.json"));
        byte[] classpath;
        try (InputStream in = getClass().getResourceAsStream("/openapi/openapi-v1.json")) {
            assertThat(in).as("openapi-v1.json on the classpath").isNotNull();
            classpath = in.readAllBytes();
        }
        assertThat(Arrays.equals(source, classpath)).isTrue();
    }
}
