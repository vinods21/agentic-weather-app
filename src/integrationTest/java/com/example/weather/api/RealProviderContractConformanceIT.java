package com.example.weather.api;

import com.example.weather.support.FakeOpenMeteo;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ContextConfiguration;

/**
 * AC-23: the ui-backend-api-contracts AC-22 conformance checks, run unchanged against the real
 * Open-Meteo adapter and the fake upstream (London is known, Atlantis is not). Shares the
 * real-provider Spring context with {@code RealProviderIntegrationTestBase} subclasses.
 */
@ContextConfiguration(initializers = FakeOpenMeteo.RealProviderInitializer.class)
class RealProviderContractConformanceIT extends ContractConformanceIT {

    @BeforeEach
    void resetFakeOpenMeteo() {
        FakeOpenMeteo.instance().reset();
    }
}
