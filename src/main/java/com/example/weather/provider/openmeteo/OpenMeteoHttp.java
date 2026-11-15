package com.example.weather.provider.openmeteo;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient}s for Open-Meteo (ADR 0006): JDK {@link HttpClient} with a connect
 * timeout and no redirects, a request-factory read timeout, and the default URI encoding
 * ({@code TEMPLATE_AND_VALUES}) from {@link RestClient.Builder#baseUrl(String)}.
 */
final class OpenMeteoHttp {

    private OpenMeteoHttp() {}

    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    static RestClient client(RestClient.Builder builder, URI baseUrl, JdkClientHttpRequestFactory factory) {
        return builder.clone()
                .baseUrl(baseUrl.toString())
                .requestFactory(factory)
                .build();
    }
}
