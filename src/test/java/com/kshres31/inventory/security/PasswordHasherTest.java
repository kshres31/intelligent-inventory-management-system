package com.kshres31.inventory.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher(10_000);

    @Test
    void verifiesTheRightPasswordOnly() {
        String encoded = hasher.hash("correct horse battery".toCharArray());

        assertTrue(encoded.startsWith("pbkdf2-sha256$10000$"));
        assertFalse(encoded.contains("correct horse"));
        assertTrue(hasher.verify("correct horse battery".toCharArray(), encoded));
        assertFalse(hasher.verify("correct horse batterY".toCharArray(), encoded));
        assertFalse(hasher.verify(new char[0], encoded));
    }

    @Test
    void saltsEveryHash() {
        char[] password = "same password twice".toCharArray();

        assertNotEquals(hasher.hash(password.clone()), hasher.hash(password.clone()));
    }

    @Test
    void storedIterationCountIsHonouredAfterTheDefaultChanges() {
        String old = new PasswordHasher(5_000).hash("rotated work factor".toCharArray());

        assertTrue(hasher.verify("rotated work factor".toCharArray(), old));
    }

    @Test
    void malformedStoredValuesFailClosed() {
        assertFalse(hasher.verify("anything at all".toCharArray(), null));
        assertFalse(hasher.verify("anything at all".toCharArray(), "plain-text"));
        assertFalse(hasher.verify("anything at all".toCharArray(), "pbkdf2-sha256$x$abc$def"));
        assertFalse(hasher.verify("anything at all".toCharArray(), "pbkdf2-sha256$10$AAAA$AAAA"));
        assertFalse(hasher.verify("anything at all".toCharArray(), "pbkdf2-sha256$10000$!!$??"));
    }

    @Test
    void enforcesPasswordPolicy() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("              ".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("x".repeat(129).toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(10));
    }
}
