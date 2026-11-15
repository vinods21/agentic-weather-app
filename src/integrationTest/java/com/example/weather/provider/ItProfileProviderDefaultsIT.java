package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.support.ApiIntegrationTestBase;
import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/** AC-5: the {@code it} profile pins the stub and loopback base URLs as literals. */
class ItProfileProviderDefaultsIT extends ApiIntegrationTestBase {

    @Autowired
    Environment environment;

    @Autowired
    ApplicationContext context;

    @Autowired
    WeatherProviderProperties properties;

    @Test
    @DisplayName("AC-5: in the it profile weather.provider.type resolves to stub and the bean is the stub")
    void ac5_typeIsStub() {
        assertThat(environment.getProperty("weather.provider.type")).isEqualTo("stub");
        assertThat(properties.type()).isEqualTo(WeatherProviderProperties.STUB);
        assertThat(context.getBeansOfType(WeatherProvider.class)).hasSize(1);
        assertThat(context.getBean(WeatherProvider.class)).isInstanceOf(StubWeatherProvider.class);
    }

    @Test
    @DisplayName("AC-5: in the it profile both upstream base URLs are loopback, never an Open-Meteo host")
    void ac5_baseUrlsAreLoopback() {
        for (String key : new String[] {
                "weather.provider.open-meteo.geocoding-base-url", "weather.provider.open-meteo.forecast-base-url"}) {
            String value = environment.getProperty(key);
            assertThat(value).as(key).isNotNull().doesNotContain("open-meteo");
            assertThat(URI.create(value).getHost()).as(key).isEqualTo("127.0.0.1");
        }
        assertThat(properties.openMeteo().geocodingBaseUrl().getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.openMeteo().forecastBaseUrl().getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("AC-5: application-it.yml sets the provider type and base URLs as literals, not placeholders")
    void ac5_itProfileValuesAreLiterals() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-it.yml"));
        Properties it = yaml.getObject();
        assertThat(it).isNotNull();

        assertThat(it.getProperty("weather.provider.type")).isEqualTo("stub");
        assertThat(it.getProperty("weather.provider.open-meteo.geocoding-base-url")).isEqualTo("http://127.0.0.1:9");
        assertThat(it.getProperty("weather.provider.open-meteo.forecast-base-url")).isEqualTo("http://127.0.0.1:9");
        it.stringPropertyNames().stream()
                .filter(k -> k.startsWith("weather.provider."))
                .forEach(k -> assertThat(it.getProperty(k)).as(k).doesNotContain("${"));
    }
}
