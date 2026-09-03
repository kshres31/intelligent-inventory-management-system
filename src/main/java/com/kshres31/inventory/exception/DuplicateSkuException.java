package com.kshres31.inventory.exception;

public final class DuplicateSkuException extends DataAccessException {
    public DuplicateSkuException(String sku, Throwable cause) {
        super("A product with SKU '" + sku + "' already exists", cause);
    }
}
