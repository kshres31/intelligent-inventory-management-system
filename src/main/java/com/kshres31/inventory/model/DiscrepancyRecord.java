package com.kshres31.inventory.model;

import java.time.OffsetDateTime;

public record DiscrepancyRecord(
        long countId,
        long productId,
        String sku,
        String productName,
        String warehouseCode,
        int expectedQuantity,
        int countedQuantity,
        int variance,
        String countedBy,
        OffsetDateTime countedAt,
        String notes
) {
}
