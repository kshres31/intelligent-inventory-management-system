package com.kshres31.inventory.notification;

import java.time.Duration;

/**
 * Exponential backoff: attempt n (1-based) that fails waits base * 2^(n-1), capped at
 * {@code maxDelay}. After {@code maxAttempts} failures the notification is dead-lettered.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("Delays must be positive and maxDelay >= baseDelay");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(6, Duration.ofSeconds(30), Duration.ofHours(1));
    }

    /** Returns the wait before the next attempt, or null when no attempts remain. */
    public Duration delayAfterFailedAttempt(int attemptsSoFar) {
        if (attemptsSoFar >= maxAttempts) {
            return null;
        }
        int exponent = Math.min(Math.max(attemptsSoFar - 1, 0), 30);
        Duration delay = baseDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
