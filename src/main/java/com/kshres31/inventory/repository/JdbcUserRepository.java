package com.kshres31.inventory.repository;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.Role;

import java.sql.SQLException;
import java.util.Optional;

public final class JdbcUserRepository implements UserRepository {
    private final ConnectionFactory connections;

    public JdbcUserRepository(ConnectionFactory connections) {
        this.connections = connections;
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
                return Optional.of(new AppUser(
                        result.getLong("id"),
                        result.getString("username"),
                        result.getString("display_name"),
                        result.getString("email"),
                        Role.valueOf(result.getString("role")),
                        result.getBoolean("active")
                ));
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load user '" + username + "'", error);
        }
    }
}
