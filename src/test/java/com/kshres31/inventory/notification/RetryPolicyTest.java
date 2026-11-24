package com.kshres31.inventory.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {
    @Test
    void doublesDelayUntilCapThenStops() {
        var policy = new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(2));

        assertEquals(Duration.ofSeconds(30), policy.delayAfterFailedAttempt(1));
        assertEquals(Duration.ofSeconds(60), policy.delayAfterFailedAttempt(2));
        assertEquals(Duration.ofSeconds(120), policy.delayAfterFailedAttempt(3));
        assertEquals(Duration.ofSeconds(120), policy.delayAfterFailedAttempt(4), "capped at maxDelay");
        assertNull(policy.delayAfterFailedAttempt(5), "no attempts remain");
    }

    @Test
    void largeAttemptCountsDoNotOverflow() {
        var policy = new RetryPolicy(1000, Duration.ofSeconds(1), Duration.ofHours(1));

        assertEquals(Duration.ofHours(1), policy.delayAfterFailedAttempt(500));
    }

    @Test
    void rejectsInvalidPolicies() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(1)));
    }
}
