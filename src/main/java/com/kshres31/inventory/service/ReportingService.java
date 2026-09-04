package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.AuditEntry;
import com.kshres31.inventory.model.DiscrepancyRecord;
import com.kshres31.inventory.model.InventoryAlert;
import com.kshres31.inventory.model.InventoryItem;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.model.ProductMovementSummary;
import com.kshres31.inventory.repository.InventoryRepository;
import com.kshres31.inventory.repository.ReportRepository;

import java.util.List;

public final class ReportingService {
    private final InventoryRepository inventory;
    private final ReportRepository reports;

    public ReportingService(InventoryRepository inventory, ReportRepository reports) {
        this.inventory = inventory;
        this.reports = reports;
    }

    public List<InventoryItem> currentInventory(AppUser actor, String query) {
        require(actor, Permission.VIEW_INVENTORY);
        return inventory.search(query, false);
    }

    public List<InventoryItem> lowStock(AppUser actor) {
        require(actor, Permission.VIEW_INVENTORY);
        return inventory.search("", true);
    }

    public List<ProductMovementSummary> mostMovedProducts(AppUser actor, int limit) {
        require(actor, Permission.VIEW_INVENTORY);
        validateLimit(limit);
        return reports.mostMovedProducts(limit);
    }

    public List<DiscrepancyRecord> recentDiscrepancies(AppUser actor, int limit) {
        require(actor, Permission.VIEW_INVENTORY);
        validateLimit(limit);
        return reports.recentDiscrepancies(limit);
    }

    public List<InventoryAlert> recentAlerts(AppUser actor, int limit, boolean unresolvedOnly) {
        require(actor, Permission.VIEW_INVENTORY);
        validateLimit(limit);
        return reports.recentAlerts(limit, unresolvedOnly);
    }

    public List<AuditEntry> recentAuditEntries(AppUser actor, int limit) {
        require(actor, Permission.VIEW_INVENTORY);
        validateLimit(limit);
        return reports.recentAuditEntries(limit);
    }

    public boolean resolveAlert(AppUser actor, long alertId, String note) {
        require(actor, Permission.RESOLVE_ALERTS);
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A resolution note is required");
        }
        return reports.resolveAlert(alertId, actor.id(), note.strip());
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Report limit must be between 1 and 500");
        }
    }

    private void require(AppUser actor, Permission permission) {
        if (actor == null || !actor.active() || !actor.role().allows(permission)) {
            throw new AuthorizationException("Active user lacks permission: " + permission);
        }
    }
}
