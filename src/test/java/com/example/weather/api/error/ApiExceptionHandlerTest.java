package com.example.weather.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.api.RequestIds;
import com.example.weather.favourites.FavouriteAlreadyExistsException;
import com.example.weather.favourites.FavouriteNotFoundException;
import com.example.weather.favourites.FavouritesLimitReachedException;
import com.example.weather.favourites.InvalidFavouriteOrderException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
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
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Edge (unit): ConversionNotSupportedException (no converter) is 500 INTERNAL_ERROR, not 400")
    void conversionNotSupportedIsInternalError(CapturedOutput output) throws Exception {
        ConversionNotSupportedException ex = new ConversionNotSupportedException(
                "secret-value", Integer.class, new IllegalStateException("no-converter-secret"));

        ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex, apiRequest("/api/v1/weather/current"));

        assertInternalError(response, output);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Edge (unit): MethodArgumentConversionNotSupportedException is 500 INTERNAL_ERROR, not 400")
    void methodArgumentConversionNotSupportedIsInternalError(CapturedOutput output) throws Exception {
        MethodArgumentConversionNotSupportedException ex = new MethodArgumentConversionNotSupportedException(
                "secret-value", Integer.class, "days", null, new IllegalStateException("no-converter-secret"));

        ResponseEntity<ProblemDetail> response = handler.handleTypeMismatch(ex, apiRequest("/api/v1/weather/current"));

        assertInternalError(response, output);
    }

    private static void assertInternalError(ResponseEntity<ProblemDetail> response, CapturedOutput output) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(body.getProperties())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("requestId", "ui-abc-123")
                .doesNotContainKey("errors");
        assertThat(body.toString())
                .doesNotContain("secret-value")
                .doesNotContain("no-converter-secret")
                .doesNotContain("Conversion")
                .doesNotContain("java.");
        assertThat(output.getAll().lines()
                .anyMatch(l -> l.contains("ERROR") && l.contains("Unhandled error") && l.contains("ui-abc-123")))
                .isTrue();
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

    // --- saved-favourite-locations ---

    private static MockHttpServletRequest favouritesRequest() {
        MockHttpServletRequest request = apiRequest("/api/v1/favourites");
        request.setMethod("POST");
        return request;
    }

    @Test
    @DisplayName("AC-5 (unit): an unreadable body is 400 VALIDATION_FAILED with one fixed body error, no class names or input")
    void unreadableBodyIsValidationFailed() throws Exception {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Cannot deserialize value of type `java.lang.String` from Array value; "
                        + "com.fasterxml.jackson.databind.exc.MismatchedInputException: input SECRET-INPUT",
                new MockHttpInputMessage("SECRET-INPUT".getBytes()));

        ResponseEntity<ProblemDetail> response = handler.handleUnreadableBody(ex, favouritesRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body.getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(body.getDetail()).isEqualTo(ApiExceptionHandler.VALIDATION_DETAIL);
        assertThat(body.getProperties().get("errors")).isEqualTo(List.of(
                new FieldErrorDto("body", "must be a JSON object matching the request schema")));
        String json = new ObjectMapper().writeValueAsString(body);
        assertThat(json).doesNotContain("Jackson").doesNotContain("jackson").doesNotContain("com.")
                .doesNotContain("java.").doesNotContain("SECRET-INPUT");
    }

    @Test
    void unreadableBodyOutsideApiIsRethrown() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0]));
        assertThatThrownBy(() -> handler.handleUnreadableBody(ex, new MockHttpServletRequest("POST", "/elsewhere")))
                .isSameAs(ex);
    }

    @Test
    @DisplayName("AC-23 (unit): an invalid reorder is 400 VALIDATION_FAILED on field ids with the fixed message")
    void invalidOrderIsValidationFailed() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidOrder(
                new InvalidFavouriteOrderException(InvalidFavouriteOrderException.SET_MISMATCH), favouritesRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(response.getBody().getProperties().get("errors")).isEqualTo(List.of(
                new FieldErrorDto("ids", "must contain every favourite id of this client exactly once")));
    }

    @Test
    @DisplayName("AC-20 (unit): a missing favourite is 404 NOT_FOUND with a detail that differs from the routing 404")
    void favouriteNotFound() throws Exception {
        ResponseEntity<ProblemDetail> response =
                handler.handleFavouriteNotFound(new FavouriteNotFoundException(), favouritesRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProblemDetail body = response.getBody();
        assertThat(body.getProperties()).containsEntry("code", "NOT_FOUND");
        assertThat(body.getType()).isEqualTo(URI.create("https://weather.example.com/problems/not-found"));
        assertThat(body.getDetail()).isEqualTo("No favourite exists with this id.")
                .isNotEqualTo(ApiExceptionHandler.NOT_FOUND_DETAIL);
        assertThat(body.getProperties()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("AC-9, AC-27 (unit): a duplicate is 409 FAVOURITE_ALREADY_EXISTS with a fixed detail")
    void favouriteAlreadyExists() throws Exception {
        ResponseEntity<ProblemDetail> response =
                handler.handleFavouriteExists(new FavouriteAlreadyExistsException(), favouritesRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body.getType())
                .isEqualTo(URI.create("https://weather.example.com/problems/favourite-already-exists"));
        assertThat(body.getTitle()).isEqualTo("Favourite already exists");
        assertThat(body.getDetail()).isEqualTo("A favourite for this city already exists.");
        assertThat(body.getProperties()).containsEntry("code", "FAVOURITE_ALREADY_EXISTS")
                .containsEntry("requestId", "ui-abc-123");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/v1/favourites"));
    }

    @Test
    @DisplayName("AC-13, AC-27 (unit): the limit is 409 FAVOURITES_LIMIT_REACHED and the detail carries the max")
    void favouritesLimitReached() throws Exception {
        ResponseEntity<ProblemDetail> response =
                handler.handleFavouritesLimit(new FavouritesLimitReachedException(3), favouritesRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body.getType())
                .isEqualTo(URI.create("https://weather.example.com/problems/favourites-limit-reached"));
        assertThat(body.getTitle()).isEqualTo("Favourites limit reached");
        assertThat(body.getDetail()).isEqualTo("This client already has the maximum of 3 favourites.");
        assertThat(body.getProperties()).containsEntry("code", "FAVOURITES_LIMIT_REACHED");
    }

    @Test
    @DisplayName("AC-27 (unit): the two new codes are 409; the six existing codes keep their status")
    void errorCodeSet() {
        assertThat(ErrorCode.values()).hasSize(8);
        assertThat(ErrorCode.FAVOURITE_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.FAVOURITES_LIMIT_REACHED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.LOCATION_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.UPSTREAM_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
