package com.kshres31.inventory.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ProductDraft(
        String sku,
        String name,
        String description,
        Long categoryId,
        Long preferredSupplierId,
        int reorderThreshold,
        int discrepancyThreshold,
        BigDecimal unitPrice
) {
    public ProductDraft {
        sku = requireText(sku, "SKU");
        name = requireText(name, "Product name");
        Objects.requireNonNull(unitPrice, "Unit price is required");
        if (reorderThreshold < 0) {
            throw new IllegalArgumentException("Reorder threshold cannot be negative");
        }
        if (discrepancyThreshold < 0) {
            throw new IllegalArgumentException("Discrepancy threshold cannot be negative");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price cannot be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
