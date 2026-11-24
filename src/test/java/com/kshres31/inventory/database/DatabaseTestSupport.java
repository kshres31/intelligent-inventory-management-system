package com.kshres31.inventory.database;

import com.kshres31.inventory.migration.MigrationRunner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;

public final class DatabaseTestSupport implements AutoCloseable {
    private static final Path MIGRATIONS = Path.of("database");

    private final EmbeddedPostgres postgres;
    private final DataSource dataSource;
    private final String externalUrl;

    private DatabaseTestSupport(EmbeddedPostgres postgres, DataSource dataSource, String externalUrl) {
        this.postgres = postgres;
        this.dataSource = dataSource;
        this.externalUrl = externalUrl;
    }

    public static DatabaseTestSupport start() throws IOException, SQLException {
        String externalUrl = System.getProperty("test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            requireDedicatedTestDatabase(externalUrl);
            var externalDataSource = new PGSimpleDataSource();
            externalDataSource.setUrl(externalUrl);
            externalDataSource.setUser(System.getProperty("test.database.user", "postgres"));
            externalDataSource.setPassword(System.getProperty("test.database.password", ""));
            var database = new DatabaseTestSupport(null, externalDataSource, externalUrl);
            database.resetPublicSchema();
            database.applyMigrations();
            return database;
        }

        var postgres = EmbeddedPostgres.builder()
                .setPGStartupWait(Duration.ofSeconds(30))
                .setErrorRedirector(ProcessBuilder.Redirect.INHERIT)
                .start();
        var database = new DatabaseTestSupport(postgres, postgres.getPostgresDatabase(), null);
        database.applyMigrations();
        return database;
    }

    private static void requireDedicatedTestDatabase(String url) {
        String withoutQuery = url.split("\\?", 2)[0];
        String databaseName = withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
        if (!databaseName.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalArgumentException(
                    "External integration tests require a disposable database whose name contains 'test'"
            );
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    private void resetPublicSchema() throws SQLException {
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    /** Applies the real migration files through the same runner the application uses. */
    private void applyMigrations() {
        new MigrationRunner(() -> dataSource().getConnection(), MIGRATIONS).migrate();
    }

    /**
     * Creates an empty database on the same server for tests that need a schema-less start.
     * The name always contains "test", so the external-database safety rule still holds.
     */
    public DataSource createScratchDatabase(String name) throws SQLException {
        if (!name.matches("[a-z0-9_]+") || !name.contains("test")) {
            throw new IllegalArgumentException("Scratch database names must be lower-case and contain 'test'");
        }
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        }
        if (postgres != null) {
            return postgres.getDatabase("postgres", name);
        }
        var scratch = new PGSimpleDataSource();
        String base = externalUrl.split("\\?", 2)[0];
        scratch.setUrl(base.substring(0, base.lastIndexOf('/') + 1) + name);
        scratch.setUser(System.getProperty("test.database.user", "postgres"));
        scratch.setPassword(System.getProperty("test.database.password", ""));
        return scratch;
    }

    @Override
    public void close() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }
}
