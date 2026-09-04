package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.AppUser;

import java.util.Optional;

public interface UserRepository {
    Optional<AppUser> findByUsername(String username);
}
