package com.kshres31.inventory.model;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    ADMIN(EnumSet.allOf(Permission.class)),
    MANAGER(EnumSet.of(
            Permission.VIEW_INVENTORY,
            Permission.RECORD_STOCK_MOVEMENT,
            Permission.ADJUST_INVENTORY,
            Permission.MANAGE_PURCHASE_ORDERS,
            Permission.RESOLVE_ALERTS,
            Permission.CONFIGURE_ALERTS
    )),
    EMPLOYEE(EnumSet.of(
            Permission.VIEW_INVENTORY,
            Permission.RECORD_STOCK_MOVEMENT
    ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }
}
