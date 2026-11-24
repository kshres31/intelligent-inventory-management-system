package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.exception.InventoryOperationException;
import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.NotificationEventType;
import com.kshres31.inventory.model.NotificationStatus;
import com.kshres31.inventory.notification.NotificationChannel;
import com.kshres31.inventory.notification.NotificationDeliveryException;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.notification.RetryPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertNotificationIntegrationTest {
    private DatabaseTestSupport database;
    private JdbcInventoryRepository inventory;
    private JdbcAlertNotificationRepository notifications;
    private long productId;
    private long chicagoId;
    private long managerId;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        inventory = new JdbcInventoryRepository(() -> database.dataSource().getConnection());
        notifications = new JdbcAlertNotificationRepository(() -> database.dataSource().getConnection());
        productId = id("SELECT id FROM product WHERE sku = 'KB-WL-01'");
        chicagoId = id("SELECT id FROM warehouse WHERE code = 'CHI-01'");
        managerId = id("SELECT id FROM app_user WHERE username = 'manager.demo'");
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void resetScenario() throws SQLException {
        execute("UPDATE product SET reorder_threshold = 5 WHERE id = " + productId);
        execute("UPDATE inventory SET quantity = 20, low_stock_threshold = NULL WHERE product_id = "
                + productId + " AND warehouse_id = " + chicagoId);
        execute("DELETE FROM inventory_alert");
        execute("DELETE FROM audit_log");
    }

    @Test
    void migrationBackfillsNotificationsForAlertsThatWereAlreadyOpen() throws Exception {
        try (var fresh = DatabaseTestSupport.start();
             var connection = fresh.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT count(*) FILTER (WHERE a.resolved_at IS NULL),
                            count(n.id) FILTER (WHERE n.event_type = 'RAISED' AND n.status = 'PENDING')
                     FROM inventory_alert a
                     LEFT JOIN alert_notification n ON n.alert_id = a.id
                     """)) {
            result.next();
            assertTrue(result.getLong(1) > 0, "seed data should leave an open low-stock alert");
            assertEquals(result.getLong(1), result.getLong(2));
        }
    }

    @Test
    void alertLifecycleProducesRaisedEscalatedAndResolvedEventsWithoutDuplicates() throws SQLException {
        inventory.sell(productId, chicagoId, 16, managerId, "drop below threshold");
        inventory.sell(productId, chicagoId, 2, managerId, "still low at the same severity");
        inventory.sell(productId, chicagoId, 2, managerId, "out of stock");
        inventory.receive(productId, chicagoId, 12, managerId, "replenished");

        assertEquals(List.of(NotificationEventType.RAISED, NotificationEventType.ESCALATED,
                        NotificationEventType.RESOLVED),
                eventTypesOldestFirst());
        assertEquals(1L, count("SELECT count(DISTINCT alert_id) FROM alert_notification"));
        var newest = notifications.recent(1, null).get(0);
        assertTrue(newest.payloadJson().contains("\"sku\": \"KB-WL-01\""), newest.payloadJson());
        assertTrue(newest.payloadJson().contains("\"event\": \"RESOLVED\""), newest.payloadJson());
    }

    @Test
    void rolledBackStockChangeLeavesNoNotificationBehind() throws SQLException {
        try (var connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT process_stock_sale(?, ?, 18, ?, 'rolled back')")) {
                statement.setLong(1, productId);
                statement.setLong(2, chicagoId);
                statement.setLong(3, managerId);
                statement.executeQuery().close();
            }
            assertEquals(1L, countOn(connection, "SELECT count(*) FROM alert_notification"));
            connection.rollback();
        }

        assertEquals(0L, count("SELECT count(*) FROM alert_notification"));
        assertEquals(0L, count("SELECT count(*) FROM inventory_alert"));
    }

    @Test
    void locationOverrideIsAuditedAndReevaluatesAlertsImmediately() throws SQLException {
        assertEquals(25, inventory.setLowStockThreshold(productId, chicagoId, 25, managerId));
        assertEquals(1L, count("SELECT count(*) FROM inventory_alert WHERE resolved_at IS NULL"));
        assertEquals(1L, count("SELECT count(*) FROM inventory_alert WHERE expected_quantity = 25"));
        assertTrue(inventory.search("KB-WL", true).stream().anyMatch(item -> item.reorderThreshold() == 25));

        assertEquals(5, inventory.setLowStockThreshold(productId, chicagoId, null, managerId));
        assertEquals(0L, count("SELECT count(*) FROM inventory_alert WHERE resolved_at IS NULL"));
        assertEquals(List.of(NotificationEventType.RAISED, NotificationEventType.RESOLVED), eventTypesOldestFirst());
        assertEquals(2L, count("SELECT count(*) FROM audit_log WHERE entity_type = 'inventory_threshold' "
                + "AND actor_user_id = " + managerId));
    }

    @Test
    void invalidThresholdChangesAreRejectedAndChangeNothing() throws SQLException {
        assertThrows(InventoryOperationException.class,
                () -> inventory.setLowStockThreshold(productId, chicagoId, -1, managerId));
        assertThrows(InventoryOperationException.class,
                () -> inventory.setLowStockThreshold(productId, 999_999, 3, managerId));
        assertEquals(0L, count("SELECT count(*) FROM audit_log"));
        assertEquals(0L, count("SELECT count(*) FROM inventory WHERE low_stock_threshold IS NOT NULL"));
    }

    @Test
    void changingProductDefaultReevaluatesLocationsWithoutOverride() throws SQLException {
        execute("UPDATE product SET reorder_threshold = 30 WHERE id = " + productId);

        assertEquals(1L, count("SELECT count(*) FROM inventory_alert WHERE resolved_at IS NULL "
                + "AND product_id = " + productId + " AND warehouse_id = " + chicagoId));
        assertEquals(List.of(NotificationEventType.RAISED), eventTypesOldestFirst());
    }

    @Test
    void dispatcherDeliversPendingNotificationsOnce() throws SQLException {
        inventory.sell(productId, chicagoId, 16, managerId, "low");
        var channel = new RecordingChannel(0);
        var dispatcher = new NotificationDispatcher(notifications, channel, fastRetries(3), Duration.ofMinutes(1));

        var first = dispatcher.dispatchOnce(10);
        var second = dispatcher.dispatchOnce(10);

        assertEquals(1, first.claimed());
        assertEquals(1, first.sent());
        assertEquals(0, second.claimed());
        assertEquals(1, channel.delivered.size());
        assertEquals(1L, count("SELECT count(*) FROM alert_notification WHERE status = 'SENT' "
                + "AND sent_at IS NOT NULL AND claim_token IS NULL AND attempts = 1"));
    }

    @Test
    void failuresBackOffThenDeadLetterAndCanBeRequeued() throws SQLException {
        inventory.sell(productId, chicagoId, 16, managerId, "low");
        var failing = new RecordingChannel(Integer.MAX_VALUE);
        var dispatcher = new NotificationDispatcher(notifications, failing,
                new RetryPolicy(2, Duration.ofMinutes(5), Duration.ofMinutes(10)), Duration.ofMinutes(1));

        var first = dispatcher.dispatchOnce(10);
        assertEquals(1, first.retried());
        assertEquals(0, dispatcher.dispatchOnce(10).claimed(), "retry must wait for its backoff");
        assertEquals(1L, count("SELECT count(*) FROM alert_notification WHERE status = 'PENDING' "
                + "AND next_attempt_at > CURRENT_TIMESTAMP + interval '4 minutes' "
                + "AND last_error = 'simulated outage'"));

        execute("UPDATE alert_notification SET next_attempt_at = CURRENT_TIMESTAMP");
        var second = dispatcher.dispatchOnce(10);
        assertEquals(1, second.dead());
        var dead = notifications.recent(10, NotificationStatus.DEAD);
        assertEquals(1, dead.size());
        assertEquals(2, dead.get(0).attempts());

        assertTrue(notifications.requeue(dead.get(0).id()));
        assertFalse(notifications.requeue(dead.get(0).id()), "only DEAD rows can be requeued");
        var healthy = new NotificationDispatcher(notifications, new RecordingChannel(0), fastRetries(2),
                Duration.ofMinutes(1));
        assertEquals(1, healthy.dispatchOnce(10).sent());
    }

    @Test
    void concurrentDispatchersDeliverEveryNotificationExactlyOnce() throws Exception {
        inventory.sell(productId, chicagoId, 16, managerId, "low");
        long alertId = count("SELECT id FROM inventory_alert");
        execute("INSERT INTO alert_notification (alert_id, event_type, payload) "
                + "SELECT " + alertId + ", 'RAISED', jsonb_build_object('n', g) FROM generate_series(1, 79) g");

        var channel = new RecordingChannel(0);
        int workers = 4;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        var sentByWorkers = new AtomicInteger();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                var dispatcher = new NotificationDispatcher(notifications, channel, fastRetries(3),
                        Duration.ofMinutes(1));
                futures.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        var summary = dispatcher.dispatchOnce(5);
                        if (summary.claimed() == 0) {
                            return null;
                        }
                        sentByWorkers.addAndGet(summary.sent());
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> ids = new ArrayList<>(channel.delivered);
        assertEquals(80, ids.size());
        assertEquals(80, new HashSet<>(ids).size(), "no notification may be delivered twice");
        assertEquals(80, sentByWorkers.get());
        assertEquals(80L, count("SELECT count(*) FROM alert_notification WHERE status = 'SENT'"));
    }

    @Test
    void staleLeaseHolderCannotOverwriteNewerClaim() throws Exception {
        inventory.sell(productId, chicagoId, 16, managerId, "low");
        AlertNotification stale = notifications.claimDue(1, Duration.ofMillis(1)).get(0);
        Thread.sleep(20);
        AlertNotification current = notifications.claimDue(1, Duration.ofMinutes(1)).get(0);

        assertEquals(stale.id(), current.id());
        assertEquals(2, current.attempts());
        assertFalse(notifications.markSent(stale.id(), stale.claimToken()));
        assertFalse(notifications.markFailed(stale.id(), stale.claimToken(), "late failure", Duration.ofSeconds(1)));
        assertTrue(notifications.markSent(current.id(), current.claimToken()));
        assertNotNull(notifications.recent(1, NotificationStatus.SENT).get(0).sentAt());
    }

    private RetryPolicy fastRetries(int attempts) {
        return new RetryPolicy(attempts, Duration.ofMillis(1), Duration.ofMillis(1));
    }

    private List<NotificationEventType> eventTypesOldestFirst() {
        var rows = new ArrayList<>(notifications.recent(100, null));
        Collections.reverse(rows);
        return rows.stream().map(AlertNotification::eventType).toList();
    }

    private long id(String sql) throws SQLException {
        return count(sql);
    }

    private long count(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection()) {
            return countOn(connection, sql);
        }
    }

    private long countOn(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static final class RecordingChannel implements NotificationChannel {
        private final ConcurrentLinkedQueue<Long> delivered = new ConcurrentLinkedQueue<>();
        private final AtomicInteger failuresRemaining;

        private RecordingChannel(int failures) {
            this.failuresRemaining = new AtomicInteger(failures);
        }

        @Override
        public void deliver(AlertNotification notification) throws NotificationDeliveryException {
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new NotificationDeliveryException("simulated outage");
            }
            delivered.add(notification.id());
        }

        @Override
        public String describe() {
            return "recording";
        }
    }
}
