package com.kshres31.inventory.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationTest {
    @Test
    void parsesVersionDescriptionAndStripsOuterTransaction() {
        var migration = Migration.parse("007_add_bins.sql", "BEGIN;\nCREATE TABLE bin (id INT);\nCOMMIT;\n");

        assertEquals(7, migration.version());
        assertEquals("add bins", migration.description());
        assertEquals("\nCREATE TABLE bin (id INT);\n", migration.sql());
        assertFalse(migration.sql().contains("COMMIT"));
    }

    @Test
    void leavesUnwrappedFilesUntouched() {
        assertEquals("SELECT 1;", Migration.parse("010_plain.sql", "SELECT 1;").sql());
    }

    @Test
    void checksumIgnoresLineEndingsButNotContent() {
        var unix = Migration.parse("001_a.sql", "SELECT 1;\nSELECT 2;\n");
        var windows = Migration.parse("001_a.sql", "SELECT 1;\r\nSELECT 2;\r\n");
        var edited = Migration.parse("001_a.sql", "SELECT 1;\nSELECT 3;\n");

        assertEquals(unix.checksum(), windows.checksum());
        assertNotEquals(unix.checksum(), edited.checksum());
        assertTrue(unix.checksum().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsBadNamesAndHalfWrappedFiles() {
        assertThrows(MigrationException.class, () -> Migration.parse("7_short.sql", "SELECT 1;"));
        assertThrows(MigrationException.class, () -> Migration.parse("007-Add Bins.sql", "SELECT 1;"));
        assertThrows(MigrationException.class, () -> Migration.parse("007_x.sql", "BEGIN;\nSELECT 1;\n"));
    }
}
