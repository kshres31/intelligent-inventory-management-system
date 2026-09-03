package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.exception.DuplicateSkuException;
import com.kshres31.inventory.model.ProductDraft;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductRepositoryIntegrationTest {
    private DatabaseTestSupport database;
    private ProductRepository products;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        products = new JdbcProductRepository(() -> database.dataSource().getConnection());
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsSearchesUpdatesAndDeactivatesProduct() {
        var created = products.create(draft("LAPTOP-STAND", "Laptop Stand", new BigDecimal("29.95")));

        assertEquals("LAPTOP-STAND", products.findById(created.id()).orElseThrow().sku());
        assertEquals(1, products.search("laptop", false).size());

        var updatedDraft = new ProductDraft(
                "LAPTOP-STAND", "Adjustable Laptop Stand", "Aluminum desk stand",
                null, null, 4, 1, new BigDecimal("34.95")
        );
        var updated = products.update(created.id(), updatedDraft);
        assertEquals("Adjustable Laptop Stand", updated.name());
        assertEquals(4, updated.reorderThreshold());

        assertTrue(products.deactivate(created.id()));
        assertFalse(products.findBySku("LAPTOP-STAND").orElseThrow().active());
        assertTrue(products.search("laptop", false).isEmpty());
        assertEquals(1, products.search("laptop", true).size());
    }

    @Test
    void reportsDuplicateSkuClearly() {
        var duplicate = draft("USB-C-2M", "Different cable", BigDecimal.TEN);

        assertThrows(DuplicateSkuException.class, () -> products.create(duplicate));
    }

    private ProductDraft draft(String sku, String name, BigDecimal price) {
        return new ProductDraft(sku, name, null, null, null, 2, 1, price);
    }
}
