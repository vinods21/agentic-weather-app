package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Drift guard: mapped /api/v1 operations equal the OpenAPI operations (AC-21). */
class OpenApiDriftIT extends ApiIntegrationTestBase {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("AC-21: (method, path) pairs mapped under /api/v1 equal the operations in the OpenAPI document")
    void ac21_mappingsEqualSpecOperations() {
        Set<String> mapped = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = info.getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/v1/") || pattern.equals("/api/v1/openapi.json")) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                assertThat(methods).as("mapping %s (%s) must declare explicit HTTP methods", pattern, e.getValue())
                        .isNotEmpty();
                methods.forEach(m -> mapped.add(m.name() + " " + pattern));
            }
        }

        Set<String> documented = new TreeSet<>();
        JsonNode paths = json(get("/api/v1/openapi.json")).get("paths");
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> path = it.next();
            path.getValue().fieldNames().forEachRemaining(op -> {
                if (HTTP_METHODS.contains(op)) {
                    documented.add(op.toUpperCase() + " " + path.getKey());
                }
            });
        }

        assertThat(mapped).as("mapped operations").isNotEmpty();
        assertThat(mapped).isEqualTo(documented);
    }
}
