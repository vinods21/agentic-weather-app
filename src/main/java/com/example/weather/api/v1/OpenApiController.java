package com.example.weather.api.v1;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the hand-written OpenAPI document for v1 (ADR 0002). */
@RestController
public class OpenApiController {

    static final String SPEC_LOCATION = "openapi/openapi-v1.json";

    private final byte[] spec;

    public OpenApiController() {
        this.spec = load(SPEC_LOCATION);
    }

    @GetMapping(value = "/api/v1/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> openApi() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(spec);
    }

    /** Loads the document once; a missing file fails application startup. */
    static byte[] load(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("OpenAPI document not found on classpath: " + location, e);
        }
    }
}
