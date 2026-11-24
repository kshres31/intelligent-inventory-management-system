package com.kshres31.inventory.migration;

import java.io.PrintStream;
import java.util.Set;

/**
 * Database-administration commands. They run before any application user is resolved,
 * because the user table may not exist yet.
 */
public final class MigrationCommand {
    private static final Set<String> COMMANDS = Set.of("migrate", "migration-status", "baseline");

    private final MigrationRunner runner;
    private final PrintStream out;

    public MigrationCommand(MigrationRunner runner, PrintStream out) {
        this.runner = runner;
        this.out = out;
    }

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0].toLowerCase());
    }

    public void run(String[] args) {
        switch (args[0].toLowerCase()) {
            case "migrate" -> {
                var applied = runner.migrate();
                applied.forEach(migration -> out.println("Applied " + migration.fileName()));
                out.println(applied.isEmpty() ? "Database is up to date." : "Applied " + applied.size() + " migration(s).");
            }
            case "migration-status" -> runner.status().forEach(status -> out.printf("%03d | %-40s | %s%n",
                    status.migration().version(), status.migration().fileName(),
                    status.applied() ? "applied " + status.appliedAt() : "PENDING"));
            case "baseline" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("Usage: baseline <version>");
                }
                var recorded = runner.baseline(Integer.parseInt(args[1]));
                out.println("Recorded " + recorded.size() + " existing migration(s) as applied without running them.");
            }
            default -> throw new IllegalArgumentException("Unknown migration command: " + args[0]);
        }
    }
}
