package com.example.weather.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request-id rules (ADR 0003): echo a valid client id, otherwise generate a UUID. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIds.class.getName();
    public static final String MDC_KEY = "requestId";
    public static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private RequestIds() {}

    /** Returns {@code incoming} if it is a valid id, otherwise a fresh random UUID. */
    public static String resolve(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /** The id the filter stored for this request, or a fresh UUID (then stored) if there is none. */
    public static String current(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String id && !id.isEmpty()) {
            return id;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }
}
