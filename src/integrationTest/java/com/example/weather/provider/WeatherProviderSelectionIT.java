package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.openmeteo.OpenMeteoWeatherProvider;
import com.example.weather.provider.stub.StubWeatherProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * AC-1..AC-3: provider selection against the real {@code application.yml} defaults.
 *
 * <p>Lives in {@code src/integrationTest} (not {@code src/test} as the design proposed) because
 * CLAUDE.md keeps Spring contexts out of unit tests. The integration-test JVM runs with
 * {@code spring.profiles.active=it} as a system property and with {@code .env} values exported as
 * environment variables, so this test does not use the {@code it} profile: before the config data
 * is loaded, it replaces the {@code systemProperties} source with an empty one and the
 * {@code systemEnvironment} source with an explicit map. No web server, no database, no network.
 */
class WeatherProviderSelectionIT {

    private static ApplicationContextRunner runner(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withInitializer(isolatedEnvironment(env))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withUserConfiguration(WeatherProviderConfiguration.class);
    }

    /** Replaces the host's system properties and environment, so only {@code env} is visible. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedEnvironment(
            Map<String, Object> env) {
        return context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
            sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            env));
            context.getEnvironment().setActiveProfiles();
        };
    }

    @Test
    @DisplayName("AC-1: without WEATHER_PROVIDER exactly one WeatherProvider bean exists and it is the Open-Meteo adapter")
    void ac1_defaultIsOpenMeteo() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).as("no profile (it is not active)").isEmpty();
            assertThat(context.getBeansOfType(WeatherProvider.class)).hasSize(1);
            assertThat(context.getBean(WeatherProvider.class))
                    .isInstanceOf(OpenMeteoWeatherProvider.class)
                    .isNotInstanceOf(StubWeatherProvider.class);

            WeatherProviderProperties p = context.getBean(WeatherProviderProperties.class);
            assertThat(p.type()).isEqualTo("open-meteo");
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(p.openMeteo().geocodingBaseUrl()).isEqualTo(URI.create("https://geocoding-api.open-meteo.com"));
            assertThat(p.openMeteo().forecastBaseUrl()).isEqualTo(URI.create("https://api.open-meteo.com"));
        });
    }

    @Test
    @DisplayName("AC-2: with WEATHER_PROVIDER=stub exactly one WeatherProvider bean exists and it is the stub")
    void ac2_stubSelectedByEnv() {
        runner(Map.of("WEATHER_PROVIDER", "stub")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(WeatherProvider.class)).hasSize(1);
            assertThat(context.getBean(WeatherProvider.class)).isInstanceOf(StubWeatherProvider.class);
        });
    }

    @Test
    @DisplayName("AC-3: WEATHER_PROVIDER=bogus fails startup naming weather.provider.type and the allowed values")
    void ac3_bogusTypeFailsStartup() {
        runner(Map.of("WEATHER_PROVIDER", "bogus")).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = NestedExceptionUtils.getRootCause(context.getStartupFailure());
            assertThat(root).isNotNull();
            assertThat(root.getMessage())
                    .contains("weather.provider.type")
                    .contains("stub")
                    .contains("open-meteo")
                    .doesNotContain("bogus");
        });
    }

    @Test
    @DisplayName("AC-3 (edge): an empty WEATHER_PROVIDER fails startup instead of falling back to a default")
    void ac3_edge_emptyTypeFailsStartup() {
        runner(Map.of("WEATHER_PROVIDER", "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getRootCause(context.getStartupFailure()).getMessage())
                    .contains("weather.provider.type");
        });
    }

    @Test
    @DisplayName("AC-3 (edge): the type is case-sensitive, WEATHER_PROVIDER=STUB fails startup")
    void ac3_edge_upperCaseTypeFailsStartup() {
        runner(Map.of("WEATHER_PROVIDER", "STUB")).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("AC-4: the provider env vars override the application.yml defaults (WEATHER_PROVIDER also set)")
    void ac4_envVarsAreBound() {
        Map<String, Object> env = Map.of(
                "WEATHER_PROVIDER", "open-meteo",
                "WEATHER_PROVIDER_GEOCODING_BASE_URL", "http://127.0.0.1:18081",
                "WEATHER_PROVIDER_FORECAST_BASE_URL", "http://127.0.0.1:18082/",
                "WEATHER_PROVIDER_CONNECT_TIMEOUT", "500ms",
                "WEATHER_PROVIDER_READ_TIMEOUT", "4s");
        runner(env).run(context -> {
            assertThat(context).hasNotFailed();
            WeatherProviderProperties p = context.getBean(WeatherProviderProperties.class);
            assertThat(p.type()).isEqualTo("open-meteo");
            assertThat(p.openMeteo().geocodingBaseUrl()).isEqualTo(URI.create("http://127.0.0.1:18081"));
            assertThat(p.openMeteo().forecastBaseUrl()).isEqualTo(URI.create("http://127.0.0.1:18082"));
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(context.getBean(WeatherProvider.class)).isInstanceOf(OpenMeteoWeatherProvider.class);
        });
    }

    @Test
    @DisplayName("AC-4 (edge): an invalid base URL from the env fails startup without echoing the value")
    void ac4_edge_invalidUrlFailsWithoutEcho() {
        runner(Map.of("WEATHER_PROVIDER_FORECAST_BASE_URL", "https://user:s3cr3t@api.example.test")).run(context -> {
            assertThat(context).hasFailed();
            String message = NestedExceptionUtils.getRootCause(context.getStartupFailure()).getMessage();
            assertThat(message).contains("weather.provider.open-meteo.forecast-base-url").doesNotContain("s3cr3t");
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).as("message of %s", t.getClass().getName())
                        .doesNotContain("s3cr3t");
            }
        });
    }
}
