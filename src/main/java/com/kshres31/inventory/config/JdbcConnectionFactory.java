package com.kshres31.inventory.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class JdbcConnectionFactory implements ConnectionFactory {
    private final DatabaseConfig config;

    public JdbcConnectionFactory(DatabaseConfig config) {
        this.config = config;
    }

    @Override
    public Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }
}
