package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseOrderRepositoryIntegrationTest {
    private DatabaseTestSupport database;
    private PurchaseOrderRepository orders;
    private long supplierId;
    private long warehouseId;
    private long managerId;
    private long productId;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        orders = new JdbcPurchaseOrderRepository(() -> database.dataSource().getConnection());
        supplierId = id("SELECT id FROM supplier WHERE name = 'Northstar Components'");
        warehouseId = id("SELECT id FROM warehouse WHERE code = 'MAD-01'");
        managerId = id("SELECT id FROM app_user WHERE username = 'manager.demo'");
        productId = id("SELECT id FROM product WHERE sku = 'KB-WL-01'");
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsDraftAddsLinesAndUsesGuardedStatusChange() {
        long orderId = orders.create(
                "PO-TEST-100", supplierId, warehouseId, managerId,
                LocalDate.now().plusDays(10), "Keyboard replenishment"
        );
        orders.addLine(orderId, productId, 12, new BigDecimal("22.50"));

        var created = orders.list(20).stream()
                .filter(order -> order.id() == orderId)
                .findFirst()
                .orElseThrow();
        assertEquals(PurchaseOrderStatus.DRAFT, created.status());
        assertEquals(1, created.lineCount());
        assertEquals(12, created.totalOrderedQuantity());

        assertTrue(orders.changeStatus(orderId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED));
        assertFalse(orders.changeStatus(orderId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CANCELLED));
        assertThrows(IllegalStateException.class,
                () -> orders.addLine(orderId, productId, 1, BigDecimal.ONE));
    }

    private long id(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
