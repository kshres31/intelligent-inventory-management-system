package com.kshres31.inventory.migration;

import com.kshres31.inventory.config.ConnectionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies numbered SQL files in order and records each one in {@code schema_migration}.
 *
 * <ul>
 *   <li>A session advisory lock serializes concurrent runners, so two deployments starting
 *       together cannot apply the same file twice.</li>
 *   <li>Each file runs in its own transaction with its version record.</li>
 *   <li>A SHA-256 checksum detects edits to files that were already applied.</li>
 *   <li>A missing version below the newest applied one is refused rather than run out of order.</li>
 * </ul>
 */
public final class MigrationRunner {
    /** Arbitrary application-wide key for pg_advisory_lock. */
    static final long LOCK_KEY = 4_815_162_342L;

    private static final String CREATE_HISTORY_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_migration (
                version INTEGER PRIMARY KEY CHECK (version > 0),
                description VARCHAR(200) NOT NULL,
                checksum CHAR(64) NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                applied_by TEXT NOT NULL DEFAULT current_user,
                execution_ms BIGINT NOT NULL CHECK (execution_ms >= 0),
                baseline BOOLEAN NOT NULL DEFAULT FALSE
            )
            """;

    private final ConnectionFactory connections;
    private final Path directory;

    public MigrationRunner(ConnectionFactory connections, Path directory) {
        this.connections = connections;
        this.directory = directory;
    }

    public List<Migration> discover() {
        if (!Files.isDirectory(directory)) {
            throw new MigrationException("Migration directory does not exist: " + directory.toAbsolutePath());
        }
        var migrations = new ArrayList<Migration>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                String name = file.getFileName().toString();
                migrations.add(Migration.parse(name, Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException error) {
            throw new MigrationException("Could not read migrations from " + directory, error);
        }
        migrations.sort(Comparator.comparingInt(Migration::version));
        for (int i = 1; i < migrations.size(); i++) {
            if (migrations.get(i).version() == migrations.get(i - 1).version()) {
                throw new MigrationException("Duplicate migration version " + migrations.get(i).version() + ": "
                        + migrations.get(i - 1).fileName() + " and " + migrations.get(i).fileName());
            }
        }
        return migrations;
    }

    /** Applies every pending migration and returns the ones applied by this call. */
    public List<Migration> migrate() {
        var migrations = discover();
        return withLock(connection -> {
            var applied = appliedChecksums(connection);
            verify(migrations, applied);
            int newestApplied = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            var newlyApplied = new ArrayList<Migration>();
            for (Migration migration : migrations) {
                if (applied.containsKey(migration.version())) {
                    continue;
                }
                if (migration.version() < newestApplied) {
                    throw new MigrationException(migration.fileName() + " is older than applied version "
                            + newestApplied + "; renumber it instead of applying out of order");
                }
                try {
                    apply(connection, migration);
                } catch (MigrationException error) {
                    if (applied.isEmpty() && newlyApplied.isEmpty()) {
                        throw new MigrationException(error.getMessage() + ". If this database was created "
                                + "before version tracking (Docker init scripts or psql), run 'baseline <version>' once.",
                                error.getCause());
                    }
                    throw error;
                }
                newlyApplied.add(migration);
            }
            return newlyApplied;
        });
    }

    /**
     * Records migrations up to {@code version} as applied without running them. Use once for a
     * database created before version tracking existed (for example by Docker init scripts).
     */
    public List<Migration> baseline(int version) {
        var migrations = discover();
        if (migrations.stream().noneMatch(migration -> migration.version() == version)) {
            throw new MigrationException("No migration file has version " + version);
        }
        return withLock(connection -> {
            if (!appliedChecksums(connection).isEmpty()) {
                throw new MigrationException("Baseline is only allowed before any migration is recorded");
            }
            var recorded = new ArrayList<Migration>();
            connection.setAutoCommit(false);
            try {
                for (Migration migration : migrations) {
                    if (migration.version() > version) {
                        break;
                    }
                    record(connection, migration, 0, true);
                    recorded.add(migration);
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
            return recorded;
        });
    }

    public List<MigrationStatus> status() {
        var migrations = discover();
        try (var connection = connections.open()) {
            var appliedAt = new HashMap<Integer, OffsetDateTime>();
            if (historyTableExists(connection)) {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT version, applied_at FROM schema_migration")) {
                    while (result.next()) {
                        appliedAt.put(result.getInt(1), result.getObject(2, OffsetDateTime.class));
                    }
                }
            }
            return migrations.stream()
                    .map(migration -> new MigrationStatus(migration, appliedAt.get(migration.version())))
                    .toList();
        } catch (SQLException error) {
            throw new MigrationException("Could not read migration status", error);
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        long started = System.nanoTime();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute(migration.sql());
            record(connection, migration, (System.nanoTime() - started) / 1_000_000, false);
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw new MigrationException("Migration " + migration.fileName() + " failed and was rolled back: "
                    + error.getMessage(), error);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void record(Connection connection, Migration migration, long executionMs, boolean baseline)
            throws SQLException {
        String sql = """
                INSERT INTO schema_migration (version, description, checksum, execution_ms, baseline)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, migration.checksum());
            statement.setLong(4, executionMs);
            statement.setBoolean(5, baseline);
            statement.executeUpdate();
        }
    }

    private void verify(List<Migration> migrations, Map<Integer, String> applied) {
        var byVersion = new HashMap<Integer, Migration>();
        migrations.forEach(migration -> byVersion.put(migration.version(), migration));
        for (var entry : applied.entrySet()) {
            Migration file = byVersion.get(entry.getKey());
            if (file == null) {
                throw new MigrationException("Database has version " + entry.getKey()
                        + " but no matching file exists in " + directory);
            }
            if (!file.checksum().equals(entry.getValue())) {
                throw new MigrationException(file.fileName() + " changed after it was applied; "
                        + "add a new migration instead of editing an applied one");
            }
        }
    }

    private Map<Integer, String> appliedChecksums(Connection connection) throws SQLException {
        var applied = new HashMap<Integer, String>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT version, checksum FROM schema_migration")) {
            while (result.next()) {
                applied.put(result.getInt(1), result.getString(2));
            }
        }
        return applied;
    }

    private boolean historyTableExists(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT to_regclass('schema_migration') IS NOT NULL")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private <T> T withLock(LockedWork<T> work) {
        try (var connection = connections.open()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute(CREATE_HISTORY_TABLE);
                }
                return work.run(connection);
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, LOCK_KEY);
                    statement.execute();
                }
            }
        } catch (SQLException error) {
            throw new MigrationException("Migration failed: " + error.getMessage(), error);
        }
    }

    @FunctionalInterface
    private interface LockedWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
