package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.AuditEntry;
import com.kshres31.inventory.model.DiscrepancyRecord;
import com.kshres31.inventory.model.InventoryAlert;
import com.kshres31.inventory.model.ProductMovementSummary;

import java.util.List;

public interface ReportRepository {
    List<ProductMovementSummary> mostMovedProducts(int limit);

    List<DiscrepancyRecord> recentDiscrepancies(int limit);

    List<InventoryAlert> recentAlerts(int limit, boolean unresolvedOnly);

    List<AuditEntry> recentAuditEntries(int limit);

    boolean resolveAlert(long alertId, long actorUserId, String note);
}
