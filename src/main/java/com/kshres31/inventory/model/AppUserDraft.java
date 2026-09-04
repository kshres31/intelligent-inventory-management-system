package com.kshres31.inventory.model;

public record AppUserDraft(
        String username,
        String displayName,
        String email,
        Role role
) {
    public AppUserDraft {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        username = username.strip();
        displayName = displayName.strip();
        email = email == null || email.isBlank() ? null : email.strip();
    }
}
