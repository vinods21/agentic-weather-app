package com.example.weather.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minimal OpenAPI 3.0 schema checker built on Jackson trees (no JSON-schema library, ADR 0002).
 * It resolves local {@code $ref}s and checks, recursively:
 * <ul>
 *   <li>every {@code required} property is present and non-null,</li>
 *   <li>no property outside {@code properties} is present (no undocumented fields),</li>
 *   <li>{@code type}, {@code enum}, {@code minimum}/{@code maximum}, {@code minLength}/{@code maxLength},
 *       {@code pattern}, {@code minItems}/{@code maxItems}.</li>
 * </ul>
 */
public final class OpenApiSchemaAssert {

    private final JsonNode spec;

    public OpenApiSchemaAssert(JsonNode spec) {
        this.spec = spec;
    }

    /** Resolves a local reference such as {@code #/components/schemas/Problem}. */
    public JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        int guard = 0;
        while (current != null && current.has("$ref")) {
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                throw new AssertionError("only local $ref supported: " + ref);
            }
            current = spec.at(ref.substring(1));
            if (current.isMissingNode()) {
                throw new AssertionError("unresolvable $ref: " + ref);
            }
            if (++guard > 20) {
                throw new AssertionError("$ref cycle at " + ref);
            }
        }
        return current;
    }

    public JsonNode schema(String name) {
        JsonNode s = spec.at("/components/schemas/" + name);
        if (s.isMissingNode()) {
            throw new AssertionError("no schema " + name);
        }
        return s;
    }

    /** Throws an {@link AssertionError} listing every violation of {@code value} against {@code schema}. */
    public void assertConforms(JsonNode value, JsonNode schema) {
        List<String> violations = new ArrayList<>();
        check(value, resolve(schema), "$", violations);
        if (!violations.isEmpty()) {
            throw new AssertionError("body does not conform to schema:\n  " + String.join("\n  ", violations)
                    + "\nbody: " + value);
        }
    }

    public void assertConforms(JsonNode value, String schemaName) {
        assertConforms(value, schema(schemaName));
    }

    private void check(JsonNode value, JsonNode schema, String path, List<String> out) {
        schema = resolve(schema);
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !typeMatches(value, type)) {
            out.add(path + ": expected " + type + " but was " + value.getNodeType());
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode e : schema.get("enum")) {
                if (e.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.add(path + ": " + value + " not in enum " + schema.get("enum"));
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
                out.add(path + ": " + value + " < minimum " + schema.get("minimum"));
            }
            if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
                out.add(path + ": " + value + " > maximum " + schema.get("maximum"));
            }
        }
        if (value.isTextual()) {
            String s = value.asText();
            if (schema.has("minLength") && s.length() < schema.get("minLength").asInt()) {
                out.add(path + ": shorter than minLength");
            }
            if (schema.has("maxLength") && s.length() > schema.get("maxLength").asInt()) {
                out.add(path + ": longer than maxLength");
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(s).find()) {
                out.add(path + ": '" + s + "' does not match pattern " + schema.get("pattern"));
            }
        }
        if (value.isObject()) {
            JsonNode props = schema.path("properties");
            for (JsonNode req : schema.path("required")) {
                JsonNode v = value.get(req.asText());
                if (v == null || v.isNull()) {
                    out.add(path + "." + req.asText() + ": required property missing or null");
                }
            }
            Set<String> documented = new HashSet<>();
            props.fieldNames().forEachRemaining(documented::add);
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!documented.contains(name)) {
                    out.add(path + "." + name + ": undocumented property");
                } else {
                    check(value.get(name), props.get(name), path + "." + name, out);
                }
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                out.add(path + ": fewer than minItems");
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                out.add(path + ": more than maxItems");
            }
            if (schema.has("items")) {
                for (int i = 0; i < value.size(); i++) {
                    check(value.get(i), schema.get("items"), path + "[" + i + "]", out);
                }
            }
        }
    }

    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }
}
