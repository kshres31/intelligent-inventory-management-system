package com.kshres31.inventory;

import com.kshres31.inventory.config.DatabaseConfig;
import com.kshres31.inventory.config.JdbcConnectionFactory;
import com.kshres31.inventory.controller.InventoryCli;
import com.kshres31.inventory.repository.JdbcInventoryRepository;
import com.kshres31.inventory.repository.JdbcProductRepository;
import com.kshres31.inventory.repository.JdbcPurchaseOrderRepository;
import com.kshres31.inventory.repository.JdbcReportRepository;
import com.kshres31.inventory.repository.JdbcUserRepository;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ProductService;
import com.kshres31.inventory.service.PurchaseOrderService;
import com.kshres31.inventory.service.ReportingService;
import com.kshres31.inventory.service.UserService;

public final class InventoryApplication {
    private InventoryApplication() {
    }

    public static void main(String[] args) {
        try {
            var connections = new JdbcConnectionFactory(DatabaseConfig.fromEnvironment(System.getenv()));
            var users = new JdbcUserRepository(connections);
            var inventoryRepository = new JdbcInventoryRepository(connections);
            var products = new JdbcProductRepository(connections);
            var reports = new JdbcReportRepository(connections);
            var orders = new JdbcPurchaseOrderRepository(connections);

            var cli = new InventoryCli(
                    users,
                    new InventoryService(inventoryRepository),
                    new ReportingService(inventoryRepository, reports),
                    new ProductService(products),
                    new PurchaseOrderService(orders),
                    new UserService(users),
                    System.out
            );
            cli.run(args);
        } catch (RuntimeException error) {
            System.err.println("Error: " + error.getMessage());
        }
    }
}
