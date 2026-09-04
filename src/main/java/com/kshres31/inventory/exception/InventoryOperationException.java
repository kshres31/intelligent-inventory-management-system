package com.kshres31.inventory.exception;

public final class InventoryOperationException extends RuntimeException {
    public InventoryOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
