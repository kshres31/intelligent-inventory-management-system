package com.kshres31.inventory.notification;

import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.DispatchSummary;
import com.kshres31.inventory.repository.AlertNotificationRepository;

import java.time.Duration;

/**
 * Drains the alert-notification outbox. Safe to run in several processes at once:
 * the repository's lease guarantees disjoint batches, and a stale lease cannot
 * overwrite a newer dispatcher's result.
 */
public final class NotificationDispatcher {
    private final AlertNotificationRepository notifications;
    private final NotificationChannel channel;
    private final RetryPolicy retryPolicy;
    private final Duration lease;

    public NotificationDispatcher(AlertNotificationRepository notifications, NotificationChannel channel,
                                  RetryPolicy retryPolicy, Duration lease) {
        this.notifications = notifications;
        this.channel = channel;
        this.retryPolicy = retryPolicy;
        this.lease = lease;
    }

    public DispatchSummary dispatchOnce(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Batch size must be between 1 and 500");
        }
        var claimed = notifications.claimDue(batchSize, lease);
        int sent = 0;
        int retried = 0;
        int dead = 0;
        int lost = 0;
        for (AlertNotification notification : claimed) {
            String failure = attempt(notification);
            if (failure == null) {
                if (notifications.markSent(notification.id(), notification.claimToken())) {
                    sent++;
                } else {
                    lost++;
                }
                continue;
            }
            Duration retryDelay = retryPolicy.delayAfterFailedAttempt(notification.attempts());
            if (!notifications.markFailed(notification.id(), notification.claimToken(), failure, retryDelay)) {
                lost++;
            } else if (retryDelay == null) {
                dead++;
            } else {
                retried++;
            }
        }
        return new DispatchSummary(claimed.size(), sent, retried, dead, lost);
    }

    private String attempt(AlertNotification notification) {
        try {
            channel.deliver(notification);
            return null;
        } catch (NotificationDeliveryException error) {
            return error.getMessage();
        } catch (RuntimeException error) {
            // A channel bug must not stall the queue or lose the row; treat it as a failure.
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    public String channelDescription() {
        return channel.describe();
    }
}
