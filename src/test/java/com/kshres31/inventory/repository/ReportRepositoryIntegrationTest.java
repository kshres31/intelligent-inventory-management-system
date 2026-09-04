package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.model.AlertType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportRepositoryIntegrationTest {
    private DatabaseTestSupport database;
    private InventoryRepository inventory;
    private ReportRepository reports;
    private long productId;
    private long warehouseId;
    private long managerId;
    private long employeeId;

    @BeforeAll
    void startDatabaseAndCreateReportData() throws Exception {
        database = DatabaseTestSupport.start();
        var connectionFactory = (com.kshres31.inventory.config.ConnectionFactory)
                () -> database.dataSource().getConnection();
        inventory = new JdbcInventoryRepository(connectionFactory);
        reports = new JdbcReportRepository(connectionFactory);
        productId = id("SELECT id FROM product WHERE sku = 'USB-C-2M'");
        warehouseId = id("SELECT id FROM warehouse WHERE code = 'CHI-01'");
        managerId = id("SELECT id FROM app_user WHERE username = 'manager.demo'");
        employeeId = id("SELECT id FROM app_user WHERE username = 'employee.demo'");

        inventory.sell(productId, warehouseId, 16, employeeId, "REPORT-SALE");
        inventory.recordPhysicalCount(productId, warehouseId, 5, managerId, "Cycle count");
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void reportsMovementsDiscrepanciesAlertsAndAuditEntries() {
        var movement = reports.mostMovedProducts(5).get(0);
        assertEquals("USB-C-2M", movement.sku());
        assertEquals(16, movement.unitsMoved());

        var discrepancy = reports.recentDiscrepancies(5).get(0);
        assertEquals(-4, discrepancy.variance());
        assertEquals("manager.demo", discrepancy.countedBy());

        var alerts = reports.recentAlerts(5, true).stream()
                .filter(alert -> alert.warehouseId() == warehouseId)
                .toList();
        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().anyMatch(alert -> alert.type() == AlertType.LOW_STOCK));
        assertTrue(alerts.stream().anyMatch(alert -> alert.type() == AlertType.DISCREPANCY));

        var audit = reports.recentAuditEntries(5).get(0);
        assertEquals("employee.demo", audit.actorUsername());
        assertTrue(audit.newValuesJson().contains("\"quantity\": 9"));
    }

    @Test
    void resolvesAnOpenAlertOnce() {
        var alert = reports.recentAlerts(5, true).get(0);

        assertTrue(reports.resolveAlert(alert.id(), managerId, "Count investigated"));
        assertFalse(reports.resolveAlert(alert.id(), managerId, "Duplicate resolution"));
        assertTrue(reports.recentAlerts(5, false).stream()
                .filter(candidate -> candidate.id() == alert.id())
                .findFirst()
                .orElseThrow()
                .isResolved());
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
