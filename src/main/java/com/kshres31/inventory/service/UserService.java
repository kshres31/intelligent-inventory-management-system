package com.kshres31.inventory.service;

import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.AppUserDraft;
import com.kshres31.inventory.model.Permission;
import com.kshres31.inventory.repository.UserRepository;

import java.util.List;

public final class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public AppUser create(AppUser actor, AppUserDraft draft) {
        requireAdmin(actor);
        return users.create(draft);
    }

    public List<AppUser> list(AppUser actor, boolean includeInactive) {
        requireAdmin(actor);
        return users.list(includeInactive);
    }

    public boolean deactivate(AppUser actor, long userId) {
        requireAdmin(actor);
        if (actor.id() == userId) {
            throw new IllegalArgumentException("Administrators cannot deactivate their own current account");
        }
        return users.deactivate(userId);
    }

    private void requireAdmin(AppUser actor) {
        if (actor == null || !actor.active() || !actor.role().allows(Permission.MANAGE_USERS)) {
            throw new AuthorizationException("Only an active administrator can manage users");
        }
    }
}
