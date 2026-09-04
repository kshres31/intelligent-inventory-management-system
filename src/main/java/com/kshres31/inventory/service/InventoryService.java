package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.InventoryItem;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.model.StockTransaction;
import com.kshres31.inventory.repository.InventoryRepository;

import java.util.List;
import java.util.UUID;

public final class InventoryService {
    private final InventoryRepository inventory;

    public InventoryService(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    public int receive(AppUser actor, long productId, long warehouseId, int quantity, String reference) {
        requirePermission(actor, Permission.RECORD_STOCK_MOVEMENT);
        requirePositive(quantity, "Received quantity");
        return inventory.receive(productId, warehouseId, quantity, actor.id(), reference);
    }

    public int sell(AppUser actor, long productId, long warehouseId, int quantity, String reference) {
        requirePermission(actor, Permission.RECORD_STOCK_MOVEMENT);
        requirePositive(quantity, "Sale quantity");
        return inventory.sell(productId, warehouseId, quantity, actor.id(), reference);
    }

    public UUID transfer(AppUser actor, long productId, long sourceWarehouseId,
                         long destinationWarehouseId, int quantity, String reference) {
        requirePermission(actor, Permission.ADJUST_INVENTORY);
        requirePositive(quantity, "Transfer quantity");
        if (sourceWarehouseId == destinationWarehouseId) {
            throw new IllegalArgumentException("Source and destination warehouses must differ");
        }
        return inventory.transfer(
                productId, sourceWarehouseId, destinationWarehouseId, quantity, actor.id(), reference
        );
    }

    public int adjust(AppUser actor, long productId, long warehouseId, int quantityChange, String reason) {
        requirePermission(actor, Permission.ADJUST_INVENTORY);
        if (quantityChange == 0) {
            throw new IllegalArgumentException("Adjustment must change the quantity");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Adjustment reason is required");
        }
        return inventory.adjust(productId, warehouseId, quantityChange, actor.id(), reason.strip());
    }

    public long recordPhysicalCount(AppUser actor, long productId, long warehouseId,
                                    int countedQuantity, String notes) {
        requirePermission(actor, Permission.ADJUST_INVENTORY);
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("Counted quantity cannot be negative");
        }
        return inventory.recordPhysicalCount(productId, warehouseId, countedQuantity, actor.id(), notes);
    }

    public List<InventoryItem> search(AppUser actor, String query, boolean lowStockOnly) {
        requirePermission(actor, Permission.VIEW_INVENTORY);
        return inventory.search(query, lowStockOnly);
    }

    public List<StockTransaction> transactionHistory(AppUser actor, long productId, int limit) {
        requirePermission(actor, Permission.VIEW_INVENTORY);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("History limit must be between 1 and 500");
        }
        return inventory.transactionHistory(productId, limit);
    }

    private void requirePermission(AppUser actor, Permission permission) {
        if (actor == null || !actor.active()) {
            throw new AuthorizationException("An active user is required");
        }
        if (!actor.role().allows(permission)) {
            throw new AuthorizationException(actor.role() + " cannot perform this operation");
        }
    }

    private void requirePositive(int quantity, String field) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
