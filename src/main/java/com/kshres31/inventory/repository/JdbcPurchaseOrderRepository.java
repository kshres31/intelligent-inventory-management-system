package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.PurchaseOrderSummary;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {
    private final ConnectionFactory connections;

    public JdbcPurchaseOrderRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public long create(String orderNumber, long supplierId, long warehouseId, long actorUserId,
                       LocalDate expectedAt, String notes) {
        String sql = """
                INSERT INTO purchase_order (
                    order_number, supplier_id, warehouse_id, ordered_by, expected_at, notes
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderNumber);
            statement.setLong(2, supplierId);
            statement.setLong(3, warehouseId);
            statement.setLong(4, actorUserId);
            statement.setObject(5, expectedAt);
            statement.setString(6, notes);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not create purchase order '" + orderNumber + "'", error);
        }
    }

    @Override
    public long addLine(long purchaseOrderId, long productId, int orderedQuantity, BigDecimal unitCost) {
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                assertDraftStatus(connection, purchaseOrderId);
                long lineId = insertLine(connection, purchaseOrderId, productId, orderedQuantity, unitCost);
                connection.commit();
                return lineId;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not add purchase-order line", error);
        }
    }

    @Override
    public boolean changeStatus(long purchaseOrderId, PurchaseOrderStatus expectedStatus,
                                PurchaseOrderStatus nextStatus) {
        String sql = """
                UPDATE purchase_order
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, nextStatus.name());
            statement.setLong(2, purchaseOrderId);
            statement.setString(3, expectedStatus.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not update purchase-order status", error);
        }
    }

    @Override
    public List<PurchaseOrderSummary> list(int limit) {
        String sql = """
                SELECT po.id, po.order_number, s.name AS supplier_name, w.code AS warehouse_code,
                       po.status, u.username AS ordered_by, po.expected_at, po.created_at,
                       count(pol.id) AS line_count,
                       coalesce(sum(pol.ordered_quantity), 0) AS total_ordered_quantity,
                       coalesce(sum(pol.received_quantity), 0) AS total_received_quantity
                FROM purchase_order po
                JOIN supplier s ON s.id = po.supplier_id
                JOIN warehouse w ON w.id = po.warehouse_id
                JOIN app_user u ON u.id = po.ordered_by
                LEFT JOIN purchase_order_line pol ON pol.purchase_order_id = po.id
                GROUP BY po.id, s.name, w.code, u.username
                ORDER BY po.created_at DESC, po.id DESC
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (var result = statement.executeQuery()) {
                var orders = new ArrayList<PurchaseOrderSummary>();
                while (result.next()) {
                    orders.add(new PurchaseOrderSummary(
                            result.getLong("id"),
                            result.getString("order_number"),
                            result.getString("supplier_name"),
                            result.getString("warehouse_code"),
                            PurchaseOrderStatus.valueOf(result.getString("status")),
                            result.getString("ordered_by"),
                            result.getObject("expected_at", LocalDate.class),
                            result.getLong("line_count"),
                            result.getLong("total_ordered_quantity"),
                            result.getLong("total_received_quantity"),
                            result.getObject("created_at", OffsetDateTime.class)
                    ));
                }
                return orders;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not list purchase orders", error);
        }
    }

    private void assertDraftStatus(Connection connection, long purchaseOrderId) throws SQLException {
        String sql = "SELECT status FROM purchase_order WHERE id = ? FOR UPDATE";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, purchaseOrderId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Purchase order " + purchaseOrderId + " does not exist");
                }
                if (!PurchaseOrderStatus.DRAFT.name().equals(result.getString(1))) {
                    throw new IllegalStateException("Lines can only be changed while an order is DRAFT");
                }
            }
        }
    }

    private long insertLine(Connection connection, long purchaseOrderId, long productId,
                            int orderedQuantity, BigDecimal unitCost) throws SQLException {
        String sql = """
                INSERT INTO purchase_order_line (
                    purchase_order_id, product_id, ordered_quantity, unit_cost
                ) VALUES (?, ?, ?, ?)
                RETURNING id
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, purchaseOrderId);
            statement.setLong(2, productId);
            statement.setInt(3, orderedQuantity);
            statement.setBigDecimal(4, unitCost);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void rollback(Connection connection, Exception originalError) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }
}
