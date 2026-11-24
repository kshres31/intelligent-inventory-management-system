package com.kshres31.inventory;

import com.kshres31.inventory.config.DatabaseConfig;
import com.kshres31.inventory.config.JdbcConnectionFactory;
import com.kshres31.inventory.controller.AuthCommand;
import com.kshres31.inventory.controller.ConsolePasswordPrompt;
import com.kshres31.inventory.controller.InventoryCli;
import com.kshres31.inventory.migration.MigrationCommand;
import com.kshres31.inventory.migration.MigrationRunner;
import com.kshres31.inventory.notification.NotificationConfig;
import com.kshres31.inventory.notification.NotificationDispatcher;
import com.kshres31.inventory.notification.RetryPolicy;
import com.kshres31.inventory.repository.JdbcAlertNotificationRepository;
import com.kshres31.inventory.repository.JdbcInventoryRepository;
import com.kshres31.inventory.repository.JdbcProductRepository;
import com.kshres31.inventory.repository.JdbcPurchaseOrderRepository;
import com.kshres31.inventory.repository.JdbcReportRepository;
import com.kshres31.inventory.repository.JdbcUserRepository;
import com.kshres31.inventory.security.AuthPolicy;
import com.kshres31.inventory.security.AuthenticationService;
import com.kshres31.inventory.security.JdbcCredentialRepository;
import com.kshres31.inventory.security.PasswordHasher;
import com.kshres31.inventory.security.SessionStore;
import com.kshres31.inventory.service.AlertNotificationService;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ProductService;
import com.kshres31.inventory.service.PurchaseOrderService;
import com.kshres31.inventory.service.ReportingService;
import com.kshres31.inventory.service.UserService;

import java.nio.file.Path;
import java.time.Duration;

public final class InventoryApplication {
    private InventoryApplication() {
    }

    public static void main(String[] args) {
        try {
            var connections = new JdbcConnectionFactory(DatabaseConfig.fromEnvironment(System.getenv()));
            if (MigrationCommand.handles(args)) {
                Path directory = Path.of(System.getenv().getOrDefault("INVENTORY_MIGRATIONS_DIR", "database"));
                new MigrationCommand(new MigrationRunner(connections, directory), System.out).run(args);
                return;
            }
            var users = new JdbcUserRepository(connections);
            var environment = System.getenv();
            var auth = new AuthCommand(
                    new AuthenticationService(users, new JdbcCredentialRepository(connections),
                            new PasswordHasher(PasswordHasher.DEFAULT_ITERATIONS), AuthPolicy.defaults()),
                    SessionStore.fromEnvironment(environment),
                    () -> environment.get("INVENTORY_SESSION_TOKEN"),
                    new ConsolePasswordPrompt(),
                    System.out
            );
            if (AuthCommand.handles(args)) {
                auth.run(args);
                return;
            }
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
            if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
                cli.run(args);
            } else if (Boolean.parseBoolean(environment.getOrDefault("INVENTORY_DEMO_MODE", "false"))) {
                // Demo only: the first argument names the acting user without a password.
                cli.run(args);
            } else {
                cli.runAs(auth.currentUser(), args);
            }
        } catch (RuntimeException error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }
}
