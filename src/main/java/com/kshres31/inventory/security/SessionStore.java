package com.kshres31.inventory.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * Keeps the CLI's session token between commands in a file readable only by the owner
 * (POSIX 0600 where supported). {@code INVENTORY_SESSION_TOKEN} overrides the file.
 */
public final class SessionStore {
    private final Path file;

    public SessionStore(Path file) {
        this.file = file;
    }

    public static SessionStore fromEnvironment(java.util.Map<String, String> environment) {
        String configured = environment.get("INVENTORY_SESSION_FILE");
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".inventory", "session")
                : Path.of(configured);
        return new SessionStore(path);
    }

    public Optional<String> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String token = Files.readString(file, StandardCharsets.UTF_8).strip();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read session file " + file, error);
        }
    }

    public void write(String token) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            Files.deleteIfExists(file);
            if (posix) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
            Files.writeString(file, token, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not write session file " + file, error);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not delete session file " + file, error);
        }
    }

    public Path path() {
        return file;
    }
}
