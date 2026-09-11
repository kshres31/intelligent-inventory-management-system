package com.kshres31.inventory.model;

import java.util.Set;

public enum PurchaseOrderStatus {
    DRAFT(Set.of("SUBMITTED", "CANCELLED")),
    SUBMITTED(Set.of("CANCELLED")),
    PARTIALLY_RECEIVED(Set.of("CANCELLED")),
    RECEIVED(Set.of()),
    CANCELLED(Set.of());

    private final Set<String> allowedNextStates;

    PurchaseOrderStatus(Set<String> allowedNextStates) {
        this.allowedNextStates = allowedNextStates;
    }

    public boolean canTransitionTo(PurchaseOrderStatus next) {
        return allowedNextStates.contains(next.name());
    }
}
