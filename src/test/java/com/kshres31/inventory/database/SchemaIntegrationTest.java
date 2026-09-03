package com.kshres31.inventory.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaIntegrationTest {
    private DatabaseTestSupport database;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsDomainTablesFunctionsTriggersAndSeedData() throws SQLException {
        assertEquals(3, queryInt("SELECT count(*) FROM app_user"));
        assertEquals(3, queryInt("SELECT count(*) FROM product"));
        assertEquals(4, queryInt("SELECT count(*) FROM inventory"));
        assertEquals(5, queryInt("""
                SELECT count(*)
                FROM pg_proc
                WHERE proname IN (
                    'receive_inventory', 'process_stock_sale', 'transfer_inventory',
                    'perform_inventory_adjustment', 'reconcile_inventory_count'
                )
                """));
        assertEquals(3, queryInt("""
                SELECT count(*)
                FROM pg_trigger
                WHERE NOT tgisinternal
                  AND tgname IN ('trg_inventory_audit', 'trg_low_stock_alert', 'trg_discrepancy_alert')
                """));
        assertEquals(1, queryInt("""
                SELECT count(*)
                FROM inventory_alert
                WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL
                """));
    }

    @Test
    void databaseConstraintRejectsNegativeInventory() {
        assertThrows(SQLException.class, () -> {
            try (var connection = database.dataSource().getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE inventory
                        SET quantity = -1
                        WHERE product_id = (SELECT id FROM product WHERE sku = 'USB-C-2M')
                          AND warehouse_id = (SELECT id FROM warehouse WHERE code = 'CHI-01')
                        """);
            }
        });
    }

    private int queryInt(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
