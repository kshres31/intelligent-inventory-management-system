package com.kshres31.inventory.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** One parsed HTTP request with typed accessors that fail with 400 on bad input. */
public final class ApiRequest {
    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> query;
    private final byte[] body;
    private final String contentType;
    private final String authorization;
    private final ObjectMapper json;
    private JsonNode parsed;

    ApiRequest(String method, String path, Map<String, String> pathParams, Map<String, String> query,
               byte[] body, String contentType, String authorization, ObjectMapper json) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.query = query;
        this.body = body;
        this.contentType = contentType;
        this.authorization = authorization;
        this.json = json;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public long pathLong(String name) {
        try {
            long value = Long.parseLong(pathParams.get(name));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error) {
            throw new ApiException(400, "Path parameter '" + name + "' must be a positive integer");
        }
    }

    public String query(String name, String fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public int queryInt(String name, int fallback) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new ApiException(400, "Query parameter '" + name + "' must be an integer");
        }
    }

    public boolean queryBoolean(String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new ApiException(400, "Query parameter '" + name + "' must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    /** The bearer token from {@code Authorization}, or null. */
    public String bearerToken() {
        if (authorization == null) {
            return null;
        }
        String[] parts = authorization.strip().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1] : null;
    }

    public JsonNode json() {
        if (parsed != null) {
            return parsed;
        }
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new ApiException(415, "Content-Type must be application/json");
        }
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ApiException(400, "Request body must be a JSON object");
            }
            parsed = node;
            return node;
        } catch (IOException error) {
            throw new ApiException(400, "Request body is not valid JSON");
        }
    }

    public int requiredInt(String field) {
        JsonNode value = json().get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) {
            throw new ApiException(400, "Field '" + field + "' must be an integer");
        }
        return value.intValue();
    }

    public long requiredLong(String field) {
        JsonNode value = json().get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ApiException(400, "Field '" + field + "' must be an integer");
        }
        return value.longValue();
    }

    /** An integer field that may be JSON null. Missing is an error so intent is explicit. */
    public Integer nullableInt(String field) {
        JsonNode node = json();
        if (!node.has(field)) {
            throw new ApiException(400, "Field '" + field + "' is required (use null to clear)");
        }
        JsonNode value = node.get(field);
        return value.isNull() ? null : requiredInt(field);
    }

    public String requiredString(String field) {
        String value = optionalString(field);
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "Field '" + field + "' is required");
        }
        return value;
    }

    public String optionalString(String field) {
        JsonNode value = json().get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ApiException(400, "Field '" + field + "' must be a string");
        }
        return value.textValue();
    }
}
