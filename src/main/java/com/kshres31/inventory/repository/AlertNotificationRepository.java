package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.NotificationStatus;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface AlertNotificationRepository {
    /**
     * Leases up to {@code limit} due notifications for {@code lease}. Concurrent callers
     * never receive the same row while a lease is valid. Each claim counts as an attempt.
     */
    List<AlertNotification> claimDue(int limit, Duration lease);

    /** Returns false when the lease no longer belongs to {@code claimToken}. */
    boolean markSent(long notificationId, UUID claimToken);

    /**
     * Records a failed attempt. With {@code retryDelay} null the row becomes DEAD;
     * otherwise it becomes due again after the delay. Returns false when the lease is stale.
     */
    boolean markFailed(long notificationId, UUID claimToken, String error, Duration retryDelay);

    /** Moves a DEAD notification back to PENDING with a fresh attempt budget. */
    boolean requeue(long notificationId);

    List<AlertNotification> recent(int limit, NotificationStatus statusFilter);
}
