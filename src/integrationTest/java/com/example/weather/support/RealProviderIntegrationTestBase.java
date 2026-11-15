package com.example.weather.support;

import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Route;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base for ITs that exercise the real Open-Meteo adapter against {@link FakeOpenMeteo}
 * ({@code weather.provider.type=open-meteo}, both base URLs on the fake, 1 s timeouts). All
 * subclasses without further customisation share one cached Spring context.
 */
@ContextConfiguration(initializers = FakeOpenMeteo.RealProviderInitializer.class)
public abstract class RealProviderIntegrationTestBase extends ApiIntegrationTestBase {

    protected final FakeOpenMeteo fake = FakeOpenMeteo.instance();

    @BeforeEach
    void resetFakeOpenMeteo() {
        fake.reset();
    }

    protected List<RecordedRequest> received(Route route) {
        return fake.requests(route);
    }
}
