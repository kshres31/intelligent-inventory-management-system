package com.kshres31.inventory.api;

/** An error whose status code and message are safe to return to the client as-is. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
