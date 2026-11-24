package com.kshres31.inventory.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One outbox row describing an alert event that must be delivered.
 *
 * @param claimToken identifies the dispatcher lease; only the holder of the current
 *                   token may record the delivery outcome
 * @param payloadJson JSON document built by PostgreSQL when the event was enqueued
 */
public record AlertNotification(
        long id,
        long alertId,
        NotificationEventType eventType,
        String payloadJson,
        NotificationStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        UUID claimToken,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt
) {
}
