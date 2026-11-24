package com.kshres31.inventory.security;

import com.kshres31.inventory.model.AppUser;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface CredentialRepository {
    Optional<StoredCredential> find(long userId);

    /** Stores a new hash, clears lockout, revokes the user's sessions and audits, atomically. */
    void setPassword(long userId, String passwordHash, Long actorUserId);

    /** Stores the first credential only if no user has one yet. Returns false otherwise. */
    boolean setFirstPassword(long userId, String passwordHash);

    /** Atomically counts a failure and locks once {@code maxAttempts} is reached. */
    void recordFailure(long userId, int maxAttempts, Duration lockDuration);

    void recordSuccess(long userId);

    OffsetDateTime createSession(String tokenHash, long userId, Duration ttl);

    /** The active user owning a live session, refreshing its last-used time. */
    Optional<AppUser> findSessionUser(String tokenHash);

    boolean revokeSession(String tokenHash);
}
