package com.kshres31.inventory.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal method + path-template router, e.g. {@code /api/v1/alerts/{alertId}/resolution}. */
final class Router {
    @FunctionalInterface
    interface Handler {
        ApiResponse handle(ApiRequest request);
    }

    record Route(String method, String[] segments, Handler handler) {
    }

    record Match(Handler handler, Map<String, String> pathParams) {
    }

    private final List<Route> routes = new ArrayList<>();

    void add(String method, String template, Handler handler) {
        routes.add(new Route(method, split(template), handler));
    }

    /** Empty when nothing matches; throws 405 when the path exists for another method. */
    Optional<Match> match(String method, String path) {
        String[] actual = split(path);
        boolean pathMatched = false;
        for (Route route : routes) {
            var params = matchSegments(route.segments(), actual);
            if (params == null) {
                continue;
            }
            pathMatched = true;
            if (route.method().equals(method)) {
                return Optional.of(new Match(route.handler(), params));
            }
        }
        if (pathMatched) {
            throw new ApiException(405, "Method " + method + " is not allowed for " + path);
        }
        return Optional.empty();
    }

    private static Map<String, String> matchSegments(String[] template, String[] actual) {
        if (template.length != actual.length) {
            return null;
        }
        var params = new HashMap<String, String>();
        for (int i = 0; i < template.length; i++) {
            String expected = template[i];
            if (expected.startsWith("{") && expected.endsWith("}")) {
                params.put(expected.substring(1, expected.length() - 1), actual[i]);
            } else if (!expected.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        String trimmed = path.replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
    }
}
