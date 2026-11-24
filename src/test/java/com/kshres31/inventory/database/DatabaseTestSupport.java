package com.kshres31.inventory.database;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public final class DatabaseTestSupport implements AutoCloseable {
    private static final List<Path> MIGRATIONS = List.of(
            Path.of("database", "001_schema.sql"),
            Path.of("database", "002_functions.sql"),
            Path.of("database", "003_triggers.sql"),
            Path.of("database", "004_seed.sql"),
            Path.of("database", "005_purchase_order_receipts.sql"),
            Path.of("database", "006_alert_notifications.sql")
    );

    private final EmbeddedPostgres postgres;
    private final DataSource dataSource;

    private DatabaseTestSupport(EmbeddedPostgres postgres, DataSource dataSource) {
        this.postgres = postgres;
        this.dataSource = dataSource;
    }

    public static DatabaseTestSupport start() throws IOException, SQLException {
        String externalUrl = System.getProperty("test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            requireDedicatedTestDatabase(externalUrl);
            var externalDataSource = new PGSimpleDataSource();
            externalDataSource.setUrl(externalUrl);
            externalDataSource.setUser(System.getProperty("test.database.user", "postgres"));
            externalDataSource.setPassword(System.getProperty("test.database.password", ""));
            var database = new DatabaseTestSupport(null, externalDataSource);
            database.resetPublicSchema();
            database.applyMigrations();
            return database;
        }

        var postgres = EmbeddedPostgres.builder()
                .setPGStartupWait(Duration.ofSeconds(30))
                .setErrorRedirector(ProcessBuilder.Redirect.INHERIT)
                .start();
        var database = new DatabaseTestSupport(postgres, postgres.getPostgresDatabase());
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

    private void applyMigrations() throws IOException, SQLException {
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            for (Path migration : MIGRATIONS) {
                statement.execute(Files.readString(migration));
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }
}
