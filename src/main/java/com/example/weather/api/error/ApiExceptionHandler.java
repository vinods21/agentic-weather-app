package com.example.weather.api.error;

import com.example.weather.api.ApiPaths;
import com.example.weather.api.RequestIds;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure under {@code /api/} to a Problem Details response (ADR 0003). For any other
 * path each handler rethrows the original exception, so Spring Boot's default resolvers apply.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String VALIDATION_DETAIL = "One or more request parameters are invalid.";
    static final String NOT_FOUND_DETAIL = "No endpoint exists at this path.";
    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";
    static final String INTERNAL_DETAIL = "An unexpected error occurred.";
    static final String DAYS_TYPE_MISMATCH = "must be an integer between 1 and 7";
    static final String INVALID_VALUE = "has an invalid value";

    private static final Comparator<FieldErrorDto> ERROR_ORDER =
            Comparator.comparing(FieldErrorDto::field).thenComparing(FieldErrorDto::message);

    /** Also covers {@code MethodArgumentNotValidException} (a subclass). */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBind(BindException ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        List<FieldErrorDto> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::toDto)
                .distinct()
                .sorted(ERROR_ORDER)
                .toList();
        return ProblemFactory.build(ErrorCode.VALIDATION_FAILED, VALIDATION_DETAIL, request, errors);
    }

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleLocationNotFound(
            LocationNotFoundException ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        String detail = "No weather data is available for city '" + ex.city() + "'.";
        return ProblemFactory.build(ErrorCode.LOCATION_NOT_FOUND, detail, request, null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(Exception ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        return ProblemFactory.build(ErrorCode.NOT_FOUND, NOT_FOUND_DETAIL, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        // Only a standard method name goes into the body, never raw input.
        String detail = Arrays.stream(HttpMethod.values())
                .filter(m -> m.name().equals(ex.getMethod()))
                .findFirst()
                .map(m -> "HTTP method " + m.name() + " is not supported for this path.")
                .orElse("The HTTP method is not supported for this path.");
        return ProblemFactory.build(ErrorCode.METHOD_NOT_ALLOWED, detail, request, null, headers);
    }

    @ExceptionHandler(WeatherProviderUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamUnavailable(
            WeatherProviderUnavailableException ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        log.warn("Weather provider unavailable requestId={} path={} error={}: {}",
                RequestIds.current(request), ApiPaths.path(request), ex.getClass().getName(), ex.getMessage());
        return ProblemFactory.build(ErrorCode.UPSTREAM_UNAVAILABLE, UPSTREAM_DETAIL, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        guard(ex, request);
        if (ex instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            // Framework 4xx outside the v1 code set (e.g. 406, 415): keep Spring's defaults.
            throw ex;
        }
        log.error("Unhandled error requestId={} path={}", RequestIds.current(request), ApiPaths.path(request), ex);
        return ProblemFactory.build(ErrorCode.INTERNAL_ERROR, INTERNAL_DETAIL, request, null);
    }

    /** Rethrows {@code ex} unless the request is under {@code /api/}. */
    private static void guard(Exception ex, HttpServletRequest request) throws Exception {
        if (!ApiPaths.isApi(request)) {
            throw ex;
        }
    }

    private static FieldErrorDto toDto(FieldError error) {
        String message;
        if (error.isBindingFailure()) {
            // Spring's default type-mismatch text contains class names; never use it or echo the value.
            message = "days".equals(error.getField()) ? DAYS_TYPE_MISMATCH : INVALID_VALUE;
        } else {
            message = error.getDefaultMessage() == null ? INVALID_VALUE : error.getDefaultMessage();
        }
        return new FieldErrorDto(error.getField(), message);
    }
}
