package com.kshres31.inventory.security;

/** Deliberately vague: the message never reveals whether the username exists. */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
