package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.NotificationStatus;
import com.kshres31.inventory.model.Role;
import com.kshres31.inventory.notification.NotificationChannel;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.notification.RetryPolicy;
import com.kshres31.inventory.repository.AlertNotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertNotificationServiceTest {
    private final CountingRepository repository = new CountingRepository();
    private final AlertNotificationService service = new AlertNotificationService(repository,
            new NotificationDispatcher(repository, new NoopChannel(), RetryPolicy.defaults(), Duration.ofMinutes(1)));

    @Test
    void employeesMayReadButNotOperateTheOutbox() {
        var employee = user(Role.EMPLOYEE, true);

        assertEquals(List.of(), service.recent(employee, 10, NotificationStatus.DEAD));
        assertThrows(AuthorizationException.class, () -> service.dispatch(employee, 10));
        assertThrows(AuthorizationException.class, () -> service.requeue(employee, 1));
        assertEquals(1, repository.calls);
    }

    @Test
    void managersAndInactiveUsers() {
        assertEquals(0, service.dispatch(user(Role.MANAGER, true), 10).claimed());
        assertTrue(service.requeue(user(Role.ADMIN, true), 1));
        assertThrows(AuthorizationException.class, () -> service.recent(user(Role.ADMIN, false), 10, null));
    }

    @Test
    void validatesLimitsBeforeQuerying() {
        var manager = user(Role.MANAGER, true);

        assertThrows(IllegalArgumentException.class, () -> service.recent(manager, 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.dispatch(manager, 501));
        assertEquals(0, repository.calls);
    }

    private AppUser user(Role role, boolean active) {
        return new AppUser(3, "u", "User", null, role, active);
    }

    private static final class CountingRepository implements AlertNotificationRepository {
        private int calls;

        @Override
        public List<AlertNotification> claimDue(int limit, Duration lease) {
            calls++;
            return List.of();
        }

        @Override
        public boolean markSent(long notificationId, UUID claimToken) {
            return true;
        }

        @Override
        public boolean markFailed(long notificationId, UUID claimToken, String error, Duration retryDelay) {
            return true;
        }

        @Override
        public boolean requeue(long notificationId) {
            calls++;
            return true;
        }

        @Override
        public List<AlertNotification> recent(int limit, NotificationStatus statusFilter) {
            calls++;
            return List.of();
        }
    }

    private static final class NoopChannel implements NotificationChannel {
        @Override
        public void deliver(AlertNotification notification) {
        }

        @Override
        public String describe() {
            return "noop";
        }
    }
}
