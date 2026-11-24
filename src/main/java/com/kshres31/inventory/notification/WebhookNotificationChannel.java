package com.kshres31.inventory.notification;

import com.kshres31.inventory.model.AlertNotification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * POSTs the JSON payload to an HTTP endpoint (for example a Slack/Teams relay or an
 * internal service). Any non-2xx response, timeout, or I/O error is a retryable failure.
 *
 * <p>Headers: {@code X-Inventory-Notification-Id} is a stable idempotency key across
 * retries; {@code X-Inventory-Signature} is {@code sha256=<hex HMAC>} of the body when a
 * shared secret is configured, so the receiver can reject forged requests.
 */
public final class WebhookNotificationChannel implements NotificationChannel {
    private final URI endpoint;
    private final byte[] secret;
    private final HttpClient client;
    private final Duration timeout;

    public WebhookNotificationChannel(URI endpoint, String secret, Duration timeout) {
        this(endpoint, secret, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    WebhookNotificationChannel(URI endpoint, String secret, Duration timeout, HttpClient client) {
        String scheme = endpoint.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Webhook URL must use http or https");
        }
        this.endpoint = endpoint;
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.timeout = timeout;
        this.client = client;
    }

    @Override
    public void deliver(AlertNotification notification) throws NotificationDeliveryException {
        byte[] body = notification.payloadJson().getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Inventory-Notification-Id", Long.toString(notification.id()))
                .header("X-Inventory-Event", notification.eventType().name());
        if (secret != null) {
            request.header("X-Inventory-Signature", "sha256=" + sign(body));
        }
        try {
            HttpResponse<Void> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new NotificationDeliveryException("Webhook responded with HTTP " + status);
            }
        } catch (IOException error) {
            throw new NotificationDeliveryException("Webhook request failed: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException("Webhook delivery interrupted", error);
        }
    }

    String sign(byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
        }
    }

    @Override
    public String describe() {
        // Never print query strings, which commonly carry webhook tokens.
        return "webhook " + endpoint.getScheme() + "://" + endpoint.getAuthority() + endpoint.getPath();
    }
}
