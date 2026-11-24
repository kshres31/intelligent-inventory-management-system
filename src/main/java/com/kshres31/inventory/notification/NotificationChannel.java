package com.kshres31.inventory.notification;

import com.kshres31.inventory.model.AlertNotification;

/**
 * Delivers one alert notification. Implementations must throw
 * {@link NotificationDeliveryException} for any failure that should be retried.
 * Delivery is at-least-once, so receivers should de-duplicate by notification id.
 */
public interface NotificationChannel {
    void deliver(AlertNotification notification) throws NotificationDeliveryException;

    String describe();
}
