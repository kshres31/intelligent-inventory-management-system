package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.InventoryItem;
import com.kshres31.inventory.model.Role;
import com.kshres31.inventory.model.StockTransaction;
import com.kshres31.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryServiceTest {
    private final RecordingInventoryRepository repository = new RecordingInventoryRepository();
    private final InventoryService service = new InventoryService(repository);

    @Test
    void employeeCanSellButCannotAdjustOrTransfer() {
        var employee = user(Role.EMPLOYEE, true);

        assertEquals(7, service.sell(employee, 1, 1, 3, "SALE-1"));
        assertThrows(AuthorizationException.class,
                () -> service.adjust(employee, 1, 1, -1, "damaged"));
        assertThrows(AuthorizationException.class,
                () -> service.transfer(employee, 1, 1, 2, 1, "move"));
    }

    @Test
    void inactiveUserCannotReadOrChangeInventory() {
        var inactiveAdmin = user(Role.ADMIN, false);

        assertThrows(AuthorizationException.class,
                () -> service.search(inactiveAdmin, "", false));
        assertThrows(AuthorizationException.class,
                () -> service.receive(inactiveAdmin, 1, 1, 1, null));
    }

    @Test
    void validatesQuantitiesBeforeCallingRepository() {
        var manager = user(Role.MANAGER, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.sell(manager, 1, 1, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordPhysicalCount(manager, 1, 1, -1, null));
        assertEquals(0, repository.callCount);
    }

    private AppUser user(Role role, boolean active) {
        return new AppUser(10, "test", "Test User", null, role, active);
    }

    private static final class RecordingInventoryRepository implements InventoryRepository {
        private int callCount;

        @Override
        public int receive(long productId, long warehouseId, int quantity, long actorUserId, String reference) {
            callCount++;
            return quantity;
        }

        @Override
        public int sell(long productId, long warehouseId, int quantity, long actorUserId, String reference) {
            callCount++;
            return 10 - quantity;
        }

        @Override
        public UUID transfer(long productId, long sourceWarehouseId, long destinationWarehouseId,
                             int quantity, long actorUserId, String reference) {
            callCount++;
            return UUID.randomUUID();
        }

        @Override
        public int adjust(long productId, long warehouseId, int quantityChange, long actorUserId, String reason) {
            callCount++;
            return 10 + quantityChange;
        }

        @Override
        public long recordPhysicalCount(long productId, long warehouseId, int countedQuantity,
                                        long actorUserId, String notes) {
            callCount++;
            return 1;
        }

        @Override
        public int currentQuantity(long productId, long warehouseId) {
            return 10;
        }

        @Override
        public List<InventoryItem> search(String query, boolean lowStockOnly) {
            callCount++;
            return List.of();
        }

        @Override
        public List<StockTransaction> transactionHistory(long productId, int limit) {
            callCount++;
            return List.of();
        }
    }
}
