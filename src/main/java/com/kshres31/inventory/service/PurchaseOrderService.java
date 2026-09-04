package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.PurchaseOrderSummary;
import com.kshres31.inventory.repository.PurchaseOrderRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseOrderService {
    private final PurchaseOrderRepository orders;

    public PurchaseOrderService(PurchaseOrderRepository orders) {
        this.orders = orders;
    }

    public long create(AppUser actor, String orderNumber, long supplierId, long warehouseId,
                       LocalDate expectedAt, String notes) {
        require(actor, Permission.MANAGE_PURCHASE_ORDERS);
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("Order number is required");
        }
        return orders.create(orderNumber.strip(), supplierId, warehouseId, actor.id(), expectedAt, notes);
    }

    public long addLine(AppUser actor, long purchaseOrderId, long productId,
                        int orderedQuantity, BigDecimal unitCost) {
        require(actor, Permission.MANAGE_PURCHASE_ORDERS);
        if (orderedQuantity <= 0) {
            throw new IllegalArgumentException("Ordered quantity must be positive");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw new IllegalArgumentException("Unit cost cannot be negative");
        }
        return orders.addLine(purchaseOrderId, productId, orderedQuantity, unitCost);
    }

    public void transition(AppUser actor, long purchaseOrderId,
                           PurchaseOrderStatus current, PurchaseOrderStatus next) {
        require(actor, Permission.MANAGE_PURCHASE_ORDERS);
        if (!current.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid purchase-order transition: " + current + " to " + next);
        }
        if (!orders.changeStatus(purchaseOrderId, current, next)) {
            throw new IllegalStateException("Purchase order changed or does not exist; refresh before retrying");
        }
    }

    public List<PurchaseOrderSummary> list(AppUser actor, int limit) {
        require(actor, Permission.VIEW_INVENTORY);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Purchase-order limit must be between 1 and 500");
        }
        return orders.list(limit);
    }

    private void require(AppUser actor, Permission permission) {
        if (actor == null || !actor.active() || !actor.role().allows(permission)) {
            throw new AuthorizationException("Active user lacks permission: " + permission);
        }
    }
}
