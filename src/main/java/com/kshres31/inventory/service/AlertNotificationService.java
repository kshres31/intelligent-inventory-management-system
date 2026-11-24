package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.DispatchSummary;
import com.kshres31.inventory.model.NotificationStatus;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.repository.AlertNotificationRepository;

import java.util.List;

/** Permission-checked entry point for people operating the notification outbox. */
public final class AlertNotificationService {
    private final AlertNotificationRepository notifications;
    private final NotificationDispatcher dispatcher;

    public AlertNotificationService(AlertNotificationRepository notifications, NotificationDispatcher dispatcher) {
        this.notifications = notifications;
        this.dispatcher = dispatcher;
    }

    public List<AlertNotification> recent(AppUser actor, int limit, NotificationStatus statusFilter) {
        require(actor, Permission.VIEW_INVENTORY);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Notification limit must be between 1 and 500");
        }
        return notifications.recent(limit, statusFilter);
    }

    public DispatchSummary dispatch(AppUser actor, int batchSize) {
        require(actor, Permission.CONFIGURE_ALERTS);
        return dispatcher.dispatchOnce(batchSize);
    }

    public boolean requeue(AppUser actor, long notificationId) {
        require(actor, Permission.CONFIGURE_ALERTS);
        return notifications.requeue(notificationId);
    }

    public String channelDescription() {
        return dispatcher.channelDescription();
    }

    private void require(AppUser actor, Permission permission) {
        if (actor == null || !actor.active() || !actor.role().allows(permission)) {
            throw new AuthorizationException("Active user lacks permission: " + permission);
        }
    }
}
