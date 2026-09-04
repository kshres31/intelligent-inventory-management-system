package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.PurchaseOrderSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PurchaseOrderRepository {
    long create(String orderNumber, long supplierId, long warehouseId, long actorUserId,
                LocalDate expectedAt, String notes);

    long addLine(long purchaseOrderId, long productId, int orderedQuantity, BigDecimal unitCost);

    boolean changeStatus(long purchaseOrderId, PurchaseOrderStatus expectedStatus,
                         PurchaseOrderStatus nextStatus);

    List<PurchaseOrderSummary> list(int limit);
}
