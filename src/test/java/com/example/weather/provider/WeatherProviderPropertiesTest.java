package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.WeatherProviderProperties.OpenMeteo;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class WeatherProviderPropertiesTest {

    private static WeatherProviderProperties props(String type) {
        return new WeatherProviderProperties(type, null, null, null);
    }

    @Test
    @DisplayName("AC-1: type defaults to open-meteo, with default timeouts and base URLs")
    void defaults() {
        WeatherProviderProperties p = props(null);
        assertThat(p.type()).isEqualTo("open-meteo");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.openMeteo().geocodingBaseUrl()).isEqualTo(URI.create("https://geocoding-api.open-meteo.com"));
        assertThat(p.openMeteo().forecastBaseUrl()).isEqualTo(URI.create("https://api.open-meteo.com"));
    }

    @ParameterizedTest
    @DisplayName("AC-1/AC-2: stub and open-meteo are accepted")
    @ValueSource(strings = {"stub", "open-meteo"})
    void allowedTypes(String type) {
        assertThat(props(type).type()).isEqualTo(type);
    }

    @ParameterizedTest
    @DisplayName("AC-3: any other type fails, naming the property and the allowed values")
    @ValueSource(strings = {"bogus", "", " ", "STUB", "Open-Meteo", "open_meteo", " stub"})
    void invalidType(String type) {
        assertThatThrownBy(() -> props(type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weather.provider.type")
                .hasMessageContaining("stub")
                .hasMessageContaining("open-meteo");
    }

    @Test
    @DisplayName("AC-3: the invalid value is not echoed")
    void invalidTypeNotEchoed() {
        assertThatThrownBy(() -> props("bogus-value")).message().doesNotContain("bogus-value");
    }

    @ParameterizedTest
    @DisplayName("Timeouts must be positive")
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void nonPositiveTimeouts(String duration) {
        Duration d = Duration.parse(duration);
        assertThatThrownBy(() -> new WeatherProviderProperties("stub", d, null, null))
                .hasMessageContaining("weather.provider.connect-timeout");
        assertThatThrownBy(() -> new WeatherProviderProperties("stub", null, d, null))
                .hasMessageContaining("weather.provider.read-timeout");
    }

    @ParameterizedTest
    @DisplayName("Valid base URLs are accepted and lose a trailing slash")
    @ValueSource(strings = {"http://127.0.0.1:9", "http://127.0.0.1:9/", "https://api.example.com", "HTTPS://api.example.com/"})
    void validUrls(String url) {
        OpenMeteo o = new OpenMeteo(URI.create(url), URI.create(url));
        assertThat(o.geocodingBaseUrl().toString()).doesNotEndWith("/");
        assertThat(o.geocodingBaseUrl().getHost()).isEqualTo(URI.create(url).getHost());
        assertThat(o.forecastBaseUrl()).isEqualTo(o.geocodingBaseUrl());
    }

    @ParameterizedTest
    @DisplayName("Invalid base URLs fail without echoing the value")
    @ValueSource(strings = {
        "ftp://example.com", "/relative", "http:opaque", "https://user:secret@example.com",
        "https://example.com/v1", "https://example.com?x=secret", "https://example.com#secret", "http:///nohost"
    })
    void invalidUrls(String url) {
        assertThatThrownBy(() -> new OpenMeteo(URI.create(url), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weather.provider.open-meteo.geocoding-base-url")
                .message().doesNotContain("secret").doesNotContain(url);
        assertThatThrownBy(() -> new OpenMeteo(null, URI.create(url)))
                .hasMessageContaining("weather.provider.open-meteo.forecast-base-url");
    }
}
