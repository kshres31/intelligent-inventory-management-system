package com.kshres31.inventory.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.exception.DuplicateSkuException;
import com.kshres31.inventory.exception.InventoryOperationException;
import com.kshres31.inventory.security.AuthenticationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JSON-over-HTTP adapter built on the JDK's HTTP server. It owns only transport concerns:
 * routing, body limits, JSON, security headers and mapping domain exceptions to status codes.
 * Authorization and business rules stay in the services.
 */
public final class ApiServer implements AutoCloseable {
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Router router;
    private final ObjectMapper json;
    private final Consumer<String> log;

    private ApiServer(HttpServer server, ExecutorService executor, Router router, ObjectMapper json,
                      Consumer<String> log) {
        this.server = server;
        this.executor = executor;
        this.router = router;
        this.json = json;
        this.log = log;
    }

    public static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static ApiServer start(InetSocketAddress address, InventoryApi api, int threads,
                                  Consumer<String> log) throws IOException {
        var router = new Router();
        api.register(router);
        var server = HttpServer.create(address, 0);
        var executor = Executors.newFixedThreadPool(threads);
        var apiServer = new ApiServer(server, executor, router, objectMapper(), log);
        server.createContext("/", apiServer::handle);
        server.setExecutor(executor);
        server.start();
        return apiServer;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status;
        try {
            ApiResponse response;
            try {
                response = dispatch(exchange, method, path);
            } catch (RuntimeException error) {
                response = toErrorResponse(error, exchange);
            }
            status = response.status();
            write(exchange, response);
        } catch (IOException error) {
            status = -1;
        } finally {
            exchange.close();
        }
        // Access log: no query string, headers or body, which may hold tokens or passwords.
        log.accept(String.format("%s %s -> %d (%d ms)", method, path, status, (System.nanoTime() - started) / 1_000_000));
    }

    private ApiResponse dispatch(HttpExchange exchange, String method, String path) throws IOException {
        var match = router.match(method, path)
                .orElseThrow(() -> new ApiException(404, "No such endpoint: " + path));
        byte[] body = readBody(exchange.getRequestBody());
        var request = new ApiRequest(method, path, match.pathParams(), parseQuery(exchange.getRequestURI().getRawQuery()),
                body, exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Authorization"), json);
        return match.handler().handle(request);
    }

    private ApiResponse toErrorResponse(RuntimeException error, HttpExchange exchange) {
        int status;
        String message = error.getMessage();
        if (error instanceof ApiException api) {
            status = api.status();
        } else if (error instanceof AuthenticationException) {
            status = 401;
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        } else if (error instanceof AuthorizationException) {
            status = 403;
        } else if (error instanceof InventoryOperationException || error instanceof DuplicateSkuException) {
            status = 409;
        } else if (error instanceof IllegalArgumentException) {
            status = 400;
        } else {
            status = 500;
            // Internal details (SQL, stack traces) stay in the server log only.
            log.accept("Unhandled " + error.getClass().getName() + ": " + error.getMessage());
            message = "Internal server error";
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("status", status);
        detail.put("message", message);
        return new ApiResponse(status, Map.of("error", detail));
    }

    private void write(HttpExchange exchange, ApiResponse response) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (response.status() == 204 || response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        byte[] bytes = json.writeValueAsBytes(response.body());
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        return body;
    }

    static Map<String, String> parseQuery(String rawQuery) {
        var values = new HashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                values.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                throw new ApiException(400, "Malformed query string");
            }
        }
        return values;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
