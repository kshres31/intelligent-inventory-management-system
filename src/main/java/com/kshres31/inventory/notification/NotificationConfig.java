package com.kshres31.inventory.notification;

import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Reads notification settings from the environment.
 * INVENTORY_ALERT_WEBHOOK_URL selects the webhook channel; without it notifications go
 * to the console. INVENTORY_ALERT_WEBHOOK_SECRET enables request signing.
 */
public record NotificationConfig(URI webhookUrl, String webhookSecret, Duration timeout) {
    public static NotificationConfig fromEnvironment(Map<String, String> environment) {
        String url = environment.get("INVENTORY_ALERT_WEBHOOK_URL");
        String timeoutSeconds = environment.getOrDefault("INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS", "10");
        Duration timeout;
        try {
            timeout = Duration.ofSeconds(Long.parseLong(timeoutSeconds.strip()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS must be a whole number");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS must be positive");
        }
        return new NotificationConfig(
                url == null || url.isBlank() ? null : URI.create(url.strip()),
                environment.get("INVENTORY_ALERT_WEBHOOK_SECRET"),
                timeout
        );
    }

    public NotificationChannel createChannel(PrintStream console) {
        if (webhookUrl == null) {
            return new ConsoleNotificationChannel(console);
        }
        return new WebhookNotificationChannel(webhookUrl, webhookSecret, timeout);
    }
}
