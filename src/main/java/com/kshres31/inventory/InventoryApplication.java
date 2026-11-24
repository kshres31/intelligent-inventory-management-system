package com.kshres31.inventory;

import com.kshres31.inventory.config.DatabaseConfig;
import com.kshres31.inventory.config.JdbcConnectionFactory;
import com.kshres31.inventory.controller.InventoryCli;
import com.kshres31.inventory.notification.NotificationConfig;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.notification.RetryPolicy;
import com.kshres31.inventory.repository.JdbcAlertNotificationRepository;
import com.kshres31.inventory.repository.JdbcInventoryRepository;
import com.kshres31.inventory.repository.JdbcProductRepository;
import com.kshres31.inventory.repository.JdbcPurchaseOrderRepository;
import com.kshres31.inventory.repository.JdbcReportRepository;
import com.kshres31.inventory.repository.JdbcUserRepository;
import com.kshres31.inventory.service.AlertNotificationService;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ProductService;
import com.kshres31.inventory.service.PurchaseOrderService;
import com.kshres31.inventory.service.ReportingService;
import com.kshres31.inventory.service.UserService;

import java.time.Duration;

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
            var notificationRepository = new JdbcAlertNotificationRepository(connections);
            var dispatcher = new NotificationDispatcher(
                    notificationRepository,
                    NotificationConfig.fromEnvironment(System.getenv()).createChannel(System.out),
                    RetryPolicy.defaults(),
                    Duration.ofMinutes(2)
            );

            var cli = new InventoryCli(
                    users,
                    new InventoryService(inventoryRepository),
                    new ReportingService(inventoryRepository, reports),
                    new ProductService(products),
                    new PurchaseOrderService(orders),
                    new UserService(users),
                    new AlertNotificationService(notificationRepository, dispatcher),
                    System.out
            );
            cli.run(args);
        } catch (RuntimeException error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }
}
