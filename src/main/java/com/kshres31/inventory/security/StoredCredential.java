package com.kshres31.inventory.security;

public record StoredCredential(long userId, String passwordHash, int failedAttempts, boolean locked) {
}
