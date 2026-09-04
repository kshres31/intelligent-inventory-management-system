package com.kshres31.inventory.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PurchaseOrderSummary(
        long id,
        String orderNumber,
        String supplierName,
        String warehouseCode,
        PurchaseOrderStatus status,
        String orderedBy,
        LocalDate expectedAt,
        long lineCount,
        long totalOrderedQuantity,
        long totalReceivedQuantity,
        OffsetDateTime createdAt
) {
}
