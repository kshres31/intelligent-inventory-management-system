package com.kshres31.inventory.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Product(
        long id,
        String sku,
        String name,
        String description,
        Long categoryId,
        Long preferredSupplierId,
        int reorderThreshold,
        int discrepancyThreshold,
        BigDecimal unitPrice,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
