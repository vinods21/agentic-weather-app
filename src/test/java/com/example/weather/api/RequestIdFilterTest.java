package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit test: filter driven with servlet mocks, no Spring context, no Docker. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void echoesValidIdAndExposesItDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weather/current");
        request.addHeader(RequestIds.HEADER, "ui-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuring = new AtomicReference<>();
        AtomicReference<String> headerDuring = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                mdcDuring.set(MDC.get(RequestIds.MDC_KEY));
                headerDuring.set(res.getHeader(RequestIds.HEADER));
            }
        }));

        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("ui-abc-123");
        assertThat(headerDuring.get()).isEqualTo("ui-abc-123");
        assertThat(mdcDuring.get()).isEqualTo("ui-abc-123");
        assertThat(request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo("ui-abc-123");
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidIdWithUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/nope");
        request.addHeader(RequestIds.HEADER, "bad id!");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader(RequestIds.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo(id);
    }

    @Test
    void skipsNonApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIds.HEADER)).isNull();
        assertThat(request.getAttribute(RequestIds.ATTRIBUTE)).isNull();
    }
}
