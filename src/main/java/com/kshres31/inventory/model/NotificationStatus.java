package com.kshres31.inventory.model;

public enum NotificationStatus {
    /** Waiting for delivery, including retries scheduled after a failure. */
    PENDING,
    /** Delivered successfully to the configured channel. */
    SENT,
    /** Gave up after the retry policy's maximum attempts; needs manual requeue. */
    DEAD
}
