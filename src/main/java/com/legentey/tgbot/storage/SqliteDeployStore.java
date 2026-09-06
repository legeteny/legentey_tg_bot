package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class SqliteDeployStore implements DeployStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteDeployStore.class);

    /** The table holds a single row; this is its fixed key. */
    private static final int ROW_ID = 0;

    private final Connection connection;
    private final Object lock = new Object();

    /** Schema (the deploy_state table) is created by Flyway — see {@link DatabaseMigrator}. */
    public SqliteDeployStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite deploy state storage at " + dbPath, e);
        }
    }

    @Override
    public Optional<String> lastAnnouncedCommit() {
        synchronized (lock) {
            String sql = "SELECT last_commit FROM deploy_state WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, ROW_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String hash = rs.getString("last_commit");
                    return hash == null || hash.isBlank() ? Optional.empty() : Optional.of(hash);
                }
            } catch (SQLException e) {
                log.error("Failed to read last announced commit", e);
                return Optional.empty();
            }
        }
    }

    @Override
    public void saveAnnouncedCommit(String commitHash) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO deploy_state (id, last_commit)
                    VALUES (?, ?)
                    ON CONFLICT(id) DO UPDATE SET last_commit = excluded.last_commit
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, ROW_ID);
                ps.setString(2, commitHash);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save last announced commit {}", commitHash, e);
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
