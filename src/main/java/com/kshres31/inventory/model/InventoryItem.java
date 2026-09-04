package com.kshres31.inventory.model;

import java.time.OffsetDateTime;

public record InventoryItem(
        long productId,
        String sku,
        String productName,
        long warehouseId,
        String warehouseCode,
        String warehouseName,
        int quantity,
        int reorderThreshold,
        OffsetDateTime updatedAt
) {
    public boolean isLowStock() {
        return quantity < reorderThreshold;
    }
}
