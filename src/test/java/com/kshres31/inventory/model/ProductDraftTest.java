package com.kshres31.inventory.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductDraftTest {
    @Test
    void trimsRequiredText() {
        var draft = new ProductDraft("  SKU-1  ", "  Product  ", null, null, null, 1, 0, BigDecimal.TEN);

        assertEquals("SKU-1", draft.sku());
        assertEquals("Product", draft.name());
    }

    @Test
    void rejectsInvalidThresholdAndPriceValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductDraft("SKU", "Name", null, null, null, -1, 0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductDraft("SKU", "Name", null, null, null, 0, -1, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductDraft("SKU", "Name", null, null, null, 0, 0, BigDecimal.valueOf(-1)));
    }
}
