package com.kshres31.inventory.notification;

import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.NotificationEventType;
import com.kshres31.inventory.model.NotificationStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookNotificationChannelTest {
    private static final String PAYLOAD = "{\"event\": \"RAISED\", \"sku\": \"KB-WL-01\"}";

    private HttpServer server;
    private final Map<String, String> received = new ConcurrentHashMap<>();
    private final AtomicInteger responseStatus = new AtomicInteger(204);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks/inventory", exchange -> {
            received.put("method", exchange.getRequestMethod());
            received.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getRequestHeaders().forEach((name, values) ->
                    received.put(name.toLowerCase(), values.get(0)));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsSignedPayloadWithIdempotencyKey() throws Exception {
        var channel = new WebhookNotificationChannel(endpoint(), "shared-secret", Duration.ofSeconds(5));

        channel.deliver(notification(42));

        assertEquals("POST", received.get("method"));
        assertEquals(PAYLOAD, received.get("body"));
        assertEquals("42", received.get("x-inventory-notification-id"));
        assertEquals("RAISED", received.get("x-inventory-event"));
        assertEquals("application/json", received.get("content-type"));
        assertEquals("sha256=" + hmac("shared-secret", PAYLOAD), received.get("x-inventory-signature"));
    }

    @Test
    void omitsSignatureWhenNoSecretIsConfigured() throws Exception {
        new WebhookNotificationChannel(endpoint(), " ", Duration.ofSeconds(5)).deliver(notification(1));

        assertNull(received.get("x-inventory-signature"));
    }

    @Test
    void nonSuccessResponseIsARetryableFailure() {
        responseStatus.set(503);
        var channel = new WebhookNotificationChannel(endpoint(), null, Duration.ofSeconds(5));

        var error = assertThrows(NotificationDeliveryException.class, () -> channel.deliver(notification(7)));
        assertTrue(error.getMessage().contains("503"));
    }

    @Test
    void unreachableEndpointIsARetryableFailure() {
        int port = server.getAddress().getPort();
        server.stop(0);
        var channel = new WebhookNotificationChannel(
                URI.create("http://127.0.0.1:" + port + "/hooks/inventory"), null, Duration.ofSeconds(2));

        assertThrows(NotificationDeliveryException.class, () -> channel.deliver(notification(7)));
    }

    @Test
    void rejectsNonHttpSchemesAndHidesQueryStringsInDescriptions() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookNotificationChannel(
                URI.create("file:///etc/passwd"), null, Duration.ofSeconds(1)));

        var channel = new WebhookNotificationChannel(
                URI.create("https://hooks.example.test/relay?token=secret-value"), null, Duration.ofSeconds(1));
        assertFalse(channel.describe().contains("secret-value"));
    }

    @Test
    void configurationChoosesConsoleUnlessWebhookUrlIsSet() {
        var console = NotificationConfig.fromEnvironment(Map.of()).createChannel(System.out);
        assertInstanceOf(ConsoleNotificationChannel.class, console);

        var webhook = NotificationConfig.fromEnvironment(Map.of(
                "INVENTORY_ALERT_WEBHOOK_URL", endpoint().toString(),
                "INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS", "3"
        )).createChannel(System.out);
        assertInstanceOf(WebhookNotificationChannel.class, webhook);

        assertThrows(IllegalArgumentException.class, () -> NotificationConfig.fromEnvironment(
                Map.of("INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS", "soon")));
        assertThrows(IllegalArgumentException.class, () -> NotificationConfig.fromEnvironment(
                Map.of("INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS", "0")));
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/inventory");
    }

    private static AlertNotification notification(long id) {
        return new AlertNotification(id, 9, NotificationEventType.RAISED, PAYLOAD, NotificationStatus.PENDING,
                1, OffsetDateTime.now(), null, null, OffsetDateTime.now(), null);
    }

    private static String hmac(String secret, String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
