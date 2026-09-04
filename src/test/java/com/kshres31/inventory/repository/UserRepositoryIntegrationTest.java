package com.kshres31.inventory.repository;

import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.model.AppUserDraft;
import com.kshres31.inventory.model.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRepositoryIntegrationTest {
    private DatabaseTestSupport database;
    private UserRepository users;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        users = new JdbcUserRepository(() -> database.dataSource().getConnection());
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsListsFindsAndDeactivatesUser() {
        var created = users.create(new AppUserDraft(
                "receiver.demo", "Receiving Clerk", null, Role.EMPLOYEE
        ));

        assertEquals(Role.EMPLOYEE, users.findByUsername("receiver.demo").orElseThrow().role());
        assertTrue(users.list(false).stream().anyMatch(user -> user.id() == created.id()));
        assertTrue(users.deactivate(created.id()));
        assertFalse(users.findByUsername("receiver.demo").orElseThrow().active());
        assertTrue(users.list(false).stream().noneMatch(user -> user.id() == created.id()));
        assertTrue(users.list(true).stream().anyMatch(user -> user.id() == created.id()));
    }
}
