package com.kshres31.inventory.security;

import com.kshres31.inventory.model.AppUser;

import java.time.OffsetDateTime;

/** A newly issued session. {@code token} is shown once and never stored server-side. */
public record Session(String token, AppUser user, OffsetDateTime expiresAt) {
    @Override
    public String toString() {
        return "Session[user=" + user.username() + ", expiresAt=" + expiresAt + "]";
    }
}
