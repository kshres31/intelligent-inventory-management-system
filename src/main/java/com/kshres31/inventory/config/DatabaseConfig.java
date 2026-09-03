package com.kshres31.inventory.config;

import java.util.Map;

public record DatabaseConfig(String url, String user, String password) {
    public DatabaseConfig {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Database URL is required");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("Database user is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Database password is required");
        }
    }

    public static DatabaseConfig fromEnvironment(Map<String, String> environment) {
        return new DatabaseConfig(
                environment.get("INVENTORY_DB_URL"),
                environment.get("INVENTORY_DB_USER"),
                environment.get("INVENTORY_DB_PASSWORD")
        );
    }
}
