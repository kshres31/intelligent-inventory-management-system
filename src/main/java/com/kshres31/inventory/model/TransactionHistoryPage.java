package com.kshres31.inventory.model;

import java.util.List;

public record TransactionHistoryPage(
        List<StockTransaction> items,
        int pageNumber,
        int pageSize,
        boolean hasNext
) {
    public TransactionHistoryPage {
        items = List.copyOf(items);
    }
}
