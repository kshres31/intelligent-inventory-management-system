package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.PurchaseOrderSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PurchaseOrderRepository {
    long create(String orderNumber, long supplierId, long warehouseId, long actorUserId,
                LocalDate expectedAt, String notes);

    long addLine(long purchaseOrderId, long productId, int orderedQuantity, BigDecimal unitCost);

    int receiveLine(long purchaseOrderId, long productId, int quantity, long actorUserId, UUID requestId);

    boolean changeStatus(long purchaseOrderId, PurchaseOrderStatus expectedStatus,
                         PurchaseOrderStatus nextStatus);

    List<PurchaseOrderSummary> list(int limit);
}
