package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SqliteRenderSettingsStore implements RenderSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteRenderSettingsStore.class);

    /** The RENDER_WIDTH quote-api was hardcoded to before this became a runtime setting. */
    private static final int DEFAULT_WIDTH = 280;

    private final Connection connection;
    private final Object lock = new Object();

    public SqliteRenderSettingsStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite render settings storage at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS render_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        width INTEGER NOT NULL
                    )
                    """);
            st.execute("INSERT OR IGNORE INTO render_settings (id, width) VALUES (1, " + DEFAULT_WIDTH + ")");
        }
    }

    @Override
    public int getWidth() {
        synchronized (lock) {
            String sql = "SELECT width FROM render_settings WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("width");
                }
            } catch (SQLException e) {
                log.error("Failed to read render width, falling back to default", e);
            }
            return DEFAULT_WIDTH;
        }
    }

    @Override
    public void setWidth(int width) {
        synchronized (lock) {
            String sql = "UPDATE render_settings SET width = ? WHERE id = 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, width);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save render width {}", width, e);
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
