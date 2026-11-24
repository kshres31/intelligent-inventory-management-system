package com.kshres31.inventory.security;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.model.Role;
import com.kshres31.inventory.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Password login, lockout, and server-side sessions. Every login failure, whether the user is
 * unknown, inactive, locked, or used a wrong password, produces the same message.
 */
public final class AuthenticationService {
    static final String INVALID_LOGIN = "Invalid username or password";
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final PasswordHasher hasher;
    private final AuthPolicy policy;
    private final SecureRandom random = new SecureRandom();

    public AuthenticationService(UserRepository users, CredentialRepository credentials,
                                 PasswordHasher hasher, AuthPolicy policy) {
        this.users = users;
        this.credentials = credentials;
        this.hasher = hasher;
        this.policy = policy;
    }

    public Session login(String username, char[] password) {
        try {
            var user = username == null ? null : users.findByUsername(username.strip()).orElse(null);
            var credential = user == null ? null : credentials.find(user.id()).orElse(null);
            if (user == null || credential == null) {
                hasher.burnVerification(password);
                throw new AuthenticationException(INVALID_LOGIN);
            }
            if (credential.locked()) {
                hasher.burnVerification(password);
                throw new AuthenticationException(INVALID_LOGIN);
            }
            if (!hasher.verify(password, credential.passwordHash())) {
                credentials.recordFailure(user.id(), policy.maxFailedAttempts(), policy.lockDuration());
                throw new AuthenticationException(INVALID_LOGIN);
            }
            if (!user.active()) {
                throw new AuthenticationException(INVALID_LOGIN);
            }
            credentials.recordSuccess(user.id());
            String token = newToken();
            var expiresAt = credentials.createSession(digest(token), user.id(), policy.sessionTtl());
            return new Session(token, user, expiresAt);
        } finally {
            PasswordHasher.clear(password);
        }
    }

    /** Resolves a bearer token to its active user, or fails. */
    public AppUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("Not logged in");
        }
        return credentials.findSessionUser(digest(token.strip()))
                .orElseThrow(() -> new AuthenticationException("Session is invalid or has expired; log in again"));
    }

    public boolean logout(String token) {
        return token != null && !token.isBlank() && credentials.revokeSession(digest(token.strip()));
    }

    /** A user may change their own password; administrators may set anyone's. */
    public void setPassword(AppUser actor, String targetUsername, char[] newPassword) {
        try {
            if (actor == null || !actor.active()) {
                throw new AuthorizationException("An active user is required");
            }
            var target = users.findByUsername(targetUsername)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + targetUsername));
            if (target.id() != actor.id() && !actor.role().allows(Permission.MANAGE_USERS)) {
                throw new AuthorizationException("Only administrators can set another user's password");
            }
            credentials.setPassword(target.id(), hasher.hash(newPassword), actor.id());
        } finally {
            PasswordHasher.clear(newPassword);
        }
    }

    /**
     * Gives the first administrator a password on a fresh installation. Refused as soon as any
     * user has a credential, so it cannot be used to take over a configured system.
     */
    public void bootstrapAdmin(String username, char[] password) {
        try {
            var user = users.findByUsername(username)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
            if (user.role() != Role.ADMIN || !user.active()) {
                throw new AuthorizationException("Bootstrap requires an active ADMIN user");
            }
            if (!credentials.setFirstPassword(user.id(), hasher.hash(password))) {
                throw new AuthorizationException("Bootstrap is only allowed before any password exists");
            }
        } finally {
            PasswordHasher.clear(password);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digest(String token) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
