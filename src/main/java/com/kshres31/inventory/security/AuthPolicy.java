package com.kshres31.inventory.security;

import java.time.Duration;

/**
 * @param maxFailedAttempts consecutive failures before the account is locked
 * @param lockDuration      how long a lock lasts
 * @param sessionTtl        lifetime of a session token
 */
public record AuthPolicy(int maxFailedAttempts, Duration lockDuration, Duration sessionTtl) {
    public AuthPolicy {
        if (maxFailedAttempts < 1 || lockDuration.isNegative() || lockDuration.isZero()
                || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("Authentication policy values must be positive");
        }
    }

    public static AuthPolicy defaults() {
        return new AuthPolicy(5, Duration.ofMinutes(15), Duration.ofHours(8));
    }
}
