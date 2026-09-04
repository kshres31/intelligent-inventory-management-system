package com.kshres31.inventory.repository;

import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.AppUserDraft;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    AppUser create(AppUserDraft draft);

    Optional<AppUser> findByUsername(String username);

    List<AppUser> list(boolean includeInactive);

    boolean deactivate(long userId);
}
