package com.kshres31.inventory.controller;

import com.kshres31.inventory.model.AppUser;
import com.kshres31.inventory.security.AuthenticationService;
import com.kshres31.inventory.security.SessionStore;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/** Login, logout and password commands, plus resolving the current session's user. */
public final class AuthCommand {
    private static final Set<String> COMMANDS = Set.of("login", "logout", "whoami", "set-password", "bootstrap-admin");

    private final AuthenticationService auth;
    private final SessionStore sessions;
    private final Supplier<String> environmentToken;
    private final PasswordPrompt prompt;
    private final PrintStream out;

    public AuthCommand(AuthenticationService auth, SessionStore sessions, Supplier<String> environmentToken,
                       PasswordPrompt prompt, PrintStream out) {
        this.auth = auth;
        this.sessions = sessions;
        this.environmentToken = environmentToken;
        this.prompt = prompt;
        this.out = out;
    }

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0].toLowerCase());
    }

    public void run(String[] args) {
        switch (args[0].toLowerCase()) {
            case "login" -> {
                require(args, 2, "login <username>");
                var session = auth.login(args[1], prompt.read("Password for " + args[1] + ": "));
                sessions.write(session.token());
                out.printf("Logged in as %s (%s) until %s. Session saved to %s%n",
                        session.user().username(), session.user().role(), session.expiresAt(), sessions.path());
            }
            case "logout" -> {
                var token = currentToken();
                boolean revoked = token != null && auth.logout(token);
                sessions.clear();
                out.println(revoked ? "Logged out." : "No active session.");
            }
            case "whoami" -> {
                var user = currentUser();
                out.printf("%s | %s | %s%n", user.username(), user.displayName(), user.role());
            }
            case "set-password" -> {
                require(args, 2, "set-password <username>");
                var actor = currentUser();
                char[] first = prompt.read("New password for " + args[1] + ": ");
                char[] second = prompt.read("Repeat new password: ");
                try {
                    if (!Arrays.equals(first, second)) {
                        throw new IllegalArgumentException("Passwords do not match");
                    }
                    auth.setPassword(actor, args[1], first.clone());
                } finally {
                    Arrays.fill(first, '\0');
                    Arrays.fill(second, '\0');
                }
                out.println("Password updated. Existing sessions for " + args[1] + " were signed out.");
                if (actor.username().equals(args[1])) {
                    sessions.clear();
                }
            }
            case "bootstrap-admin" -> {
                require(args, 2, "bootstrap-admin <username>");
                auth.bootstrapAdmin(args[1], prompt.read("Initial password for " + args[1] + ": "));
                out.println("Initial administrator password set. Run 'login " + args[1] + "'.");
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    public AppUser currentUser() {
        return auth.authenticate(currentToken());
    }

    private String currentToken() {
        String fromEnvironment = environmentToken.get();
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return sessions.read().orElse(null);
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    @FunctionalInterface
    public interface PasswordPrompt {
        char[] read(String message);
    }
}
