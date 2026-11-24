package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.controller.InventoryCli;
import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.exception.InventoryOperationException;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.service.PurchaseOrderService;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseOrderReceiptIntegrationTest {
    private DatabaseTestSupport database;
    private PurchaseOrderRepository orders;
    private long orderId;
    private long productId;
    private long otherProductId;
    private long warehouseId;
    private long managerId;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        orders = new JdbcPurchaseOrderRepository(this::openReceiptConnection);
        productId = number("SELECT id FROM product WHERE sku = 'USB-C-2M'");
        otherProductId = number("SELECT id FROM product WHERE sku = 'KB-WL-01'");
        warehouseId = number("SELECT id FROM warehouse WHERE code = 'CHI-01'");
        managerId = number("SELECT id FROM app_user WHERE username = 'manager.demo'");
    }

    @AfterAll
    void stopDatabase() throws Exception {
        if (database != null) database.close();
    }

    @BeforeEach
    void prepareOrder() throws Exception {
        execute("UPDATE inventory SET quantity = 1 WHERE product_id = " + productId
                + " AND warehouse_id = " + warehouseId);
        execute("DELETE FROM stock_transaction");
        execute("DELETE FROM audit_log");
        long supplier = number("SELECT id FROM supplier WHERE name = 'Northstar Components'");
        orderId = orders.create(UUID.randomUUID().toString(), supplier, warehouseId, managerId, null, null);
        orders.addLine(orderId, productId, 10, BigDecimal.ONE);
        assertTrue(orders.changeStatus(orderId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED));
    }

    @Test
    void cliReceivesAndReplaysDeliveryWhileRejectingEmployee() throws Exception {
        var output = new ByteArrayOutputStream();
        var users = new JdbcUserRepository(() -> database.dataSource().getConnection());
        var cli = new InventoryCli(users, null, null, null, new PurchaseOrderService(orders), null, null,
                new PrintStream(output));
        String[] command = {"manager.demo", "receive-order-line", Long.toString(orderId),
                Long.toString(productId), "4", UUID.randomUUID().toString()};
        cli.run(command);
        cli.run(command);
        assertEquals(5, stock());
        assertEquals(1, receiptCount());
        assertTrue(output.toString().contains("Line received quantity at this receipt: 4"));
        command[0] = "employee.demo";
        assertThrows(AuthorizationException.class, () -> cli.run(command));
        assertEquals(5, stock());
    }

    @Test
    void partialThenFullReceiptUpdatesStockLineStatusAuditAndAlerts() throws Exception {
        assertEquals(4, receive(4, UUID.randomUUID()));
        assertEquals(5, stock());
        assertEquals("PARTIALLY_RECEIVED", status());
        assertEquals(4, received());
        assertEquals(1, number("SELECT count(*) FROM audit_log WHERE actor_user_id = " + managerId));
        assertEquals(5, number("SELECT actual_quantity FROM inventory_alert WHERE alert_type = 'LOW_STOCK'"
                + " AND resolved_at IS NULL AND product_id = " + productId + " AND warehouse_id = " + warehouseId));

        assertEquals(10, receive(6, UUID.randomUUID()));
        assertEquals(11, stock());
        assertEquals("RECEIVED", status());
        assertEquals(2, number("SELECT count(*) FROM stock_transaction"));
        assertEquals(0, number("SELECT count(*) FROM inventory_alert WHERE alert_type = 'LOW_STOCK'"
                + " AND resolved_at IS NULL AND product_id = " + productId + " AND warehouse_id = " + warehouseId));
    }

    @Test
    void replayReturnsOriginalResultAfterLaterReceiptsWithoutAddingStock() throws Exception {
        UUID first = UUID.randomUUID();
        assertEquals(4, receive(4, first));
        assertEquals(10, receive(6, UUID.randomUUID()));
        assertEquals(4, receive(4, first));
        assertEquals(11, stock());
        assertEquals(2, receiptCount());
        assertEquals(2, number("SELECT count(*) FROM stock_transaction"));
        assertEquals(2, number("SELECT count(*) FROM audit_log"));
    }

    @Test
    void reusedKeyRejectsChangedQuantityProductOrActor() throws Exception {
        UUID key = UUID.randomUUID();
        receive(4, key);
        assertThrows(InventoryOperationException.class, () -> receive(5, key));
        assertThrows(InventoryOperationException.class,
                () -> orders.receiveLine(orderId, otherProductId, 4, managerId, key));
        long admin = number("SELECT id FROM app_user WHERE username = 'admin.demo'");
        assertThrows(InventoryOperationException.class,
                () -> orders.receiveLine(orderId, productId, 4, admin, key));
        assertEquals(5, stock());
        assertEquals(1, receiptCount());
    }

    @Test
    void overReceiptAndInvalidInputHaveNoSideEffects() throws Exception {
        assertThrows(InventoryOperationException.class, () -> receive(11, UUID.randomUUID()));
        assertThrows(InventoryOperationException.class, () -> receive(0, UUID.randomUUID()));
        assertThrows(InventoryOperationException.class, () -> receive(1, null));
        assertThrows(InventoryOperationException.class,
                () -> orders.receiveLine(orderId, otherProductId, 1, managerId, UUID.randomUUID()));
        assertThrows(InventoryOperationException.class,
                () -> orders.receiveLine(Long.MAX_VALUE, productId, 1, managerId, UUID.randomUUID()));
        assertUnchanged();
    }

    @Test
    void draftAndCancelledOrdersRejectNewReceipts() throws Exception {
        execute("UPDATE purchase_order SET status = 'DRAFT' WHERE id = " + orderId);
        assertThrows(InventoryOperationException.class, () -> receive(1, UUID.randomUUID()));
        assertTrue(orders.changeStatus(orderId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CANCELLED));
        assertThrows(InventoryOperationException.class, () -> receive(1, UUID.randomUUID()));
        assertEquals(1, stock());
        assertEquals(0, receiptCount());
    }

    @Test
    void allLinesMustBeReceivedBeforeOrderCompletes() throws Exception {
        execute("UPDATE purchase_order SET status = 'DRAFT' WHERE id = " + orderId);
        orders.addLine(orderId, otherProductId, 2, BigDecimal.ONE);
        assertTrue(orders.changeStatus(orderId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED));
        assertEquals(10, receive(10, UUID.randomUUID()));
        assertEquals("PARTIALLY_RECEIVED", status());
        assertEquals(2, orders.receiveLine(orderId, otherProductId, 2, managerId, UUID.randomUUID()));
        assertEquals("RECEIVED", status());
        assertThrows(InventoryOperationException.class, () -> receive(1, UUID.randomUUID()));
        assertEquals(2, receiptCount());
    }

    @Test
    void cancellationPreservesCommittedReceiptAndAllowsItsReplayOnly() throws Exception {
        UUID request = UUID.randomUUID();
        receive(4, request);
        assertTrue(orders.changeStatus(orderId, PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.CANCELLED));
        assertEquals(4, receive(4, request));
        assertThrows(InventoryOperationException.class, () -> receive(1, UUID.randomUUID()));
        assertEquals(5, stock());
        assertEquals(1, receiptCount());
    }

    @Test
    void manualStatusChangeCannotPretendGoodsWereReceived() {
        assertThrows(IllegalArgumentException.class,
                () -> orders.changeStatus(orderId, PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.RECEIVED));
        assertThrows(IllegalArgumentException.class,
                () -> orders.changeStatus(orderId, PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.PARTIALLY_RECEIVED));
    }

    @Test
    void failureAfterStockAndOrderUpdatesRollsBackEverythingAndAllowsRetry() throws Exception {
        UUID request = UUID.randomUUID();
        String beforeAlert = text("SELECT row_to_json(a)::text FROM inventory_alert a WHERE alert_type = 'LOW_STOCK'"
                + " AND resolved_at IS NULL AND product_id = " + productId + " AND warehouse_id = " + warehouseId);
        execute("""
                CREATE FUNCTION fail_receipt_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Injected failure after stock and order writes'; END; $$;
                CREATE TRIGGER fail_receipt_test BEFORE INSERT ON purchase_order_receipt
                FOR EACH ROW EXECUTE FUNCTION fail_receipt_test();
                """);
        try {
            assertThrows(InventoryOperationException.class, () -> receive(10, request));
            assertUnchanged();
            assertEquals(beforeAlert, text("SELECT row_to_json(a)::text FROM inventory_alert a WHERE alert_type = 'LOW_STOCK'"
                    + " AND resolved_at IS NULL AND product_id = " + productId + " AND warehouse_id = " + warehouseId));
        } finally {
            execute("DROP TRIGGER fail_receipt_test ON purchase_order_receipt; DROP FUNCTION fail_receipt_test()");
        }
        assertEquals(10, receive(10, request));
        assertEquals(11, stock());
    }

    @Test
    void twoConcurrentDeliveriesCannotExceedOutstandingQuantity() throws Exception {
        List<Integer> results = contend(
                () -> attempt(7, UUID.randomUUID()), () -> attempt(6, UUID.randomUUID()));
        assertEquals(1, results.stream().filter(value -> value > 0).count());
        int winner = results.stream().filter(value -> value > 0).findFirst().orElseThrow();
        assertEquals(winner, received());
        assertEquals(1 + winner, stock());
        assertEquals(1, receiptCount());
        assertEquals(1, number("SELECT count(*) FROM stock_transaction"));
    }

    @Test
    void concurrentIdenticalRequestsCommitOneReceipt() throws Exception {
        UUID request = UUID.randomUUID();
        assertEquals(List.of(10, 10), contend(() -> receive(10, request), () -> receive(10, request)));
        assertEquals(11, stock());
        assertEquals(1, receiptCount());
        assertEquals(1, number("SELECT count(*) FROM stock_transaction"));
        assertEquals(1, number("SELECT count(*) FROM audit_log"));
    }

    private int attempt(int quantity, UUID key) {
        try {
            return receive(quantity, key);
        } catch (InventoryOperationException error) {
            assertTrue(error.getMessage().contains("outstanding ordered quantity"));
            return 0;
        }
    }

    // Hold the parent row until both real connections are observed waiting on locks.
    // This avoids claiming contention merely because two tasks shared a start latch.
    private List<Integer> contend(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (var blocker = database.dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM purchase_order WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, orderId);
                statement.executeQuery().close();
            }
            var a = pool.submit(first);
            var b = pool.submit(second);
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (number("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'receipt-contention-test'"
                        + " AND cardinality(pg_blocking_pids(pid)) > 0") < 2) {
                    if (System.nanoTime() > deadline) fail("Both receipt connections must wait for the order lock");
                    Thread.sleep(10);
                }
            } finally {
                blocker.rollback();
            }
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private Connection openReceiptConnection() throws SQLException {
        var connection = database.dataSource().getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("SET application_name = 'receipt-contention-test'; SET statement_timeout = '10s'");
        }
        return connection;
    }

    private int receive(int quantity, UUID request) {
        return orders.receiveLine(orderId, productId, quantity, managerId, request);
    }

    private long stock() throws SQLException {
        return number("SELECT quantity FROM inventory WHERE product_id = " + productId + " AND warehouse_id = " + warehouseId);
    }

    private long received() throws SQLException {
        return number("SELECT received_quantity FROM purchase_order_line WHERE purchase_order_id = " + orderId);
    }

    private String status() throws SQLException {
        return text("SELECT status FROM purchase_order WHERE id = " + orderId);
    }

    private long receiptCount() throws SQLException {
        return number("SELECT count(*) FROM purchase_order_receipt WHERE purchase_order_id = " + orderId);
    }

    private void assertUnchanged() throws SQLException {
        assertEquals(1, stock());
        assertEquals(0, received());
        assertEquals("SUBMITTED", status());
        assertEquals(0, receiptCount());
        assertEquals(0, number("SELECT count(*) FROM stock_transaction"));
        assertEquals(0, number("SELECT count(*) FROM audit_log"));
    }

    private long number(String sql) throws SQLException { return Long.parseLong(text(sql)); }

    private String text(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
