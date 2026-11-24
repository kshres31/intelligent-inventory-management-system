package com.kshres31.inventory.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * One numbered SQL file such as {@code 006_alert_notifications.sql}.
 *
 * <p>Files keep their own {@code BEGIN;} / {@code COMMIT;} wrapper so they can still be
 * applied with {@code psql}. The runner removes that outer wrapper and executes the body in
 * its own transaction together with the version record, so a migration and its record
 * always commit or roll back as one unit.
 */
public record Migration(int version, String description, String fileName, String sql, String checksum) {
    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{3,})_([a-z0-9_]+)\\.sql$");
    private static final Pattern LEADING_BEGIN = Pattern.compile("\\A\\s*BEGIN\\s*;", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_COMMIT = Pattern.compile("COMMIT\\s*;\\s*\\z", Pattern.CASE_INSENSITIVE);

    public static Migration parse(String fileName, String content) {
        var matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new MigrationException("Migration file name must look like 007_short_name.sql: " + fileName);
        }
        String normalized = content.replace("\r\n", "\n");
        return new Migration(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2).replace('_', ' '),
                fileName,
                body(fileName, normalized),
                sha256(normalized)
        );
    }

    private static String body(String fileName, String content) {
        var begin = LEADING_BEGIN.matcher(content);
        var commit = TRAILING_COMMIT.matcher(content);
        boolean hasBegin = begin.find();
        boolean hasCommit = commit.find();
        if (hasBegin != hasCommit) {
            throw new MigrationException(fileName + " must either start with BEGIN; and end with COMMIT; or use neither");
        }
        if (!hasBegin) {
            return content;
        }
        return content.substring(begin.end(), commit.start());
    }

    private static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
