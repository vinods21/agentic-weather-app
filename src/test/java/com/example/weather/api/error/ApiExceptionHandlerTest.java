package com.example.weather.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.api.RequestIds;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.TypeMismatchException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/** Unit test: plain handler calls with mock servlet requests, no Spring context, no Docker. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static MockHttpServletRequest apiRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(RequestIds.ATTRIBUTE, "ui-abc-123");
        return request;
    }

    @Test
    void validationErrorsUseFixedMessagesAndAreSorted() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "query");
        result.addError(new FieldError("query", "units", "kelvin", false, new String[] {"Pattern"}, null,
                "must be one of: metric, imperial"));
        // A binding failure (any field) gets the generic fixed message, never Spring's text.
        result.addError(new FieldError("query", "days", "abc", true, new String[] {"typeMismatch"}, null,
                "Failed to convert value of type 'java.lang.String' to required type 'java.lang.Integer'"));
        result.addError(new FieldError("query", "city", null, false, new String[] {"NotBlank"}, null,
                "must not be blank"));

        ResponseEntity<ProblemDetail> response =
                handler.handleBind(new BindException(result), apiRequest("/api/v1/weather/forecast"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body.getType()).isEqualTo(URI.create("https://weather.example.com/problems/validation-failed"));
        assertThat(body.getTitle()).isEqualTo("Validation failed");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/v1/weather/forecast"));
        assertThat(body.getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("requestId", "ui-abc-123");
        assertThat(body.getProperties().get("errors")).isEqualTo(List.of(
                new FieldErrorDto("city", "must not be blank"),
                new FieldErrorDto("days", "has an invalid value"),
                new FieldErrorDto("units", "must be one of: metric, imperial")));
    }

    @Test
    @DisplayName("AC-7 (unit): a request-param type mismatch is 400 VALIDATION_FAILED with a fixed message, no class names")
    void methodArgumentTypeMismatchIsValidationFailed() throws Exception {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "xyz-bad", Integer.class, "days", null, new NumberFormatException("For input string: \"xyz-bad\""));

        ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex, apiRequest("/api/v1/weather/forecast"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body.getDetail()).isEqualTo("One or more request parameters are invalid.");
        assertThat(body.getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("requestId", "ui-abc-123")
                .containsEntry("errors", List.of(new FieldErrorDto("days", "has an invalid value")));
        assertThat(body.toString())
                .doesNotContain("java.")
                .doesNotContain("Integer")
                .doesNotContain("xyz-bad")
                .doesNotContain("NumberFormat");
    }

    @Test
    @DisplayName("Edge (unit): a type mismatch without a property name is 400 with an empty errors list")
    void typeMismatchWithoutPropertyName() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(
                new TypeMismatchException("abc", Integer.class), apiRequest("/api/v1/weather/forecast"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("errors", List.of());
    }

    @Test
    void typeMismatchOutsideApiIsRethrown() {
        TypeMismatchException ex = new TypeMismatchException("abc", Integer.class);
        assertThatThrownBy(() -> handler.handleTypeMismatch(ex, new MockHttpServletRequest("GET", "/actuator/x")))
                .isSameAs(ex);
    }

    @Test
    void locationNotFoundNamesTheCity() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleLocationNotFound(
                new LocationNotFoundException("Atlantis"), apiRequest("/api/v1/weather/current"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("No weather data is available for city 'Atlantis'.");
        assertThat(response.getBody().getProperties()).containsEntry("code", "LOCATION_NOT_FOUND")
                .doesNotContainKey("errors");
    }

    @Test
    void unknownApiPathIsNotFound() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/v1/does-not-exist", new HttpHeaders()),
                apiRequest("/api/v1/does-not-exist"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getInstance()).isEqualTo(URI.create("/api/v1/does-not-exist"));
        assertThat(response.getBody().getProperties()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void methodNotAllowedSetsAllowHeader() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("POST", Set.of("GET")),
                apiRequest("/api/v1/weather/current"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET);
        assertThat(response.getBody().getDetail()).isEqualTo("HTTP method POST is not supported for this path.");
    }

    @Test
    void methodNotAllowedNeverEchoesNonStandardMethod() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("EVIL<script>", Set.of("GET")),
                apiRequest("/api/v1/weather/current"));
        assertThat(response.getBody().getDetail()).doesNotContain("EVIL");
    }

    @Test
    void upstreamUnavailableIs503() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleUpstreamUnavailable(
                new WeatherProviderUnavailableException("timeout talking to upstream", null),
                apiRequest("/api/v1/weather/current"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getDetail()).doesNotContain("timeout");
        assertThat(response.getBody().getProperties()).containsEntry("code", "UPSTREAM_UNAVAILABLE");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("AC-13 (unit): the 503 WARN line names the exception and cause classes, never the provider message")
    void upstreamUnavailableWarnLogsNoProviderMessage(CapturedOutput output) throws Exception {
        handler.handleUpstreamUnavailable(
                new WeatherProviderUnavailableException("GET https://upstream.example/data?appid=SECRET-KEY failed",
                        new java.io.IOException("connect to https://upstream.example/data?appid=SECRET-KEY")),
                apiRequest("/api/v1/weather/current"));

        String warn = output.getAll().lines()
                .filter(l -> l.contains("WARN") && l.contains("ui-abc-123"))
                .findFirst().orElseThrow();
        assertThat(warn)
                .contains("error=" + WeatherProviderUnavailableException.class.getName())
                .contains("cause=java.io.IOException")
                .doesNotContain("SECRET-KEY")
                .doesNotContain("upstream.example");
        assertThat(output.getAll()).doesNotContain("SECRET-KEY");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("AC-13 (unit, edge): a 503 without a cause logs cause=none and still no provider message")
    void upstreamUnavailableWithoutCauseLogsNone(CapturedOutput output) throws Exception {
        handler.handleUpstreamUnavailable(
                new WeatherProviderUnavailableException("token=SECRET-NO-CAUSE expired", null),
                apiRequest("/api/v1/weather/forecast"));

        String warn = output.getAll().lines()
                .filter(l -> l.contains("WARN") && l.contains("ui-abc-123"))
                .findFirst().orElseThrow();
        assertThat(warn)
                .contains("path=/api/v1/weather/forecast")
                .contains("error=" + WeatherProviderUnavailableException.class.getName())
                .contains("cause=none");
        assertThat(output.getAll()).doesNotContain("SECRET-NO-CAUSE");
    }

    @Test
    void unexpectedErrorHidesMessageAndClass() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
                new IllegalStateException("boom-secret"), apiRequest("/api/v1/weather/current"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(response.getBody().toString()).doesNotContain("boom-secret").doesNotContain("IllegalState");
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void framework4xxOutsideCodeSetIsRethrown() {
        HttpMediaTypeNotAcceptableException ex = new HttpMediaTypeNotAcceptableException("nope");
        assertThatThrownBy(() -> handler.handleUnexpected(ex, apiRequest("/api/v1/weather/current"))).isSameAs(ex);
    }

    @Test
    void nonApiPathsAreRethrown() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/nope");
        NoHandlerFoundException notFound = new NoHandlerFoundException("GET", "/actuator/nope", new HttpHeaders());
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> handler.handleNotFound(notFound, request)).isSameAs(notFound);
        assertThatThrownBy(() -> handler.handleUnexpected(boom, request)).isSameAs(boom);
        assertThatThrownBy(() -> handler.handleUnexpected(boom, new MockHttpServletRequest("GET", "/apix")))
                .isSameAs(boom);
    }

    @Test
    void requestIdFallsBackToGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weather/current");
        ResponseEntity<ProblemDetail> response =
                handler.handleLocationNotFound(new LocationNotFoundException("x"), request);
        Object id = response.getBody().getProperties().get("requestId");
        assertThat(java.util.UUID.fromString((String) id)).isNotNull();
        assertThat(request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo(id);
    }
}
