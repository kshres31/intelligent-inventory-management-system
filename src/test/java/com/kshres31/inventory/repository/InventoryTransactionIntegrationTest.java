package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.exception.InventoryOperationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryTransactionIntegrationTest {
    private DatabaseTestSupport database;
    private InventoryRepository inventory;
    private long productId;
    private long chicagoId;
    private long madisonId;
    private long managerId;
    private long employeeId;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        inventory = new JdbcInventoryRepository(() -> database.dataSource().getConnection());
        productId = id("SELECT id FROM product WHERE sku = 'USB-C-2M'");
        chicagoId = id("SELECT id FROM warehouse WHERE code = 'CHI-01'");
        madisonId = id("SELECT id FROM warehouse WHERE code = 'MAD-01'");
        managerId = id("SELECT id FROM app_user WHERE username = 'manager.demo'");
        employeeId = id("SELECT id FROM app_user WHERE username = 'employee.demo'");
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void resetScenario() throws SQLException {
        execute("DELETE FROM stock_transaction");
        execute("DELETE FROM audit_log");
        execute("DELETE FROM inventory_alert");
        execute("UPDATE inventory SET quantity = 10 WHERE product_id = " + productId
                + " AND warehouse_id IN (" + chicagoId + ", " + madisonId + ")");
        execute("DELETE FROM audit_log");
        execute("DELETE FROM inventory_alert");
    }

    @Test
    void concurrentSalesCannotOversell() throws Exception {
        var start = new CountDownLatch(1);
        List<Integer> requested = List.of(7, 6);
        var outcomes = new ArrayList<SaleOutcome>();

        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<SaleOutcome>> futures = requested.stream()
                    .map(quantity -> pool.submit(() -> attemptSale(start, quantity)))
                    .toList();
            start.countDown();
            for (var future : futures) {
                outcomes.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        var successful = outcomes.stream().filter(SaleOutcome::success).toList();
        assertEquals(1, successful.size());
        int finalQuantity = inventory.currentQuantity(productId, chicagoId);
        assertEquals(10 - successful.get(0).quantity(), finalQuantity);
        assertTrue(finalQuantity >= 0);
        assertEquals(1, queryInt("SELECT count(*) FROM stock_transaction WHERE transaction_type = 'SALE'"));
    }

    @Test
    void failedTransferRollsBackBothWarehousesAndHistory() throws SQLException {
        assertThrows(InventoryOperationException.class,
                () -> inventory.transfer(productId, chicagoId, madisonId, 11, managerId, "TOO-LARGE"));

        assertEquals(10, inventory.currentQuantity(productId, chicagoId));
        assertEquals(10, inventory.currentQuantity(productId, madisonId));
        assertEquals(0, queryInt("SELECT count(*) FROM stock_transaction"));
    }

    @Test
    void successfulTransferRecordsCorrelatedMovements() throws SQLException {
        var transferId = inventory.transfer(productId, chicagoId, madisonId, 4, managerId, "MOVE-4");

        assertNotNull(transferId);
        assertEquals(6, inventory.currentQuantity(productId, chicagoId));
        assertEquals(14, inventory.currentQuantity(productId, madisonId));
        assertEquals(2, queryInt("SELECT count(*) FROM stock_transaction WHERE transfer_id = '" + transferId + "'"));
    }

    @Test
    void transactionHistorySupportsStablePages() {
        for (int sequence = 1; sequence <= 5; sequence++) {
            inventory.receive(productId, chicagoId, 1, employeeId, "PAGE-" + sequence);
        }

        var firstPage = inventory.transactionHistory(productId, 2, 0);
        var secondPage = inventory.transactionHistory(productId, 2, 2);

        assertEquals(List.of("PAGE-5", "PAGE-4"),
                firstPage.stream().map(transaction -> transaction.reference()).toList());
        assertEquals(List.of("PAGE-3", "PAGE-2"),
                secondPage.stream().map(transaction -> transaction.reference()).toList());
    }

    @Test
    void lowStockAlertIsDeduplicatedAndResolvedAfterRecovery() throws SQLException {
        inventory.sell(productId, chicagoId, 2, employeeId, "SALE-A");
        inventory.sell(productId, chicagoId, 1, employeeId, "SALE-B");

        assertEquals(1, queryInt("""
                SELECT count(*) FROM inventory_alert
                WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL
                """));
        assertEquals(7, queryInt("""
                SELECT actual_quantity FROM inventory_alert
                WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL
                """));

        inventory.receive(productId, chicagoId, 4, employeeId, "RESTOCK");
        assertEquals(0, queryInt("""
                SELECT count(*) FROM inventory_alert
                WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL
                """));
    }

    @Test
    void physicalCountCreatesDiscrepancyAndAdjustmentCapturesActor() throws SQLException {
        inventory.recordPhysicalCount(productId, chicagoId, 7, managerId, "Shelf count");

        assertEquals(1, queryInt("""
                SELECT count(*) FROM inventory_alert
                WHERE alert_type = 'DISCREPANCY' AND expected_quantity = 10 AND actual_quantity = 7
                """));

        inventory.adjust(productId, chicagoId, -3, managerId, "Reconcile shelf count");
        assertEquals(managerId, id("""
                SELECT actor_user_id FROM audit_log
                WHERE entity_type = 'inventory'
                ORDER BY id DESC LIMIT 1
                """));
        assertEquals(7, inventory.currentQuantity(productId, chicagoId));
    }

    private SaleOutcome attemptSale(CountDownLatch start, int quantity) throws InterruptedException {
        start.await();
        try {
            inventory.sell(productId, chicagoId, quantity, employeeId, "CONCURRENT-" + quantity);
            return new SaleOutcome(quantity, true);
        } catch (InventoryOperationException expected) {
            return new SaleOutcome(quantity, false);
        }
    }

    private long id(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private int queryInt(String sql) throws SQLException {
        return Math.toIntExact(id(sql));
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private record SaleOutcome(int quantity, boolean success) {
    }
}
