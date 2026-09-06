package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class SqliteUpdateOffsetStore implements UpdateOffsetStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteUpdateOffsetStore.class);

    /** The table holds a single row; this is its fixed key. */
    private static final int ROW_ID = 0;

    private final Connection connection;
    private final Object lock = new Object();

    /** Schema (the update_offset table) is created by Flyway — see {@link DatabaseMigrator}. */
    public SqliteUpdateOffsetStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite update offset storage at " + dbPath, e);
        }
    }

    @Override
    public Optional<Long> lastOffset() {
        synchronized (lock) {
            String sql = "SELECT next_update_id FROM update_offset WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, ROW_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    // An offset of 0 is a real value, so presence is decided by the row, not the number.
                    return rs.next() ? Optional.of(rs.getLong("next_update_id")) : Optional.empty();
                }
            } catch (SQLException e) {
                log.error("Failed to read the stored update offset", e);
                return Optional.empty();
            }
        }
    }

    @Override
    public void saveOffset(long offset) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO update_offset (id, next_update_id)
                    VALUES (?, ?)
                    ON CONFLICT(id) DO UPDATE SET next_update_id = excluded.next_update_id
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, ROW_ID);
                ps.setLong(2, offset);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save update offset {}", offset, e);
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
