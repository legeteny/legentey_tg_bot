package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class SqliteApprovalSettingsStore implements ApprovalSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteApprovalSettingsStore.class);

    private static final int DEFAULT_APPROVE_COUNT = 2;

    /** The five minutes a vote was hardcoded to before this became a runtime setting. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final Connection connection;
    private final Object lock = new Object();
    /** Which feature's approval settings this instance holds — /q_del and /s_del each get their own table/row. */
    private final String table;

    public SqliteApprovalSettingsStore(String dbPath, String table) {
        this.table = table;
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite approval settings storage at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        approve_count INTEGER NOT NULL
                    )
                    """.formatted(table));
            st.execute("INSERT OR IGNORE INTO " + table + " (id, approve_count) VALUES (1, "
                    + DEFAULT_APPROVE_COUNT + ")");
            // A row written before this column existed keeps the old hardcoded timeout.
            addColumnIfMissing(st, "approve_timeout_seconds", "INTEGER NOT NULL DEFAULT " + DEFAULT_TIMEOUT_SECONDS);
        }
    }

    private void addColumnIfMissing(Statement st, String column, String type) {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException e) {
            // Column already exists (either from a prior run's ALTER, or nothing to migrate) — fine.
        }
    }

    @Override
    public int getApproveCount() {
        synchronized (lock) {
            String sql = "SELECT approve_count FROM " + table + " WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("approve_count");
                }
            } catch (SQLException e) {
                log.error("Failed to read approve count, falling back to default", e);
            }
            return DEFAULT_APPROVE_COUNT;
        }
    }

    @Override
    public void setApproveCount(int count) {
        synchronized (lock) {
            String sql = "UPDATE " + table + " SET approve_count = ? WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, count);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save approve count {}", count, e);
            }
        }
    }

    @Override
    public int getApproveTimeoutSeconds() {
        synchronized (lock) {
            String sql = "SELECT approve_timeout_seconds FROM " + table + " WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("approve_timeout_seconds");
                }
            } catch (SQLException e) {
                log.error("Failed to read approve timeout, falling back to default", e);
            }
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    @Override
    public void setApproveTimeoutSeconds(int seconds) {
        synchronized (lock) {
            String sql = "UPDATE " + table + " SET approve_timeout_seconds = ? WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, seconds);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save approve timeout {}", seconds, e);
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
