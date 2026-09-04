package com.kshres31.inventory.controller;

import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.AppUserDraft;
import com.kshres31.inventory.model.ProductDraft;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.Role;
import com.kshres31.inventory.repository.UserRepository;
import com.kshres31.inventory.service.InventoryService;
import com.kshres31.inventory.service.ProductService;
import com.kshres31.inventory.service.PurchaseOrderService;
import com.kshres31.inventory.service.ReportingService;
import com.kshres31.inventory.service.UserService;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

public final class InventoryCli {
    private final UserRepository users;
    private final InventoryService inventory;
    private final ReportingService reporting;
    private final ProductService products;
    private final PurchaseOrderService purchaseOrders;
    private final UserService userService;
    private final PrintStream out;

    public InventoryCli(UserRepository users, InventoryService inventory, ReportingService reporting,
                        ProductService products, PurchaseOrderService purchaseOrders,
                        UserService userService, PrintStream out) {
        this.users = users;
        this.inventory = inventory;
        this.reporting = reporting;
        this.products = products;
        this.purchaseOrders = purchaseOrders;
        this.userService = userService;
        this.out = out;
    }

    public void run(String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            printHelp();
            return;
        }
        requireArgs(args, 2);
        AppUser actor = users.findByUsername(args[0])
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + args[0]));
        String command = args[1].toLowerCase();

        switch (command) {
            case "inventory" -> showInventory(actor, joinFrom(args, 2));
            case "low-stock" -> showLowStock(actor);
            case "receive" -> receive(actor, args);
            case "sell" -> sell(actor, args);
            case "transfer" -> transfer(actor, args);
            case "adjust" -> adjust(actor, args);
            case "count" -> count(actor, args);
            case "transactions" -> showTransactions(actor, args);
            case "alerts" -> showAlerts(actor, args);
            case "resolve-alert" -> resolveAlert(actor, args);
            case "most-moved" -> showMostMoved(actor, args);
            case "discrepancies" -> showDiscrepancies(actor, args);
            case "audit" -> showAudit(actor, args);
            case "products" -> showProducts(actor, args);
            case "add-product" -> addProduct(actor, args);
            case "update-product" -> updateProduct(actor, args);
            case "deactivate-product" -> deactivateProduct(actor, args);
            case "purchase-orders" -> showPurchaseOrders(actor, args);
            case "create-order" -> createOrder(actor, args);
            case "add-order-line" -> addOrderLine(actor, args);
            case "order-status" -> changeOrderStatus(actor, args);
            case "users" -> showUsers(actor, args);
            case "create-user" -> createUser(actor, args);
            case "deactivate-user" -> deactivateUser(actor, args);
            default -> throw new IllegalArgumentException("Unknown command: " + command + ". Run 'help'.");
        }
    }

    private void showInventory(AppUser actor, String query) {
        reporting.currentInventory(actor, query).forEach(item -> out.printf(
                "%d | %s | %s | %s | qty=%d | reorder=%d%n",
                item.productId(), item.sku(), item.productName(), item.warehouseCode(),
                item.quantity(), item.reorderThreshold()
        ));
    }

    private void showLowStock(AppUser actor) {
        reporting.lowStock(actor).forEach(item -> out.printf(
                "%s @ %s: %d (threshold %d)%n",
                item.sku(), item.warehouseCode(), item.quantity(), item.reorderThreshold()
        ));
    }

    private void receive(AppUser actor, String[] args) {
        requireArgs(args, 5);
        int newQuantity = inventory.receive(
                actor, longArg(args, 2), longArg(args, 3), intArg(args, 4), optional(args, 5)
        );
        out.println("Inventory received. New quantity: " + newQuantity);
    }

    private void sell(AppUser actor, String[] args) {
        requireArgs(args, 5);
        int newQuantity = inventory.sell(
                actor, longArg(args, 2), longArg(args, 3), intArg(args, 4), optional(args, 5)
        );
        out.println("Sale recorded. New quantity: " + newQuantity);
    }

    private void transfer(AppUser actor, String[] args) {
        requireArgs(args, 6);
        var transferId = inventory.transfer(
                actor, longArg(args, 2), longArg(args, 3), longArg(args, 4),
                intArg(args, 5), optional(args, 6)
        );
        out.println("Transfer recorded: " + transferId);
    }

    private void adjust(AppUser actor, String[] args) {
        requireArgs(args, 6);
        int newQuantity = inventory.adjust(
                actor, longArg(args, 2), longArg(args, 3), intArg(args, 4), joinFrom(args, 5)
        );
        out.println("Inventory adjusted. New quantity: " + newQuantity);
    }

    private void count(AppUser actor, String[] args) {
        requireArgs(args, 5);
        long countId = inventory.recordPhysicalCount(
                actor, longArg(args, 2), longArg(args, 3), intArg(args, 4), joinFrom(args, 5)
        );
        out.println("Physical count recorded: " + countId);
    }

    private void showTransactions(AppUser actor, String[] args) {
        requireArgs(args, 3);
        int limit = args.length > 3 ? intArg(args, 3) : 20;
        inventory.transactionHistory(actor, longArg(args, 2), limit).forEach(transaction -> out.printf(
                "%s | warehouse=%d | change=%+d | %d -> %d | actor=%d | %s%n",
                transaction.type(), transaction.warehouseId(), transaction.quantityChange(),
                transaction.quantityBefore(), transaction.quantityAfter(), transaction.actorUserId(),
                transaction.reference()
        ));
    }

    private void showAlerts(AppUser actor, String[] args) {
        boolean unresolvedOnly = args.length <= 2 || !"all".equalsIgnoreCase(args[2]);
        reporting.recentAlerts(actor, 50, unresolvedOnly).forEach(alert -> out.printf(
                "%d | %s | %s @ %s | %s | resolved=%s%n",
                alert.id(), alert.type(), alert.sku(), alert.warehouseCode(), alert.message(), alert.isResolved()
        ));
    }

    private void resolveAlert(AppUser actor, String[] args) {
        requireArgs(args, 4);
        out.println(reporting.resolveAlert(actor, longArg(args, 2), joinFrom(args, 3))
                ? "Alert resolved." : "Alert was already resolved or does not exist.");
    }

    private void showMostMoved(AppUser actor, String[] args) {
        int limit = args.length > 2 ? intArg(args, 2) : 10;
        reporting.mostMovedProducts(actor, limit).forEach(row -> out.printf(
                "%s | %s | operations=%d | units moved=%d%n",
                row.sku(), row.productName(), row.operationCount(), row.unitsMoved()
        ));
    }

    private void showDiscrepancies(AppUser actor, String[] args) {
        int limit = args.length > 2 ? intArg(args, 2) : 20;
        reporting.recentDiscrepancies(actor, limit).forEach(row -> out.printf(
                "%s @ %s | expected=%d | counted=%d | variance=%+d | by=%s%n",
                row.sku(), row.warehouseCode(), row.expectedQuantity(), row.countedQuantity(),
                row.variance(), row.countedBy()
        ));
    }

    private void showAudit(AppUser actor, String[] args) {
        int limit = args.length > 2 ? intArg(args, 2) : 20;
        reporting.recentAuditEntries(actor, limit).forEach(row -> out.printf(
                "%d | %s %s | actor=%s | old=%s | new=%s%n",
                row.id(), row.action(), row.entityKey(), row.actorUsername(),
                row.oldValuesJson(), row.newValuesJson()
        ));
    }

    private void showProducts(AppUser actor, String[] args) {
        String query = args.length > 2 ? args[2] : "";
        boolean includeInactive = args.length > 3 && "all".equalsIgnoreCase(args[3]);
        products.search(actor, query, includeInactive).forEach(product -> out.printf(
                "%d | %s | %s | $%s | active=%s%n",
                product.id(), product.sku(), product.name(), product.unitPrice(), product.active()
        ));
    }

    private void addProduct(AppUser actor, String[] args) {
        requireArgs(args, 7);
        var draft = productDraft(args, 2);
        out.println("Product created: " + products.create(actor, draft).id());
    }

    private void updateProduct(AppUser actor, String[] args) {
        requireArgs(args, 8);
        long productId = longArg(args, 2);
        var draft = productDraft(args, 3);
        out.println("Product updated: " + products.update(actor, productId, draft).id());
    }

    private ProductDraft productDraft(String[] args, int offset) {
        return new ProductDraft(
                args[offset], args[offset + 1], null, null, null,
                Integer.parseInt(args[offset + 3]), Integer.parseInt(args[offset + 4]),
                new BigDecimal(args[offset + 2])
        );
    }

    private void deactivateProduct(AppUser actor, String[] args) {
        requireArgs(args, 3);
        out.println(products.deactivate(actor, longArg(args, 2)) ? "Product deactivated." : "No active product found.");
    }

    private void showPurchaseOrders(AppUser actor, String[] args) {
        int limit = args.length > 2 ? intArg(args, 2) : 20;
        purchaseOrders.list(actor, limit).forEach(order -> out.printf(
                "%d | %s | %s | %s | lines=%d | ordered=%d | received=%d%n",
                order.id(), order.orderNumber(), order.status(), order.warehouseCode(),
                order.lineCount(), order.totalOrderedQuantity(), order.totalReceivedQuantity()
        ));
    }

    private void createOrder(AppUser actor, String[] args) {
        requireArgs(args, 6);
        LocalDate expectedAt = "-".equals(args[5]) ? null : LocalDate.parse(args[5]);
        long id = purchaseOrders.create(
                actor, args[2], longArg(args, 3), longArg(args, 4), expectedAt, joinFrom(args, 6)
        );
        out.println("Purchase order created: " + id);
    }

    private void addOrderLine(AppUser actor, String[] args) {
        requireArgs(args, 6);
        long id = purchaseOrders.addLine(
                actor, longArg(args, 2), longArg(args, 3), intArg(args, 4), new BigDecimal(args[5])
        );
        out.println("Purchase-order line created: " + id);
    }

    private void changeOrderStatus(AppUser actor, String[] args) {
        requireArgs(args, 5);
        purchaseOrders.transition(
                actor, longArg(args, 2), PurchaseOrderStatus.valueOf(args[3].toUpperCase()),
                PurchaseOrderStatus.valueOf(args[4].toUpperCase())
        );
        out.println("Purchase-order status updated.");
    }

    private void showUsers(AppUser actor, String[] args) {
        boolean includeInactive = args.length > 2 && "all".equalsIgnoreCase(args[2]);
        userService.list(actor, includeInactive).forEach(user -> out.printf(
                "%d | %s | %s | %s | active=%s%n",
                user.id(), user.username(), user.displayName(), user.role(), user.active()
        ));
    }

    private void createUser(AppUser actor, String[] args) {
        requireArgs(args, 6);
        var draft = new AppUserDraft(args[2], args[3], "-".equals(args[5]) ? null : args[5],
                Role.valueOf(args[4].toUpperCase()));
        out.println("User created: " + userService.create(actor, draft).id());
    }

    private void deactivateUser(AppUser actor, String[] args) {
        requireArgs(args, 3);
        out.println(userService.deactivate(actor, longArg(args, 2)) ? "User deactivated." : "No active user found.");
    }

    private long longArg(String[] args, int index) {
        return Long.parseLong(args[index]);
    }

    private int intArg(String[] args, int index) {
        return Integer.parseInt(args[index]);
    }

    private String optional(String[] args, int index) {
        return args.length > index ? joinFrom(args, index) : null;
    }

    private String joinFrom(String[] args, int index) {
        return args.length <= index ? "" : String.join(" ", Arrays.copyOfRange(args, index, args.length));
    }

    private void requireArgs(String[] args, int count) {
        if (args.length < count) {
            throw new IllegalArgumentException("Not enough arguments. Run 'help' for examples.");
        }
    }

    private void printHelp() {
        out.println("Usage: <username> <command> [arguments]");
        out.println("Read: inventory [query], low-stock, transactions <productId> [limit], alerts [all]");
        out.println("Reports: most-moved [limit], discrepancies [limit], audit [limit]");
        out.println("Stock: receive|sell <productId> <warehouseId> <quantity> [reference]");
        out.println("       transfer <productId> <fromId> <toId> <quantity> [reference]");
        out.println("       adjust <productId> <warehouseId> <change> <reason>");
        out.println("       count <productId> <warehouseId> <physicalQuantity> [notes]");
        out.println("Admin: products [query] [all], add-product <sku> <name> <price> <reorder> <discrepancy>");
        out.println("       update-product <id> <sku> <name> <price> <reorder> <discrepancy>");
        out.println("       deactivate-product <id>, users [all], create-user <username> <displayName> <role> <email|->");
        out.println("Orders: purchase-orders [limit], create-order <number> <supplierId> <warehouseId> <date|-> [notes]");
        out.println("        add-order-line <orderId> <productId> <quantity> <unitCost>");
        out.println("        order-status <orderId> <current> <next>");
        out.println("Alerts: resolve-alert <alertId> <resolution note>");
    }
}
