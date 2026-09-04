package com.kshres31.inventory.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StockTransaction(
        long id,
        long productId,
        long warehouseId,
        StockTransactionType type,
        int quantityChange,
        int quantityBefore,
        int quantityAfter,
        long actorUserId,
        UUID transferId,
        String reference,
        OffsetDateTime createdAt
) {
}
