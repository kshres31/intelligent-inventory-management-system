package com.kshres31.inventory.security;

import com.kshres31.inventory.config.ConnectionFactory;
import com.kshres31.inventory.exception.DataAccessException;
import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.model.Role;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

public final class JdbcCredentialRepository implements CredentialRepository {
    private final ConnectionFactory connections;

    public JdbcCredentialRepository(ConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public Optional<StoredCredential> find(long userId) {
        String sql = """
                SELECT user_id, password_hash, failed_attempts,
                       COALESCE(locked_until > CURRENT_TIMESTAMP, FALSE) AS locked
                FROM user_credential WHERE user_id = ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredCredential(result.getLong(1), result.getString(2),
                        result.getInt(3), result.getBoolean(4)));
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not load credential", error);
        }
    }

    @Override
    public void setPassword(long userId, String passwordHash, Long actorUserId) {
        inTransaction(connection -> {
            upsert(connection, userId, passwordHash);
            try (var statement = connection.prepareStatement("""
                    UPDATE user_session SET revoked_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND revoked_at IS NULL
                    """)) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO audit_log (entity_type, entity_key, action, new_values, actor_user_id)
                    VALUES ('user_credential', ?, 'UPDATE', jsonb_build_object('passwordChanged', TRUE), ?)
                    """)) {
                statement.setString(1, Long.toString(userId));
                if (actorUserId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(2, actorUserId);
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean setFirstPassword(long userId, String passwordHash) {
        return inTransaction(connection -> {
            // EXCLUSIVE blocks concurrent bootstraps (and writes) until this transaction ends,
            // so two simultaneous bootstrap attempts cannot both see an empty table.
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE user_credential IN EXCLUSIVE MODE");
                try (var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM user_credential)")) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return false;
                    }
                }
            }
            upsert(connection, userId, passwordHash);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO audit_log (entity_type, entity_key, action, new_values, actor_user_id)
                    VALUES ('user_credential', ?, 'INSERT', jsonb_build_object('bootstrap', TRUE), ?)
                    """)) {
                statement.setString(1, Long.toString(userId));
                statement.setLong(2, userId);
                statement.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public void recordFailure(long userId, int maxAttempts, Duration lockDuration) {
        String sql = """
                UPDATE user_credential
                SET failed_attempts = failed_attempts + 1,
                    locked_until = CASE WHEN failed_attempts + 1 >= ?
                                        THEN CURRENT_TIMESTAMP + ?::interval
                                        ELSE locked_until END
                WHERE user_id = ?
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxAttempts);
            statement.setString(2, lockDuration.toMillis() + " milliseconds");
            statement.setLong(3, userId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DataAccessException("Could not record failed login", error);
        }
    }

    @Override
    public void recordSuccess(long userId) {
        String sql = "UPDATE user_credential SET failed_attempts = 0, locked_until = NULL WHERE user_id = ?";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DataAccessException("Could not record login", error);
        }
    }

    @Override
    public OffsetDateTime createSession(String tokenHash, long userId, Duration ttl) {
        String sql = """
                INSERT INTO user_session (token_hash, user_id, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + ?::interval)
                RETURNING expires_at
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            statement.setLong(2, userId);
            statement.setString(3, ttl.toMillis() + " milliseconds");
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, OffsetDateTime.class);
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not create session", error);
        }
    }

    @Override
    public Optional<AppUser> findSessionUser(String tokenHash) {
        String sql = """
                UPDATE user_session s
                SET last_used_at = CURRENT_TIMESTAMP
                FROM app_user u
                WHERE s.token_hash = ? AND u.id = s.user_id
                  AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP AND u.active
                RETURNING u.id, u.username, u.display_name, u.email, u.role, u.active
                """;
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AppUser(result.getLong("id"), result.getString("username"),
                        result.getString("display_name"), result.getString("email"),
                        Role.valueOf(result.getString("role")), result.getBoolean("active")));
            }
        } catch (SQLException error) {
            throw new DataAccessException("Could not resolve session", error);
        }
    }

    @Override
    public boolean revokeSession(String tokenHash) {
        String sql = "UPDATE user_session SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ? AND revoked_at IS NULL";
        try (var connection = connections.open();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new DataAccessException("Could not revoke session", error);
        }
    }

    private static void upsert(Connection connection, long userId, String passwordHash) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO user_credential (user_id, password_hash)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash, failed_attempts = 0,
                    locked_until = NULL, password_changed_at = CURRENT_TIMESTAMP
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, passwordHash);
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new DataAccessException("Credential update failed", error);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
