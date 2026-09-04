package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.AppUserDraft;
import com.kshres31.inventory.model.Role;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcUserRepository implements UserRepository {
    private final ConnectionFactory connections;

    public JdbcUserRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public AppUser create(AppUserDraft draft) {
        String sql = """
                INSERT INTO app_user (username, display_name, email, role)
                VALUES (?, ?, ?, ?)
                RETURNING id, username, display_name, email, role, active
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, draft.username());
            statement.setString(2, draft.displayName());
            statement.setString(3, draft.email());
            statement.setString(4, draft.role().name());
            try (var result = statement.executeQuery()) {
                result.next();
                return mapUser(result);
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not create user '" + draft.username() + "'", error);
        }
    }

    @Override
    public Optional<AppUser> findByUsername(String username) {
        String sql = """
                SELECT id, username, display_name, email, role, active
                FROM app_user
                WHERE username = ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUser(result));
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load user '" + username + "'", error);
        }
    }

    @Override
    public List<AppUser> list(boolean includeInactive) {
        String sql = """
                SELECT id, username, display_name, email, role, active
                FROM app_user
                WHERE ? OR active
                ORDER BY username
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, includeInactive);
            try (var result = statement.executeQuery()) {
                var users = new ArrayList<AppUser>();
                while (result.next()) {
                    users.add(mapUser(result));
                }
                return users;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not list users", error);
        }
    }

    @Override
    public boolean deactivate(long userId) {
        String sql = "UPDATE app_user SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND active";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not deactivate user " + userId, error);
        }
    }

    private AppUser mapUser(ResultSet result) throws SQLException {
        return new AppUser(
                result.getLong("id"),
                result.getString("username"),
                result.getString("display_name"),
                result.getString("email"),
                Role.valueOf(result.getString("role")),
                result.getBoolean("active")
        );
    }
}
