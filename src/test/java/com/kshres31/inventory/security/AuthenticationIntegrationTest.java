package com.kshres31.inventory.security;

import com.kshres31.inventory.controller.AuthCommand;
import com.kshres31.inventory.database.DatabaseTestSupport;
import com.kshres31.inventory.exception.AuthorizationException;
import com.kshres31.inventory.repository.JdbcUserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationIntegrationTest {
    private static final String ADMIN_PASSWORD = "admin password 123";
    private static final String MANAGER_PASSWORD = "manager password 123";

    private DatabaseTestSupport database;
    private JdbcUserRepository users;
    private AuthenticationService auth;

    @BeforeAll
    void startDatabase() throws Exception {
        database = DatabaseTestSupport.start();
        users = new JdbcUserRepository(() -> database.dataSource().getConnection());
        auth = new AuthenticationService(users, new JdbcCredentialRepository(() -> database.dataSource().getConnection()),
                new PasswordHasher(10_000), new AuthPolicy(3, Duration.ofMinutes(15), Duration.ofHours(1)));
    }

    @AfterAll
    void stopDatabase() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void resetCredentials() throws SQLException {
        execute("DELETE FROM user_session");
        execute("DELETE FROM user_credential");
        execute("DELETE FROM audit_log");
        execute("UPDATE app_user SET active = TRUE");
    }

    @Test
    void bootstrapWorksOnceThenLoginIssuesAHashedSessionToken() throws SQLException {
        assertThrows(AuthorizationException.class, () -> auth.bootstrapAdmin("manager.demo", pw(MANAGER_PASSWORD)),
                "only an ADMIN may be bootstrapped");
        auth.bootstrapAdmin("admin.demo", pw(ADMIN_PASSWORD));
        assertThrows(AuthorizationException.class, () -> auth.bootstrapAdmin("admin.demo", pw("another password 1")));

        var session = auth.login("admin.demo", pw(ADMIN_PASSWORD));

        assertEquals("admin.demo", auth.authenticate(session.token()).username());
        assertTrue(session.token().length() >= 43);
        assertEquals(0L, count("SELECT count(*) FROM user_session WHERE token_hash = '" + session.token() + "'"),
                "raw token must never be stored");
        assertEquals(1L, count("SELECT count(*) FROM user_session WHERE token_hash = '"
                + AuthenticationService.digest(session.token()) + "'"));
        assertEquals(0L, count("SELECT count(*) FROM user_credential WHERE password_hash LIKE '%" + ADMIN_PASSWORD + "%'"));
        assertFalse(session.toString().contains(session.token()));
    }

    @Test
    void concurrentBootstrapAttemptsLetExactlyOneSucceed() throws Exception {
        execute("INSERT INTO app_user (username, display_name, role) VALUES ('second.admin', 'Second', 'ADMIN') "
                + "ON CONFLICT (username) DO NOTHING");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var outcomes = new ArrayList<Boolean>();
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (String username : List.of("admin.demo", "second.admin")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        auth.bootstrapAdmin(username, pw(ADMIN_PASSWORD));
                        return true;
                    } catch (AuthorizationException refused) {
                        return false;
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1L, count("SELECT count(*) FROM user_credential"));
    }

    @Test
    void failuresAreIndistinguishableAndLockTheAccount() throws SQLException {
        auth.bootstrapAdmin("admin.demo", pw(ADMIN_PASSWORD));

        var unknown = assertThrows(AuthenticationException.class, () -> auth.login("nobody", pw(ADMIN_PASSWORD)));
        var noCredential = assertThrows(AuthenticationException.class,
                () -> auth.login("employee.demo", pw(ADMIN_PASSWORD)));
        var wrong = assertThrows(AuthenticationException.class, () -> auth.login("admin.demo", pw("wrong password 1")));
        assertEquals(unknown.getMessage(), noCredential.getMessage());
        assertEquals(unknown.getMessage(), wrong.getMessage());

        assertThrows(AuthenticationException.class, () -> auth.login("admin.demo", pw("wrong password 2")));
        assertThrows(AuthenticationException.class, () -> auth.login("admin.demo", pw("wrong password 3")));
        var locked = assertThrows(AuthenticationException.class, () -> auth.login("admin.demo", pw(ADMIN_PASSWORD)),
                "correct password is refused while locked");
        assertEquals(unknown.getMessage(), locked.getMessage());

        execute("UPDATE user_credential SET locked_until = CURRENT_TIMESTAMP - interval '1 second'");
        auth.login("admin.demo", pw(ADMIN_PASSWORD));
        assertEquals(0L, count("SELECT failed_attempts FROM user_credential"), "success resets the counter");
    }

    @Test
    void sessionsExpireAreRevokedAndDieWithDeactivation() throws SQLException {
        auth.bootstrapAdmin("admin.demo", pw(ADMIN_PASSWORD));
        var admin = auth.login("admin.demo", pw(ADMIN_PASSWORD)).user();
        auth.setPassword(admin, "manager.demo", pw(MANAGER_PASSWORD));

        var expiring = auth.login("manager.demo", pw(MANAGER_PASSWORD));
        execute("UPDATE user_session SET created_at = CURRENT_TIMESTAMP - interval '2 hours', "
                + "expires_at = CURRENT_TIMESTAMP - interval '1 second' WHERE token_hash = '"
                + AuthenticationService.digest(expiring.token()) + "'");
        assertThrows(AuthenticationException.class, () -> auth.authenticate(expiring.token()));

        var revoked = auth.login("manager.demo", pw(MANAGER_PASSWORD));
        assertTrue(auth.logout(revoked.token()));
        assertFalse(auth.logout(revoked.token()));
        assertThrows(AuthenticationException.class, () -> auth.authenticate(revoked.token()));

        var deactivated = auth.login("manager.demo", pw(MANAGER_PASSWORD));
        execute("UPDATE app_user SET active = FALSE WHERE username = 'manager.demo'");
        assertThrows(AuthenticationException.class, () -> auth.authenticate(deactivated.token()));
        assertThrows(AuthenticationException.class, () -> auth.login("manager.demo", pw(MANAGER_PASSWORD)));
        assertThrows(AuthenticationException.class, () -> auth.authenticate("forged-token"));
        assertThrows(AuthenticationException.class, () -> auth.authenticate(null));
    }

    @Test
    void passwordChangesAreAuthorizedAuditedAndSignOutOldSessions() throws SQLException {
        auth.bootstrapAdmin("admin.demo", pw(ADMIN_PASSWORD));
        var admin = auth.login("admin.demo", pw(ADMIN_PASSWORD)).user();
        auth.setPassword(admin, "manager.demo", pw(MANAGER_PASSWORD));
        auth.setPassword(admin, "employee.demo", pw("employee password 1"));
        var manager = auth.login("manager.demo", pw(MANAGER_PASSWORD));
        var employee = auth.login("employee.demo", pw("employee password 1")).user();

        assertThrows(AuthorizationException.class,
                () -> auth.setPassword(employee, "manager.demo", pw("takeover attempt 1")));
        assertThrows(IllegalArgumentException.class, () -> auth.setPassword(employee, "employee.demo", pw("short")));

        auth.setPassword(manager.user(), "manager.demo", pw("rotated manager pw 1"));
        assertThrows(AuthenticationException.class, () -> auth.authenticate(manager.token()),
                "changing a password signs out existing sessions");
        assertThrows(AuthenticationException.class, () -> auth.login("manager.demo", pw(MANAGER_PASSWORD)));
        auth.login("manager.demo", pw("rotated manager pw 1"));
        assertEquals(4L, count("SELECT count(*) FROM audit_log WHERE entity_type = 'user_credential'"));
    }

    @Test
    void cliLoginStoresSessionAndResolvesTheActor(@TempDir Path directory) {
        auth.bootstrapAdmin("admin.demo", pw(ADMIN_PASSWORD));
        var store = new SessionStore(directory.resolve("session"));
        var output = new ByteArrayOutputStream();
        var answers = new ArrayDeque<>(List.of(ADMIN_PASSWORD));
        var command = new AuthCommand(auth, store, () -> null, message -> answers.pop().toCharArray(),
                new PrintStream(output));

        command.run(new String[]{"login", "admin.demo"});
        assertTrue(store.read().isPresent());
        assertEquals("admin.demo", command.currentUser().username());
        String token = store.read().orElseThrow();
        assertFalse(output.toString().contains(token), "token must not be printed");

        command.run(new String[]{"logout"});
        assertTrue(store.read().isEmpty());
        assertThrows(AuthenticationException.class, command::currentUser);
        assertThrows(AuthenticationException.class, () -> auth.authenticate(token));
    }

    private static char[] pw(String value) {
        return value.toCharArray();
    }

    private long count(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
