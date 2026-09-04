package com.kshres31.inventory.model;

import java.time.OffsetDateTime;

public record AuditEntry(
        long id,
        String entityType,
        String entityKey,
        String action,
        String oldValuesJson,
        String newValuesJson,
        String actorUsername,
        OffsetDateTime createdAt
) {
}
