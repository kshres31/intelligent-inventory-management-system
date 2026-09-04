package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.model.Product;
import com.kshres31.inventory.model.ProductDraft;
import com.kshres31.inventory.repository.ProductRepository;

import java.util.List;

public final class ProductService {
    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    public Product create(AppUser actor, ProductDraft draft) {
        requireAdmin(actor);
        return products.create(draft);
    }

    public Product update(AppUser actor, long productId, ProductDraft draft) {
        requireAdmin(actor);
        return products.update(productId, draft);
    }

    public boolean deactivate(AppUser actor, long productId) {
        requireAdmin(actor);
        return products.deactivate(productId);
    }

    public List<Product> search(AppUser actor, String query, boolean includeInactive) {
        if (actor == null || !actor.active() || !actor.role().allows(Permission.VIEW_INVENTORY)) {
            throw new AuthorizationException("An active inventory user is required");
        }
        return products.search(query, includeInactive && actor.role().allows(Permission.MANAGE_PRODUCTS));
    }

    private void requireAdmin(AppUser actor) {
        if (actor == null || !actor.active() || !actor.role().allows(Permission.MANAGE_PRODUCTS)) {
            throw new AuthorizationException("Only an active administrator can manage products");
        }
    }
}
