package com.kshres31.inventory.exception;

public final class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}
