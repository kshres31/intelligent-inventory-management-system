package com.kshres31.inventory.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.notification.ConsoleNotificationChannel;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.notification.RetryPolicy;
import com.kshres31.inventory.repository.JdbcAlertNotificationRepository;
import com.kshres31.inventory.repository.JdbcInventoryRepository;
import com.kshres31.inventory.repository.JdbcReportRepository;
import com.kshres31.inventory.repository.JdbcUserRepository;
import com.kshres31.inventory.security.AuthPolicy;
import com.kshres31.inventory.security.AuthenticationService;
import com.kshres31.inventory.security.JdbcCredentialRepository;
import com.kshres31.inventory.security.PasswordHasher;
import com.kshres31.inventory.service.AlertNotificationService;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ReportingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryApiIntegrationTest {
    private static final String PASSWORD = "integration password 1";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> serverLog = Collections.synchronizedList(new ArrayList<>());

    private DatabaseTestSupport database;
    private ApiServer server;
    private long productId;
    private long chicagoId;
    private long madisonId;
    private String adminToken;
    private String managerToken;
    private String employeeToken;

    @BeforeAll
    void start() throws Exception {
        database = DatabaseTestSupport.start();
        var connections = (com.kshres31.inventory.config.ConnectionFactory) () -> database.dataSource().getConnection();
        var users = new JdbcUserRepository(connections);
        var auth = new AuthenticationService(users, new JdbcCredentialRepository(connections),
                new PasswordHasher(10_000), AuthPolicy.defaults());
        var inventoryRepository = new JdbcInventoryRepository(connections);
        var notificationRepository = new JdbcAlertNotificationRepository(connections);
        var quiet = new PrintStream(OutputStream.nullOutputStream());
        var api = new InventoryApi(auth, new InventoryService(inventoryRepository),
                new ReportingService(inventoryRepository, new JdbcReportRepository(connections)),
                new AlertNotificationService(notificationRepository, new NotificationDispatcher(notificationRepository,
                        new ConsoleNotificationChannel(quiet), RetryPolicy.defaults(), Duration.ofMinutes(1))),
                connections);
        server = ApiServer.start(new InetSocketAddress("127.0.0.1", 0), api, 8, serverLog::add);

        auth.bootstrapAdmin("admin.demo", PASSWORD.toCharArray());
        adminToken = login("admin.demo", PASSWORD).get("token").asText();
        var admin = auth.authenticate(adminToken);
        auth.setPassword(admin, "manager.demo", PASSWORD.toCharArray());
        auth.setPassword(admin, "employee.demo", PASSWORD.toCharArray());
        managerToken = login("manager.demo", PASSWORD).get("token").asText();
        employeeToken = login("employee.demo", PASSWORD).get("token").asText();

        productId = id("SELECT id FROM product WHERE sku = 'USB-C-2M'");
        chicagoId = id("SELECT id FROM warehouse WHERE code = 'CHI-01'");
        madisonId = id("SELECT id FROM warehouse WHERE code = 'MAD-01'");
    }

    @AfterAll
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void resetStock() throws SQLException {
        execute("UPDATE inventory SET quantity = 30, low_stock_threshold = NULL WHERE product_id = " + productId);
        execute("DELETE FROM inventory_alert");
    }

    @Test
    void healthIsPublicAndReportsTheDatabase() throws Exception {
        var response = send("GET", "/api/v1/health", null, null);

        assertEquals(200, response.statusCode());
        assertEquals("up", body(response).get("database").asText());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void loginIssuesBearerTokenAndRejectsBadCredentialsGenerically() throws Exception {
        var good = send("POST", "/api/v1/sessions", null, "{\"username\":\"manager.demo\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, good.statusCode());
        assertEquals("Bearer", body(good).get("tokenType").asText());
        assertEquals("MANAGER", body(good).get("user").get("role").asText());

        var wrong = send("POST", "/api/v1/sessions", null, "{\"username\":\"manager.demo\",\"password\":\"nope nope nope\"}");
        var unknown = send("POST", "/api/v1/sessions", null, "{\"username\":\"ghost\",\"password\":\"nope nope nope\"}");
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals(message(wrong), message(unknown));
        assertEquals("Bearer", wrong.headers().firstValue("WWW-Authenticate").orElse(""));
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        assertEquals(401, send("GET", "/api/v1/inventory", null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/inventory", "forged", null).statusCode());

        var me = send("GET", "/api/v1/me", employeeToken, null);
        assertEquals(200, me.statusCode());
        assertEquals("employee.demo", body(me).get("username").asText());
    }

    @Test
    void stockMovesEnforceRolesAndBusinessRules() throws Exception {
        String path = "/api/v1/inventory/" + productId + "/" + chicagoId;

        var sale = send("POST", path + "/sales", employeeToken, "{\"quantity\":4,\"reference\":\"API-SALE-1\"}");
        assertEquals(200, sale.statusCode());
        assertEquals(26, body(sale).get("quantity").asInt());

        var adjust = send("POST", path + "/adjustments", employeeToken, "{\"change\":-1,\"reason\":\"damaged\"}");
        assertEquals(403, adjust.statusCode(), "employees cannot adjust");
        assertEquals(200, send("POST", path + "/adjustments", managerToken,
                "{\"change\":-1,\"reason\":\"damaged\"}").statusCode());

        var oversell = send("POST", path + "/sales", employeeToken, "{\"quantity\":1000}");
        assertEquals(409, oversell.statusCode());
        assertEquals("Insufficient stock: requested 1000, available 25", message(oversell),
                "business message only, without driver prefix or PL/pgSQL context");
        assertEquals(25, quantity(chicagoId), "failed sale must not change stock");

        var transfer = send("POST", "/api/v1/transfers", managerToken, "{\"productId\":" + productId
                + ",\"fromWarehouseId\":" + chicagoId + ",\"toWarehouseId\":" + madisonId + ",\"quantity\":5}");
        assertEquals(201, transfer.statusCode());
        assertEquals(20, quantity(chicagoId));

        var history = send("GET", "/api/v1/products/" + productId + "/transactions?page=1&pageSize=2", employeeToken, null);
        assertEquals(200, history.statusCode());
        assertEquals(2, body(history).get("items").size());
        assertTrue(body(history).get("hasNext").asBoolean());

        assertEquals(409, send("POST", "/api/v1/inventory/999999/" + chicagoId + "/receipts", managerToken,
                "{\"quantity\":1}").statusCode(), "unknown product is a conflict, not a server error");
    }

    @Test
    void thresholdsAlertsAndNotificationsAreReachable() throws Exception {
        String path = "/api/v1/inventory/" + productId + "/" + chicagoId + "/threshold";
        assertEquals(403, send("PUT", path, employeeToken, "{\"threshold\":40}").statusCode());

        var set = send("PUT", path, managerToken, "{\"threshold\":40}");
        assertEquals(200, set.statusCode());
        assertEquals(40, body(set).get("effectiveThreshold").asInt());

        var alerts = body(send("GET", "/api/v1/alerts?status=open", employeeToken, null));
        assertEquals(1, alerts.size());
        long alertId = alerts.get(0).get("id").asLong();

        var notificationList = body(send("GET", "/api/v1/notifications?status=pending", managerToken, null));
        assertTrue(notificationList.size() >= 1);
        assertEquals("RAISED", notificationList.get(0).get("event").asText());
        assertEquals("USB-C-2M", notificationList.get(0).get("payload").get("sku").asText());
        assertFalse(notificationList.get(0).has("claimToken"));

        assertEquals(403, send("POST", "/api/v1/alerts/" + alertId + "/resolution", employeeToken,
                "{\"note\":\"ordered more\"}").statusCode());
        assertEquals(200, send("POST", "/api/v1/alerts/" + alertId + "/resolution", managerToken,
                "{\"note\":\"ordered more\"}").statusCode());
        assertEquals(409, send("POST", "/api/v1/alerts/" + alertId + "/resolution", managerToken,
                "{\"note\":\"again\"}").statusCode());

        var cleared = send("PUT", path, managerToken, "{\"threshold\":null}");
        assertEquals(10, body(cleared).get("effectiveThreshold").asInt());
        assertTrue(body(cleared).get("override").isNull());
    }

    @Test
    void malformedRequestsGetPreciseClientErrors() throws Exception {
        String sales = "/api/v1/inventory/" + productId + "/" + chicagoId + "/sales";

        assertEquals(400, send("POST", sales, employeeToken, "{not json").statusCode());
        assertEquals(400, send("POST", sales, employeeToken, "{\"quantity\":\"four\"}").statusCode());
        assertEquals(400, send("POST", sales, employeeToken, "{\"quantity\":1.5}").statusCode());
        assertEquals(400, send("POST", sales, employeeToken, "[1,2]").statusCode());
        assertEquals(400, send("POST", sales, employeeToken, "{\"quantity\":0}").statusCode());
        assertEquals(400, send("POST", "/api/v1/inventory/abc/" + chicagoId + "/sales", employeeToken,
                "{\"quantity\":1}").statusCode());
        assertEquals(400, send("GET", "/api/v1/inventory?lowStock=maybe", employeeToken, null).statusCode());
        assertEquals(400, send("PUT", "/api/v1/inventory/" + productId + "/" + chicagoId + "/threshold",
                managerToken, "{}").statusCode());
        assertEquals(404, send("GET", "/api/v1/nothing-here", employeeToken, null).statusCode());
        assertEquals(405, send("DELETE", sales, employeeToken, null).statusCode());

        var wrongType = HttpRequest.newBuilder(uri(sales)).header("Authorization", "Bearer " + employeeToken)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}")).build();
        assertEquals(415, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());

        String huge = "{\"reference\":\"" + "x".repeat(ApiServer.MAX_BODY_BYTES) + "\",\"quantity\":1}";
        assertEquals(413, send("POST", sales, employeeToken, huge).statusCode());
        assertEquals(30, quantity(chicagoId), "no rejected request may change stock");
    }

    @Test
    void concurrentHttpSalesNeverOversell() throws Exception {
        String sales = "/api/v1/inventory/" + productId + "/" + chicagoId + "/sales";
        int requests = 12;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(requests);
        var statuses = new ArrayList<Integer>();
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return send("POST", sales, employeeToken, "{\"quantity\":4}").statusCode();
                }));
            }
            start.countDown();
            for (var future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long succeeded = statuses.stream().filter(status -> status == 200).count();
        long rejected = statuses.stream().filter(status -> status == 409).count();
        assertEquals(7, succeeded, "30 units allow exactly seven sales of 4");
        assertEquals(requests - 7, rejected);
        assertEquals(2, quantity(chicagoId));
    }

    @Test
    void logoutRevokesTheTokenAndServerErrorsDoNotLeakDetails() throws Exception {
        String token = login("employee.demo", PASSWORD).get("token").asText();
        assertEquals(204, send("DELETE", "/api/v1/sessions/current", token, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/me", token, null).statusCode());

        execute("ALTER TABLE stock_transaction RENAME TO stock_transaction_hidden");
        try {
            var failure = send("GET", "/api/v1/products/" + productId + "/transactions", employeeToken, null);
            assertEquals(500, failure.statusCode());
            assertEquals("Internal server error", message(failure));
            assertFalse(failure.body().contains("stock_transaction"));
        } finally {
            execute("ALTER TABLE stock_transaction_hidden RENAME TO stock_transaction");
        }
        assertTrue(serverLog.stream().noneMatch(line -> line.contains(PASSWORD)), "passwords never reach the log");
        assertTrue(serverLog.stream().noneMatch(line -> line.contains(employeeToken)), "tokens never reach the log");
    }

    private JsonNode login(String username, String password) throws Exception {
        var response = send("POST", "/api/v1/sessions", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(201, response.statusCode(), response.body());
        return body(response);
    }

    private HttpResponse<String> send(String method, String path, String token, String jsonBody) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private JsonNode body(HttpResponse<String> response) throws IOException {
        return json.readTree(response.body());
    }

    private String message(HttpResponse<String> response) throws IOException {
        return body(response).get("error").get("message").asText();
    }

    private int quantity(long warehouseId) throws SQLException {
        return (int) id("SELECT quantity FROM inventory WHERE product_id = " + productId
                + " AND warehouse_id = " + warehouseId);
    }

    private long id(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
