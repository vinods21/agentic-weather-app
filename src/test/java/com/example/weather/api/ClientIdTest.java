package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.api.error.ApiExceptionHandler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** Unit test: no Spring context, no Docker. */
class ClientIdTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a b", "a/b", "ü", " abc", "abc ", "a\nb", "abc\n", "semi;colon"})
    @DisplayName("AC-15: a missing, empty or ill-formed client id is rejected")
    void invalid(String raw) {
        assertThatThrownBy(() -> ClientId.parse(raw)).isInstanceOf(InvalidClientIdException.class);
    }

    @Test
    @DisplayName("AC-15: 64 characters are accepted, 65 are not")
    void length() {
        assertThat(ClientId.parse("a".repeat(64)).value()).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> ClientId.parse("a".repeat(65))).isInstanceOf(InvalidClientIdException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "A.b_C-9", "0b6f2c9e-3a1d-4c8e-9f12-5d7a8b9c0e1f"})
    @DisplayName("AC-15: valid ids are kept exactly (case-sensitive, no stripping)")
    void valid(String raw) {
        assertThat(ClientId.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    @DisplayName("AC-15: the error message is the fixed pattern text")
    void message() {
        assertThat(ClientId.MESSAGE).isEqualTo("must match ^[A-Za-z0-9._-]{1,64}$");
        assertThat(ClientId.HEADER).isEqualTo("X-Client-Id");
    }

    @Test
    @DisplayName("AC-30: toString and the exception never contain the value")
    void redacted() {
        assertThat(ClientId.parse("cid-SECRET30").toString()).doesNotContain("cid-SECRET30");
        assertThatThrownBy(() -> ClientId.parse("bad value SECRET"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    }

    @Test
    @DisplayName("AC-15: the resolver reads and validates the X-Client-Id header")
    void resolver() throws Exception {
        ClientIdArgumentResolver resolver = new ClientIdArgumentResolver();
        Method m = ClientIdTest.class.getDeclaredMethod("sample", ClientId.class, String.class);
        assertThat(resolver.supportsParameter(new MethodParameter(m, 0))).isTrue();
        assertThat(resolver.supportsParameter(new MethodParameter(m, 1))).isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/favourites");
        request.addHeader("X-Client-Id", "cid-1");
        assertThat(resolver.resolveArgument(new MethodParameter(m, 0), null, new ServletWebRequest(request), null))
                .isEqualTo(new ClientId("cid-1"));

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/v1/favourites");
        assertThatThrownBy(() -> resolver.resolveArgument(new MethodParameter(m, 0), null,
                new ServletWebRequest(missing), null)).isInstanceOf(InvalidClientIdException.class);
    }

    @Test
    @DisplayName("AC-15: the handler maps an invalid client id to 400 with the X-Client-Id field error")
    void handler() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/favourites");
        var response = new ApiExceptionHandler().handleInvalidClientId(new InvalidClientIdException(), request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(response.getBody().getProperties().get("errors").toString())
                .contains("X-Client-Id").contains("must match ^[A-Za-z0-9._-]{1,64}$");
    }

    @SuppressWarnings("unused")
    private static void sample(ClientId clientId, String other) {}
}
