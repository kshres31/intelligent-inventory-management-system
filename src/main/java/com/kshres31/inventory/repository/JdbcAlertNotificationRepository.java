package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.AlertNotification;
import com.kshres31.inventory.model.NotificationEventType;
import com.kshres31.inventory.model.NotificationStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcAlertNotificationRepository implements AlertNotificationRepository {
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String COLUMNS = """
            id, alert_id, event_type, payload::text AS payload, status, attempts,
            next_attempt_at, claim_token, last_error, created_at, sent_at
            """;

    private final ConnectionFactory connections;

    public JdbcAlertNotificationRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public List<AlertNotification> claimDue(int limit, Duration lease) {
        // SKIP LOCKED lets several dispatchers claim disjoint batches without waiting on
        // each other. An expired lease (crashed dispatcher) makes the row claimable again.
        String sql = """
                WITH due AS (
                    SELECT id
                    FROM alert_notification
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE alert_notification n
                SET claim_token = gen_random_uuid(),
                    locked_until = CURRENT_TIMESTAMP + ?::interval,
                    attempts = n.attempts + 1
                FROM due
                WHERE n.id = due.id
                RETURNING n.id, n.alert_id, n.event_type, n.payload::text AS payload, n.status,
                          n.attempts, n.next_attempt_at, n.claim_token, n.last_error,
                          n.created_at, n.sent_at
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setString(2, lease.toMillis() + " milliseconds");
            try (var result = statement.executeQuery()) {
                var rows = mapAll(result);
                rows.sort((a, b) -> Long.compare(a.id(), b.id()));
                return rows;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not claim alert notifications", error);
        }
    }

    @Override
    public boolean markSent(long notificationId, UUID claimToken) {
        String sql = """
                UPDATE alert_notification
                SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, last_error = NULL,
                    claim_token = NULL, locked_until = NULL
                WHERE id = ? AND claim_token = ? AND status = 'PENDING'
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, notificationId);
            statement.setObject(2, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not record notification delivery", error);
        }
    }

    @Override
    public boolean markFailed(long notificationId, UUID claimToken, String error, Duration retryDelay) {
        String sql = """
                UPDATE alert_notification
                SET status = CASE WHEN ?::interval IS NULL THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = COALESCE(CURRENT_TIMESTAMP + ?::interval, next_attempt_at),
                    last_error = ?,
                    claim_token = NULL, locked_until = NULL
                WHERE id = ? AND claim_token = ? AND status = 'PENDING'
                """;
        String interval = retryDelay == null ? null : retryDelay.toMillis() + " milliseconds";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, interval);
            statement.setString(2, interval);
            statement.setString(3, truncate(error));
            statement.setLong(4, notificationId);
            statement.setObject(5, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException sqlError) {
            throw new DataAccessException("Could not record notification failure", sqlError);
        }
    }

    @Override
    public boolean requeue(long notificationId) {
        String sql = """
                UPDATE alert_notification
                SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'DEAD'
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, notificationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not requeue notification", error);
        }
    }

    @Override
    public List<AlertNotification> recent(int limit, NotificationStatus statusFilter) {
        String sql = "SELECT " + COLUMNS + """
                FROM alert_notification
                WHERE (?::varchar IS NULL OR status = ?::varchar)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        String status = statusFilter == null ? null : statusFilter.name();
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, status);
            statement.setInt(3, limit);
            try (var result = statement.executeQuery()) {
                return mapAll(result);
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load alert notifications", error);
        }
    }

    private static List<AlertNotification> mapAll(ResultSet result) throws SQLException {
        var rows = new ArrayList<AlertNotification>();
        while (result.next()) {
            rows.add(new AlertNotification(
                    result.getLong("id"),
                    result.getLong("alert_id"),
                    NotificationEventType.valueOf(result.getString("event_type")),
                    result.getString("payload"),
                    NotificationStatus.valueOf(result.getString("status")),
                    result.getInt("attempts"),
                    result.getObject("next_attempt_at", OffsetDateTime.class),
                    result.getObject("claim_token", UUID.class),
                    result.getString("last_error"),
                    result.getObject("created_at", OffsetDateTime.class),
                    result.getObject("sent_at", OffsetDateTime.class)
            ));
        }
        return rows;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
