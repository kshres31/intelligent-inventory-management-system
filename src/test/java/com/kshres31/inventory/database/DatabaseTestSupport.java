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

final class DatabaseTestSupport implements AutoCloseable {
    private static final List<Path> MIGRATIONS = List.of(
            Path.of("database", "001_schema.sql"),
            Path.of("database", "002_functions.sql"),
            Path.of("database", "003_triggers.sql"),
            Path.of("database", "004_seed.sql")
    );

    private final EmbeddedPostgres postgres;
    private final DataSource dataSource;

    private DatabaseTestSupport(EmbeddedPostgres postgres, DataSource dataSource) {
        this.postgres = postgres;
        this.dataSource = dataSource;
    }

    static DatabaseTestSupport start() throws IOException, SQLException {
        String externalUrl = System.getProperty("test.database.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
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

    DataSource dataSource() {
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
