package com.kshres31.inventory.migration;

import com.kshres31.inventory.database.DatabaseTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationRunnerIntegrationTest {
    private static final Path REAL_MIGRATIONS = Path.of("database");
    private static final AtomicInteger SCRATCH_SEQUENCE = new AtomicInteger();

    private DatabaseTestSupport server;

    @BeforeAll
    void startServer() throws Exception {
        server = DatabaseTestSupport.start();
    }

    @AfterAll
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void appliesRealMigrationsOnceAndReportsStatus() throws SQLException {
        DataSource database = scratch();
        var runner = runner(database, REAL_MIGRATIONS);
        int files = runner.discover().size();

        assertEquals(files, runner.migrate().size());
        assertEquals(List.of(), runner.migrate(), "second run must be a no-op");
        assertTrue(runner.status().stream().allMatch(MigrationStatus::applied));
        assertEquals(files, count(database, "SELECT count(*) FROM schema_migration WHERE NOT baseline"));
        assertEquals(1, count(database, "SELECT count(*) FROM app_user WHERE username = 'admin.demo'"),
                "seed must not be inserted twice");
    }

    @Test
    void failedMigrationRollsBackAndIsRetriedAfterFix(@TempDir Path directory) throws Exception {
        DataSource database = scratch();
        write(directory, "001_create_bin.sql", "BEGIN;\nCREATE TABLE bin (id INT PRIMARY KEY);\nCOMMIT;\n");
        write(directory, "002_add_label.sql",
                "BEGIN;\nCREATE TABLE label (id INT);\nALTER TABLE missing_table ADD COLUMN x INT;\nCOMMIT;\n");
        var runner = runner(database, directory);

        var error = assertThrows(MigrationException.class, runner::migrate);
        assertTrue(error.getMessage().contains("002_add_label.sql"), error.getMessage());
        assertEquals(1, count(database, "SELECT count(*) FROM schema_migration"));
        assertFalse(exists(database, "label"), "partial DDL from the failed file must roll back");

        write(directory, "002_add_label.sql", "BEGIN;\nCREATE TABLE label (id INT);\nCOMMIT;\n");
        assertEquals(1, runner.migrate().size());
        assertTrue(exists(database, "label"));
    }

    @Test
    void refusesEditedAppliedFilesAndOutOfOrderAdditions(@TempDir Path directory) throws Exception {
        DataSource database = scratch();
        write(directory, "001_a.sql", "CREATE TABLE a (id INT);");
        write(directory, "003_c.sql", "CREATE TABLE c (id INT);");
        var runner = runner(database, directory);
        runner.migrate();

        write(directory, "002_b.sql", "CREATE TABLE b (id INT);");
        var outOfOrder = assertThrows(MigrationException.class, runner::migrate);
        assertTrue(outOfOrder.getMessage().contains("out of order"), outOfOrder.getMessage());
        Files.delete(directory.resolve("002_b.sql"));

        write(directory, "001_a.sql", "CREATE TABLE a (id BIGINT);");
        var edited = assertThrows(MigrationException.class, runner::migrate);
        assertTrue(edited.getMessage().contains("changed after it was applied"), edited.getMessage());

        write(directory, "001_a.sql", "CREATE TABLE a (id INT);");
        Files.delete(directory.resolve("003_c.sql"));
        var missing = assertThrows(MigrationException.class, runner::migrate);
        assertTrue(missing.getMessage().contains("no matching file"), missing.getMessage());
    }

    @Test
    void rejectsDuplicateVersions(@TempDir Path directory) throws Exception {
        write(directory, "001_a.sql", "SELECT 1;");
        write(directory, "001_b.sql", "SELECT 1;");

        assertThrows(MigrationException.class, () -> runner(scratch(), directory).discover());
    }

    @Test
    void concurrentRunnersApplyEachMigrationExactlyOnce() throws Exception {
        DataSource database = scratch();
        int runners = 3;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(runners);
        var appliedCounts = new ArrayList<Integer>();
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < runners; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return runner(database, REAL_MIGRATIONS).migrate().size();
                }));
            }
            start.countDown();
            for (var future : futures) {
                appliedCounts.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        int files = runner(database, REAL_MIGRATIONS).discover().size();
        assertEquals(files, appliedCounts.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, appliedCounts.stream().filter(n -> n == files).count(), "one runner does all the work");
        assertEquals(files, count(database, "SELECT count(*) FROM schema_migration"));
        assertEquals(3, count(database, "SELECT count(*) FROM app_user"));
    }

    @Test
    void baselineAdoptsDatabaseCreatedByInitScripts(@TempDir Path directory) throws Exception {
        DataSource database = scratch();
        write(directory, "001_a.sql", "BEGIN;\nCREATE TABLE a (id INT);\nCOMMIT;\n");
        write(directory, "002_b.sql", "BEGIN;\nCREATE TABLE b (id INT);\nCOMMIT;\n");
        execute(database, "CREATE TABLE a (id INT)");
        var runner = runner(database, directory);

        assertThrows(MigrationException.class, () -> runner.baseline(9), "unknown version");
        assertEquals(1, runner.baseline(1).size());
        assertThrows(MigrationException.class, () -> runner.baseline(1), "baseline only once");
        assertEquals(1, count(database, "SELECT count(*) FROM schema_migration WHERE baseline"));

        var applied = runner.migrate();
        assertEquals(List.of("002_b.sql"), applied.stream().map(Migration::fileName).toList());
        assertTrue(exists(database, "b"));
    }

    private DataSource scratch() throws SQLException {
        return server.createScratchDatabase("migration_test_" + SCRATCH_SEQUENCE.incrementAndGet());
    }

    private static MigrationRunner runner(DataSource database, Path directory) {
        return new MigrationRunner(database::getConnection, directory);
    }

    private static void write(Path directory, String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content);
    }

    private static boolean exists(DataSource database, String table) throws SQLException {
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static long count(DataSource database, String sql) throws SQLException {
        try (var connection = database.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(DataSource database, String sql) throws SQLException {
        try (var connection = database.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
