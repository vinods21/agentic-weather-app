package com.example.weather.api.error;

import com.example.weather.api.ApiPaths;
import com.example.weather.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Builds every API error body: RFC 9457 Problem Details plus {@code code}, {@code requestId} and,
 * for validation failures only, {@code errors}. Content type is {@code application/problem+json}.
 */
public final class ProblemFactory {

    private ProblemFactory() {}

    public static ResponseEntity<ProblemDetail> build(
            ErrorCode code, String detail, HttpServletRequest request, List<FieldErrorDto> errors) {
        return build(code, detail, request, errors, new HttpHeaders());
    }

    public static ResponseEntity<ProblemDetail> build(
            ErrorCode code, String detail, HttpServletRequest request, List<FieldErrorDto> errors,
            HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(instance(request));
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", RequestIds.current(request));
        if (errors != null) {
            problem.setProperty("errors", List.copyOf(errors));
        }
        return ResponseEntity.status(code.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** The request path (no query string) as a relative URI. */
    static URI instance(HttpServletRequest request) {
        String path = ApiPaths.path(request);
        try {
            return new URI(path);
        } catch (URISyntaxException e) {
            try {
                return new URI(null, null, path, null);
            } catch (URISyntaxException ignored) {
                return URI.create(ApiPaths.API_PREFIX);
            }
        }
    }
}
