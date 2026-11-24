package com.kshres31.inventory.migration;

public class MigrationException extends RuntimeException {
    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
