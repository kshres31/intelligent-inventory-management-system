package com.kshres31.inventory.repository;

import org.postgresql.util.PSQLException;

import java.sql.SQLException;

/** Helpers for turning PostgreSQL errors into messages that are safe to show users. */
final class SqlErrors {
    private SqlErrors() {
    }

    /**
     * The primary message raised by a PL/pgSQL function, without the driver's "ERROR:" prefix
     * or the "Where: PL/pgSQL function ..." context, which exposes internal signatures.
     */
    static String businessMessage(SQLException error) {
        if (error instanceof PSQLException psql && psql.getServerErrorMessage() != null
                && psql.getServerErrorMessage().getMessage() != null) {
            return psql.getServerErrorMessage().getMessage();
        }
        String message = error.getMessage();
        if (message == null) {
            return "Operation rejected";
        }
        String firstLine = message.lines().findFirst().orElse(message);
        return firstLine.startsWith("ERROR: ") ? firstLine.substring("ERROR: ".length()) : firstLine;
    }
}
