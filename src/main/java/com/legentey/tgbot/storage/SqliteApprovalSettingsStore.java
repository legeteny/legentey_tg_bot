package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SqliteApprovalSettingsStore implements ApprovalSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteApprovalSettingsStore.class);

    private static final int DEFAULT_APPROVE_COUNT = 2;

    private final Connection connection;
    private final Object lock = new Object();

    public SqliteApprovalSettingsStore(String dbPath) {
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
                    CREATE TABLE IF NOT EXISTS approval_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        approve_count INTEGER NOT NULL
                    )
                    """);
            st.execute("INSERT OR IGNORE INTO approval_settings (id, approve_count) VALUES (1, "
                    + DEFAULT_APPROVE_COUNT + ")");
        }
    }

    @Override
    public int getApproveCount() {
        synchronized (lock) {
            String sql = "SELECT approve_count FROM approval_settings WHERE id = 1";
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
            String sql = "UPDATE approval_settings SET approve_count = ? WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, count);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save approve count {}", count, e);
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
