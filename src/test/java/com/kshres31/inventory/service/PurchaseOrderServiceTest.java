package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.PurchaseOrderStatus;
import com.kshres31.inventory.model.Role;
import com.kshres31.inventory.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PurchaseOrderServiceTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final PurchaseOrderRepository repository = (PurchaseOrderRepository) Proxy.newProxyInstance(
            PurchaseOrderRepository.class.getClassLoader(), new Class<?>[]{PurchaseOrderRepository.class},
            (proxy, method, args) -> { calls.incrementAndGet(); return 7; });
    private final PurchaseOrderService service = new PurchaseOrderService(repository);

    @Test
    void rejectsEmployeeInactiveAndMissingActorsBeforeDatabaseAccess() {
        for (AppUser actor : new AppUser[]{user(Role.EMPLOYEE, true), user(Role.ADMIN, false), null}) {
            assertThrows(AuthorizationException.class,
                    () -> service.receiveLine(actor, 1, 2, 3, UUID.randomUUID()));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void requiresPositiveIdsQuantityAndAnExplicitRetryKey() {
        var manager = user(Role.MANAGER, true);
        assertThrows(IllegalArgumentException.class, () -> service.receiveLine(manager, 0, 2, 3, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.receiveLine(manager, 1, -1, 3, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.receiveLine(manager, 1, 2, 0, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.receiveLine(manager, 1, 2, 3, null));
        assertEquals(0, calls.get());
    }

    @Test
    void managerAndAdminCanReceive() {
        for (Role role : new Role[]{Role.MANAGER, Role.ADMIN}) {
            assertEquals(7, service.receiveLine(user(role, true), 1, 2, 3, UUID.randomUUID()));
        }
        assertEquals(2, calls.get());
    }

    @Test
    void receiptStatusesCannotBeAssignedManually() {
        for (PurchaseOrderStatus status : new PurchaseOrderStatus[]{
                PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.RECEIVED, null}) {
            assertThrows(IllegalArgumentException.class, () -> service.transition(
                    user(Role.MANAGER, true), 1, PurchaseOrderStatus.SUBMITTED, status));
        }
        assertEquals(0, calls.get());
    }

    private AppUser user(Role role, boolean active) {
        return new AppUser(3, "demo", "Demo", null, role, active);
    }
}
