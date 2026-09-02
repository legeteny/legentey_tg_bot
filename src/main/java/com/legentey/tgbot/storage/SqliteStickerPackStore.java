package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class SqliteStickerPackStore implements StickerPackStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteStickerPackStore.class);

    private final Connection connection;
    private final Object lock = new Object();

    public SqliteStickerPackStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite sticker pack storage at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chat_sticker_packs (
                        chat_id INTEGER PRIMARY KEY,
                        pack_name TEXT NOT NULL,
                        owner_user_id INTEGER NOT NULL
                    )
                    """);
        }
    }

    @Override
    public Optional<StickerPack> findForChat(long chatId) {
        synchronized (lock) {
            String sql = "SELECT pack_name, owner_user_id FROM chat_sticker_packs WHERE chat_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StickerPack(chatId, rs.getString("pack_name"), rs.getLong("owner_user_id")));
                }
            } catch (SQLException e) {
                log.error("Failed to look up sticker pack for chat {}", chatId, e);
                return Optional.empty();
            }
        }
    }

    @Override
    public void save(long chatId, String packName, long ownerUserId) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO chat_sticker_packs (chat_id, pack_name, owner_user_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT(chat_id) DO UPDATE SET
                        pack_name = excluded.pack_name,
                        owner_user_id = excluded.owner_user_id
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setString(2, packName);
                ps.setLong(3, ownerUserId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save sticker pack for chat {}", chatId, e);
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
