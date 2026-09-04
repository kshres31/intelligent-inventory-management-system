package com.kshres31.inventory.model;

public record ProductMovementSummary(
        long productId,
        String sku,
        String productName,
        long operationCount,
        long unitsMoved
) {
}
