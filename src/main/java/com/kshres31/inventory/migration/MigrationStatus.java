package com.kshres31.inventory.migration;

import java.time.OffsetDateTime;

/** A migration file and, when applied, when that happened. */
public record MigrationStatus(Migration migration, OffsetDateTime appliedAt) {
    public boolean applied() {
        return appliedAt != null;
    }
}
