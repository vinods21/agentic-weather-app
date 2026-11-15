package com.example.weather.api;

import jakarta.servlet.http.HttpServletRequest;

/** Path constants for the HTTP API and the {@code /api/} guard used by the filter and advice. */
public final class ApiPaths {

    public static final String API_PREFIX = "/api/";
    public static final String V1 = "/api/v1";

    private ApiPaths() {}

    /** The request path without context path and query string (still URL-encoded). */
    public static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** True when the request path is under {@code /api/}. */
    public static boolean isApi(HttpServletRequest request) {
        return path(request).startsWith(API_PREFIX);
    }
}
