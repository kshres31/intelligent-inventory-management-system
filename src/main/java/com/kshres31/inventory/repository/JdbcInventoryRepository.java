package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.exception.InventoryOperationException;
import com.kshres31.inventory.model.InventoryItem;
import com.kshres31.inventory.model.StockTransaction;
import com.kshres31.inventory.model.StockTransactionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcInventoryRepository implements InventoryRepository {
    private final ConnectionFactory connections;

    public JdbcInventoryRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public int receive(long productId, long warehouseId, int quantity, long actorUserId, String reference) {
        return quantityFunction(
                "SELECT receive_inventory(?, ?, ?, ?, ?)",
                productId, warehouseId, quantity, actorUserId, reference, "receive inventory"
        );
    }

    @Override
    public int sell(long productId, long warehouseId, int quantity, long actorUserId, String reference) {
        return quantityFunction(
                "SELECT process_stock_sale(?, ?, ?, ?, ?)",
                productId, warehouseId, quantity, actorUserId, reference, "sell inventory"
        );
    }

    @Override
    public UUID transfer(long productId, long sourceWarehouseId, long destinationWarehouseId,
                         int quantity, long actorUserId, String reference) {
        return inTransaction("transfer inventory", connection -> {
            try (var statement = connection.prepareStatement("SELECT transfer_inventory(?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, productId);
                statement.setLong(2, sourceWarehouseId);
                statement.setLong(3, destinationWarehouseId);
                statement.setInt(4, quantity);
                statement.setLong(5, actorUserId);
                statement.setString(6, reference);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getObject(1, UUID.class);
                }
            }
        });
    }

    @Override
    public int adjust(long productId, long warehouseId, int quantityChange, long actorUserId, String reason) {
        return quantityFunction(
                "SELECT perform_inventory_adjustment(?, ?, ?, ?, ?)",
                productId, warehouseId, quantityChange, actorUserId, reason, "adjust inventory"
        );
    }

    @Override
    public long recordPhysicalCount(long productId, long warehouseId, int countedQuantity,
                                    long actorUserId, String notes) {
        return inTransaction("record physical count", connection -> {
            try (var statement = connection.prepareStatement("SELECT reconcile_inventory_count(?, ?, ?, ?, ?)")) {
                statement.setLong(1, productId);
                statement.setLong(2, warehouseId);
                statement.setInt(3, countedQuantity);
                statement.setLong(4, actorUserId);
                statement.setString(5, notes);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    @Override
    public int currentQuantity(long productId, long warehouseId) {
        String sql = "SELECT quantity FROM inventory WHERE product_id = ? AND warehouse_id = ?";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            statement.setLong(2, warehouseId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Inventory row does not exist");
                }
                return result.getInt(1);
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load inventory quantity", error);
        }
    }

    @Override
    public List<InventoryItem> search(String query, boolean lowStockOnly) {
        String sql = """
                SELECT p.id AS product_id, p.sku, p.name AS product_name,
                       w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,
                       i.quantity, p.reorder_threshold, i.updated_at
                FROM inventory i
                JOIN product p ON p.id = i.product_id
                JOIN warehouse w ON w.id = i.warehouse_id
                WHERE p.active AND w.active
                  AND (? = '' OR p.sku ILIKE '%%' || ? || '%%' OR p.name ILIKE '%%' || ? || '%%')
                  AND (NOT ? OR i.quantity < p.reorder_threshold)
                ORDER BY p.name, w.code
                """;
        String searchTerm = query == null ? "" : query.strip();
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, searchTerm);
            statement.setString(2, searchTerm);
            statement.setString(3, searchTerm);
            statement.setBoolean(4, lowStockOnly);
            try (var result = statement.executeQuery()) {
                var inventory = new ArrayList<InventoryItem>();
                while (result.next()) {
                    inventory.add(new InventoryItem(
                            result.getLong("product_id"),
                            result.getString("sku"),
                            result.getString("product_name"),
                            result.getLong("warehouse_id"),
                            result.getString("warehouse_code"),
                            result.getString("warehouse_name"),
                            result.getInt("quantity"),
                            result.getInt("reorder_threshold"),
                            result.getObject("updated_at", OffsetDateTime.class)
                    ));
                }
                return inventory;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not search inventory", error);
        }
    }

    @Override
    public List<StockTransaction> transactionHistory(long productId, int limit) {
        String sql = """
                SELECT id, product_id, warehouse_id, transaction_type, quantity_change,
                       quantity_before, quantity_after, actor_user_id, transfer_id, reference, created_at
                FROM stock_transaction
                WHERE product_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            statement.setInt(2, limit);
            try (var result = statement.executeQuery()) {
                var transactions = new ArrayList<StockTransaction>();
                while (result.next()) {
                    transactions.add(mapTransaction(result));
                }
                return transactions;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load transaction history", error);
        }
    }

    private int quantityFunction(String sql, long productId, long warehouseId, int quantity,
                                 long actorUserId, String reference, String operation) {
        return inTransaction(operation, connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, productId);
                statement.setLong(2, warehouseId);
                statement.setInt(3, quantity);
                statement.setLong(4, actorUserId);
                statement.setString(5, reference);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private <T> T inTransaction(String operation, SqlWork<T> work) {
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                rollbackAfterFailure(connection, error);
                if (error instanceof SQLException sqlError) {
                    throw translateOperationFailure(operation, sqlError);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw translateOperationFailure(operation, error);
        }
    }

    private void rollbackAfterFailure(Connection connection, Exception originalError) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }

    private RuntimeException translateOperationFailure(String operation, SQLException error) {
        if ("P0001".equals(error.getSQLState()) || "23514".equals(error.getSQLState())) {
            return new InventoryOperationException(error.getMessage(), error);
        }
        return new DataAccessException("Could not " + operation, error);
    }

    private StockTransaction mapTransaction(ResultSet result) throws SQLException {
        return new StockTransaction(
                result.getLong("id"),
                result.getLong("product_id"),
                result.getLong("warehouse_id"),
                StockTransactionType.valueOf(result.getString("transaction_type")),
                result.getInt("quantity_change"),
                result.getInt("quantity_before"),
                result.getInt("quantity_after"),
                result.getLong("actor_user_id"),
                result.getObject("transfer_id", UUID.class),
                result.getString("reference"),
                result.getObject("created_at", OffsetDateTime.class)
        );
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
