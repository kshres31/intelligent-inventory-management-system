package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.AlertSeverity;
import com.kshres31.inventory.model.AlertType;
import com.kshres31.inventory.model.AuditEntry;
import com.kshres31.inventory.model.DiscrepancyRecord;
import com.kshres31.inventory.model.InventoryAlert;
import com.kshres31.inventory.model.ProductMovementSummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class JdbcReportRepository implements ReportRepository {
    private final ConnectionFactory connections;

    public JdbcReportRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public List<ProductMovementSummary> mostMovedProducts(int limit) {
        String sql = """
                SELECT p.id AS product_id, p.sku, p.name AS product_name,
                       count(st.id) AS operation_count,
                       sum(abs(st.quantity_change)) AS units_moved
                FROM stock_transaction st
                JOIN product p ON p.id = st.product_id
                GROUP BY p.id, p.sku, p.name
                ORDER BY units_moved DESC, operation_count DESC, p.sku
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<ProductMovementSummary>();
                while (result.next()) {
                    rows.add(new ProductMovementSummary(
                            result.getLong("product_id"),
                            result.getString("sku"),
                            result.getString("product_name"),
                            result.getLong("operation_count"),
                            result.getLong("units_moved")
                    ));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load product movement report", error);
        }
    }

    @Override
    public List<DiscrepancyRecord> recentDiscrepancies(int limit) {
        String sql = """
                SELECT ic.id AS count_id, p.id AS product_id, p.sku, p.name AS product_name,
                       w.code AS warehouse_code, ic.expected_quantity, ic.counted_quantity,
                       ic.variance, u.username AS counted_by, ic.counted_at, ic.notes
                FROM inventory_count ic
                JOIN product p ON p.id = ic.product_id
                JOIN warehouse w ON w.id = ic.warehouse_id
                JOIN app_user u ON u.id = ic.counted_by
                WHERE ic.variance <> 0
                ORDER BY ic.counted_at DESC, ic.id DESC
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<DiscrepancyRecord>();
                while (result.next()) {
                    rows.add(new DiscrepancyRecord(
                            result.getLong("count_id"),
                            result.getLong("product_id"),
                            result.getString("sku"),
                            result.getString("product_name"),
                            result.getString("warehouse_code"),
                            result.getInt("expected_quantity"),
                            result.getInt("counted_quantity"),
                            result.getInt("variance"),
                            result.getString("counted_by"),
                            result.getObject("counted_at", OffsetDateTime.class),
                            result.getString("notes")
                    ));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load discrepancy report", error);
        }
    }

    @Override
    public List<InventoryAlert> recentAlerts(int limit, boolean unresolvedOnly) {
        String sql = """
                SELECT a.id, a.alert_type, a.severity, a.product_id, p.sku,
                       a.warehouse_id, w.code AS warehouse_code, a.message,
                       a.expected_quantity, a.actual_quantity, a.created_at,
                       a.resolved_at, a.resolved_by, a.resolution_note
                FROM inventory_alert a
                JOIN product p ON p.id = a.product_id
                JOIN warehouse w ON w.id = a.warehouse_id
                WHERE (NOT ? OR a.resolved_at IS NULL)
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, unresolvedOnly);
            statement.setInt(2, limit);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<InventoryAlert>();
                while (result.next()) {
                    rows.add(mapAlert(result));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load alerts", error);
        }
    }

    @Override
    public List<AuditEntry> recentAuditEntries(int limit) {
        String sql = """
                SELECT a.id, a.entity_type, a.entity_key, a.action,
                       a.old_values::TEXT AS old_values_json,
                       a.new_values::TEXT AS new_values_json,
                       u.username AS actor_username, a.created_at
                FROM audit_log a
                LEFT JOIN app_user u ON u.id = a.actor_user_id
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<AuditEntry>();
                while (result.next()) {
                    rows.add(new AuditEntry(
                            result.getLong("id"),
                            result.getString("entity_type"),
                            result.getString("entity_key"),
                            result.getString("action"),
                            result.getString("old_values_json"),
                            result.getString("new_values_json"),
                            result.getString("actor_username"),
                            result.getObject("created_at", OffsetDateTime.class)
                    ));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load audit history", error);
        }
    }

    @Override
    public boolean resolveAlert(long alertId, long actorUserId, String note) {
        String sql = """
                UPDATE inventory_alert
                SET resolved_at = CURRENT_TIMESTAMP, resolved_by = ?, resolution_note = ?
                WHERE id = ? AND resolved_at IS NULL
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, actorUserId);
            statement.setString(2, note);
            statement.setLong(3, alertId);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not resolve alert " + alertId, error);
        }
    }

    private InventoryAlert mapAlert(ResultSet result) throws SQLException {
        return new InventoryAlert(
                result.getLong("id"),
                AlertType.valueOf(result.getString("alert_type")),
                AlertSeverity.valueOf(result.getString("severity")),
                result.getLong("product_id"),
                result.getString("sku"),
                result.getLong("warehouse_id"),
                result.getString("warehouse_code"),
                result.getString("message"),
                nullableInteger(result, "expected_quantity"),
                nullableInteger(result, "actual_quantity"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("resolved_at", OffsetDateTime.class),
                nullableLong(result, "resolved_by"),
                result.getString("resolution_note")
        );
    }

    private Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
