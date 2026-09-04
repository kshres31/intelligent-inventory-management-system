package com.kshres31.inventory.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseTestSupportTest {
    @Test
    void refusesToResetDatabaseWithoutTestInItsName() {
        String previousUrl = System.getProperty("test.database.url");
        try {
            System.setProperty("test.database.url", "jdbc:postgresql://localhost:5432/inventory");
            assertThrows(IllegalArgumentException.class, DatabaseTestSupport::start);
        } finally {
            if (previousUrl == null) {
                System.clearProperty("test.database.url");
            } else {
                System.setProperty("test.database.url", previousUrl);
            }
        }
    }
}
