package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.Product;
import com.kshres31.inventory.model.ProductDraft;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Product create(ProductDraft draft);

    Product update(long productId, ProductDraft draft);

    boolean deactivate(long productId);

    Optional<Product> findById(long productId);

    Optional<Product> findBySku(String sku);

    List<Product> search(String query, boolean includeInactive);
}
