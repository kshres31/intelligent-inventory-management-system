package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.InventoryItem;
import com.kshres31.inventory.model.StockTransaction;

import java.util.List;
import java.util.UUID;

public interface InventoryRepository {
    int receive(long productId, long warehouseId, int quantity, long actorUserId, String reference);

    int sell(long productId, long warehouseId, int quantity, long actorUserId, String reference);

    UUID transfer(long productId, long sourceWarehouseId, long destinationWarehouseId,
                  int quantity, long actorUserId, String reference);

    int adjust(long productId, long warehouseId, int quantityChange, long actorUserId, String reason);

    long recordPhysicalCount(long productId, long warehouseId, int countedQuantity,
                             long actorUserId, String notes);

    int currentQuantity(long productId, long warehouseId);

    /**
     * Sets a per-location low-stock threshold, or clears it when {@code threshold} is null so
     * the product default applies. Returns the effective threshold after the change.
     */
    int setLowStockThreshold(long productId, long warehouseId, Integer threshold, long actorUserId);

    List<InventoryItem> search(String query, boolean lowStockOnly);

    List<StockTransaction> transactionHistory(long productId, int limit, int offset);
}
