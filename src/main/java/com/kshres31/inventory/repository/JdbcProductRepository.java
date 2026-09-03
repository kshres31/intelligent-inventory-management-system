package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.exception.DuplicateSkuException;
import com.kshres31.inventory.model.Product;
import com.kshres31.inventory.model.ProductDraft;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcProductRepository implements ProductRepository {
    private static final String PRODUCT_COLUMNS = """
            id, sku, name, description, category_id, preferred_supplier_id,
            reorder_threshold, discrepancy_threshold, unit_price, active, created_at, updated_at
            """;

    private final ConnectionFactory connections;

    public JdbcProductRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public Product create(ProductDraft draft) {
        String sql = """
                INSERT INTO product (
                    sku, name, description, category_id, preferred_supplier_id,
                    reorder_threshold, discrepancy_threshold, unit_price
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING %s
                """.formatted(PRODUCT_COLUMNS);
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            bindDraft(statement, draft);
            try (var result = statement.executeQuery()) {
                result.next();
                return mapProduct(result);
            }
        } catch (SQLException error) {
            throw translateWriteFailure("create", draft.sku(), error);
        }
    }

    @Override
    public Product update(long productId, ProductDraft draft) {
        String sql = """
                UPDATE product SET
                    sku = ?, name = ?, description = ?, category_id = ?, preferred_supplier_id = ?,
                    reorder_threshold = ?, discrepancy_threshold = ?, unit_price = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                RETURNING %s
                """.formatted(PRODUCT_COLUMNS);
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            bindDraft(statement, draft);
            statement.setLong(9, productId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Product " + productId + " does not exist");
                }
                return mapProduct(result);
            }
        } catch (SQLException error) {
            throw translateWriteFailure("update", draft.sku(), error);
        }
    }

    @Override
    public boolean deactivate(long productId) {
        String sql = "UPDATE product SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND active";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not deactivate product " + productId, error);
        }
    }

    @Override
    public Optional<Product> findById(long productId) {
        return findOne("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE id = ?", statement ->
                statement.setLong(1, productId));
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return findOne("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE sku = ?", statement ->
                statement.setString(1, sku));
    }

    @Override
    public List<Product> search(String query, boolean includeInactive) {
        String sql = """
                SELECT %s FROM product
                WHERE (? OR active)
                  AND (? = '' OR sku ILIKE '%%' || ? || '%%' OR name ILIKE '%%' || ? || '%%')
                ORDER BY name, sku
                """.formatted(PRODUCT_COLUMNS);
        String searchTerm = query == null ? "" : query.strip();
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, includeInactive);
            statement.setString(2, searchTerm);
            statement.setString(3, searchTerm);
            statement.setString(4, searchTerm);
            try (var result = statement.executeQuery()) {
                var products = new ArrayList<Product>();
                while (result.next()) {
                    products.add(mapProduct(result));
                }
                return products;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not search products", error);
        }
    }

    private Optional<Product> findOne(String sql, StatementBinder binder) {
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapProduct(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load product", error);
        }
    }

    private void bindDraft(PreparedStatement statement, ProductDraft draft) throws SQLException {
        statement.setString(1, draft.sku());
        statement.setString(2, draft.name());
        statement.setString(3, draft.description());
        setNullableLong(statement, 4, draft.categoryId());
        setNullableLong(statement, 5, draft.preferredSupplierId());
        statement.setInt(6, draft.reorderThreshold());
        statement.setInt(7, draft.discrepancyThreshold());
        statement.setBigDecimal(8, draft.unitPrice());
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private Product mapProduct(ResultSet result) throws SQLException {
        return new Product(
                result.getLong("id"),
                result.getString("sku"),
                result.getString("name"),
                result.getString("description"),
                nullableLong(result, "category_id"),
                nullableLong(result, "preferred_supplier_id"),
                result.getInt("reorder_threshold"),
                result.getInt("discrepancy_threshold"),
                result.getBigDecimal("unit_price"),
                result.getBoolean("active"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private RuntimeException translateWriteFailure(String operation, String sku, SQLException error) {
        if ("23505".equals(error.getSQLState())) {
            return new DuplicateSkuException(sku, error);
        }
        return new DataAccessException("Could not " + operation + " product '" + sku + "'", error);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
