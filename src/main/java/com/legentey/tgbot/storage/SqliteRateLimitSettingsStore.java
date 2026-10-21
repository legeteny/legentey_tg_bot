package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SqliteRateLimitSettingsStore implements RateLimitSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteRateLimitSettingsStore.class);

    /** The /all cooldown that was hardcoded before this became a runtime setting. */
    private static final int DEFAULT_ALL_COOLDOWN_SECONDS = 60;

    private final Connection connection;
    private final Object lock = new Object();

    /** Schema (the rate_limit_settings table, seeded with the default) is created by Flyway — see {@link DatabaseMigrator}. */
    public SqliteRateLimitSettingsStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite rate limit settings storage at " + dbPath, e);
        }
    }

    @Override
    public int getAllCooldownSeconds() {
        synchronized (lock) {
            String sql = "SELECT all_cooldown_seconds FROM rate_limit_settings WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("all_cooldown_seconds");
                }
            } catch (SQLException e) {
                log.error("Failed to read /all cooldown, falling back to default", e);
            }
            return DEFAULT_ALL_COOLDOWN_SECONDS;
        }
    }

    @Override
    public void setAllCooldownSeconds(int seconds) {
        synchronized (lock) {
            String sql = "UPDATE rate_limit_settings SET all_cooldown_seconds = ? WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, seconds);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save /all cooldown {}", seconds, e);
            }
        }
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close SQLite connection", e);
        }
    }
}
