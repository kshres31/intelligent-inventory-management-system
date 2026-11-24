package com.kshres31.inventory.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.NotificationStatus;
import com.kshres31.inventory.security.AuthenticationService;
import com.kshres31.inventory.service.AlertNotificationService;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ReportingService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Endpoint definitions. Each handler authenticates, delegates to a service, and shapes JSON. */
public final class InventoryApi {
    private static final String V1 = "/api/v1";

    private final AuthenticationService auth;
    private final InventoryService inventory;
    private final ReportingService reporting;
    private final AlertNotificationService notifications;
    private final ConnectionFactory connections;
    private final ObjectMapper json = ApiServer.objectMapper();

    public InventoryApi(AuthenticationService auth, InventoryService inventory, ReportingService reporting,
                        AlertNotificationService notifications, ConnectionFactory connections) {
        this.auth = auth;
        this.inventory = inventory;
        this.reporting = reporting;
        this.notifications = notifications;
        this.connections = connections;
    }

    void register(Router router) {
        router.add("GET", V1 + "/health", this::health);

        router.add("POST", V1 + "/sessions", this::login);
        router.add("DELETE", V1 + "/sessions/current", this::logout);
        router.add("GET", V1 + "/me", request -> ApiResponse.ok(userView(user(request))));

        router.add("GET", V1 + "/inventory", request -> ApiResponse.ok(
                inventory.search(user(request), request.query("q", ""), request.queryBoolean("lowStock"))));
        router.add("POST", V1 + "/inventory/{productId}/{warehouseId}/receipts", request -> quantityResult(request,
                inventory.receive(user(request), request.pathLong("productId"), request.pathLong("warehouseId"),
                        request.requiredInt("quantity"), request.optionalString("reference"))));
        router.add("POST", V1 + "/inventory/{productId}/{warehouseId}/sales", request -> quantityResult(request,
                inventory.sell(user(request), request.pathLong("productId"), request.pathLong("warehouseId"),
                        request.requiredInt("quantity"), request.optionalString("reference"))));
        router.add("POST", V1 + "/inventory/{productId}/{warehouseId}/adjustments", request -> quantityResult(request,
                inventory.adjust(user(request), request.pathLong("productId"), request.pathLong("warehouseId"),
                        request.requiredInt("change"), request.requiredString("reason"))));
        router.add("POST", V1 + "/inventory/{productId}/{warehouseId}/counts", request -> {
            long countId = inventory.recordPhysicalCount(user(request), request.pathLong("productId"),
                    request.pathLong("warehouseId"), request.requiredInt("countedQuantity"),
                    request.optionalString("notes"));
            return ApiResponse.created(Map.of("countId", countId));
        });
        router.add("PUT", V1 + "/inventory/{productId}/{warehouseId}/threshold", request -> {
            Integer threshold = request.nullableInt("threshold");
            int effective = inventory.setLowStockThreshold(user(request), request.pathLong("productId"),
                    request.pathLong("warehouseId"), threshold);
            var body = new LinkedHashMap<String, Object>();
            body.put("override", threshold);
            body.put("effectiveThreshold", effective);
            return ApiResponse.ok(body);
        });
        router.add("POST", V1 + "/transfers", request -> {
            UUID transferId = inventory.transfer(user(request), request.requiredLong("productId"),
                    request.requiredLong("fromWarehouseId"), request.requiredLong("toWarehouseId"),
                    request.requiredInt("quantity"), request.optionalString("reference"));
            return ApiResponse.created(Map.of("transferId", transferId));
        });
        router.add("GET", V1 + "/products/{productId}/transactions", request -> ApiResponse.ok(
                inventory.transactionHistory(user(request), request.pathLong("productId"),
                        request.queryInt("page", 1), request.queryInt("pageSize", 20))));

        router.add("GET", V1 + "/alerts", request -> {
            String status = request.query("status", "open").toLowerCase(Locale.ROOT);
            if (!status.equals("open") && !status.equals("all")) {
                throw new ApiException(400, "status must be open or all");
            }
            return ApiResponse.ok(reporting.recentAlerts(user(request), request.queryInt("limit", 50),
                    status.equals("open")));
        });
        router.add("POST", V1 + "/alerts/{alertId}/resolution", request -> {
            if (!reporting.resolveAlert(user(request), request.pathLong("alertId"), request.requiredString("note"))) {
                throw new ApiException(409, "Alert was already resolved or does not exist");
            }
            return ApiResponse.ok(Map.of("resolved", true));
        });
        router.add("GET", V1 + "/notifications", request -> {
            String status = request.query("status", "all").toUpperCase(Locale.ROOT);
            NotificationStatus filter;
            try {
                filter = status.equals("ALL") ? null : NotificationStatus.valueOf(status);
            } catch (IllegalArgumentException error) {
                throw new ApiException(400, "status must be pending, sent, dead or all");
            }
            return ApiResponse.ok(notifications.recent(user(request), request.queryInt("limit", 20), filter)
                    .stream().map(this::notificationView).toList());
        });
    }

    private ApiResponse health(ApiRequest request) {
        try (var connection = connections.open();
             var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return ApiResponse.ok(Map.of("status", "ok", "database", "up"));
        } catch (Exception error) {
            return new ApiResponse(503, Map.of("status", "degraded", "database", "down"));
        }
    }

    private ApiResponse login(ApiRequest request) {
        String username = request.requiredString("username");
        String password = request.requiredString("password");
        var session = auth.login(username, password.toCharArray());
        var body = new LinkedHashMap<String, Object>();
        body.put("token", session.token());
        body.put("tokenType", "Bearer");
        body.put("expiresAt", session.expiresAt());
        body.put("user", userView(session.user()));
        return ApiResponse.created(body);
    }

    private ApiResponse logout(ApiRequest request) {
        user(request);
        auth.logout(request.bearerToken());
        return ApiResponse.noContent();
    }

    private AppUser user(ApiRequest request) {
        return auth.authenticate(request.bearerToken());
    }

    private static ApiResponse quantityResult(ApiRequest request, int quantity) {
        var body = new LinkedHashMap<String, Object>();
        body.put("productId", request.pathLong("productId"));
        body.put("warehouseId", request.pathLong("warehouseId"));
        body.put("quantity", quantity);
        return ApiResponse.ok(body);
    }

    private static Map<String, Object> userView(AppUser user) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", user.id());
        view.put("username", user.username());
        view.put("displayName", user.displayName());
        view.put("role", user.role());
        return view;
    }

    /** Leaves out the dispatcher's lease token, which is internal state. */
    private Map<String, Object> notificationView(AlertNotification notification) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", notification.id());
        view.put("alertId", notification.alertId());
        view.put("event", notification.eventType());
        view.put("status", notification.status());
        view.put("attempts", notification.attempts());
        view.put("nextAttemptAt", notification.nextAttemptAt());
        view.put("lastError", notification.lastError());
        view.put("createdAt", notification.createdAt());
        view.put("sentAt", notification.sentAt());
        view.put("payload", parse(notification.payloadJson()));
        return view;
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException error) {
            return json.getNodeFactory().textNode(payload);
        }
    }
}
