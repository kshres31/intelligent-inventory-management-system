package com.kshres31.inventory.model;

import java.time.OffsetDateTime;

public record InventoryAlert(
        long id,
        AlertType type,
        AlertSeverity severity,
        long productId,
        String sku,
        long warehouseId,
        String warehouseCode,
        String message,
        Integer expectedQuantity,
        Integer actualQuantity,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        Long resolvedBy,
        String resolutionNote
) {
    public boolean isResolved() {
        return resolvedAt != null;
    }
}
