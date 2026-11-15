package com.example.weather.api;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link ClientId} handler parameters from the {@code X-Client-Id} header (ADR 0009 §3).
 * Client-scoped handlers declare it first, so the header is checked before the path id and body.
 */
public class ClientIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ClientId.class.equals(parameter.getParameterType());
    }

    @Override
    public ClientId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return ClientId.parse(webRequest.getHeader(ClientId.HEADER));
    }
}
