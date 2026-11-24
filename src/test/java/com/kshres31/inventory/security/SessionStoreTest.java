package com.kshres31.inventory.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionStoreTest {
    @Test
    void writesOwnerOnlyFileAndClearsIt(@TempDir Path directory) throws Exception {
        var store = new SessionStore(directory.resolve("nested").resolve("session"));

        assertEquals(Optional.empty(), store.read());
        store.write("token-one");
        store.write("token-two");
        assertEquals(Optional.of("token-two"), store.read());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path())));
        }
        store.clear();
        assertEquals(Optional.empty(), store.read());
    }

    @Test
    void environmentCanChooseTheFile(@TempDir Path directory) {
        Path file = directory.resolve("custom");

        assertEquals(file, SessionStore.fromEnvironment(Map.of("INVENTORY_SESSION_FILE", file.toString())).path());
    }
}
