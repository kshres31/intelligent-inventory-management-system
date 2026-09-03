package com.kshres31.inventory.model;

public record AppUser(
        long id,
        String username,
        String displayName,
        String email,
        Role role,
        boolean active
) {
}
