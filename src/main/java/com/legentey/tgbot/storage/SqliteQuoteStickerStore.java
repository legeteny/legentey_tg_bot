package com.legentey.tgbot.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SqliteQuoteStickerStore implements QuoteStickerStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteQuoteStickerStore.class);

    private final Connection connection;
    private final Object lock = new Object();

    public SqliteQuoteStickerStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite quote sticker storage at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS quote_stickers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id INTEGER NOT NULL,
                        pack_name TEXT NOT NULL,
                        owner_user_id INTEGER NOT NULL,
                        file_id TEXT NOT NULL,
                        from_json TEXT NOT NULL,
                        text TEXT NOT NULL,
                        entities_json TEXT,
                        emoji TEXT NOT NULL,
                        avatar_file_id TEXT,
                        emoji_status TEXT,
                        username TEXT
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_quote_stickers_pack ON quote_stickers(pack_name, id)");
            // Rows from before these columns existed just get NULL here — rerenderAll()
            // treats that the same as "never captured" and falls back to a live lookup.
            addColumnIfMissing(st, "avatar_file_id", "TEXT");
            addColumnIfMissing(st, "emoji_status", "TEXT");
            addColumnIfMissing(st, "username", "TEXT");
        }
        migrateEmojiToJsonArray();
    }

    private void addColumnIfMissing(Statement st, String column, String type) {
        try {
            st.execute("ALTER TABLE quote_stickers ADD COLUMN " + column + " " + type);
        } catch (SQLException e) {
            // Column already exists (either from CREATE TABLE on a fresh DB, or a prior run's ALTER) — fine.
        }
    }

    /**
     * Rows written before multi-emoji support stored a bare emoji character in this
     * column (e.g. "😀"); newer rows store a JSON array (e.g. ["😀","😄"]) so more
     * than one can round-trip through /q_rerender. This upgrades any row still in
     * the old shape in place. It's a plain heuristic rather than an attempted JSON
     * parse — no valid emoji starts with '[', so "doesn't look like a JSON array"
     * is exactly "is a legacy row" — and it's idempotent (an already-migrated row
     * is left alone), so it's safe to run on every startup instead of needing a
     * one-shot flag.
     */
    private void migrateEmojiToJsonArray() throws SQLException {
        record LegacyRow(long id, String emoji) {
        }
        List<LegacyRow> legacy = new ArrayList<>();
        try (var st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, emoji FROM quote_stickers")) {
            while (rs.next()) {
                String emoji = rs.getString("emoji");
                if (emoji != null && !looksLikeJsonArray(emoji)) {
                    legacy.add(new LegacyRow(rs.getLong("id"), emoji));
                }
            }
        }
        if (legacy.isEmpty()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        try (PreparedStatement ps = connection.prepareStatement("UPDATE quote_stickers SET emoji = ? WHERE id = ?")) {
            for (LegacyRow row : legacy) {
                ps.setString(1, mapper.writeValueAsString(List.of(row.emoji())));
                ps.setLong(2, row.id());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize a legacy emoji value during migration", e);
        }
        log.info("Migrated {} quote sticker row(s) from bare-emoji to JSON-array format", legacy.size());
    }

    private static boolean looksLikeJsonArray(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    @Override
    public void save(long chatId, String packName, long ownerUserId, String fileId, String fromJson, String text,
                      String entitiesJson, String emoji, String avatarFileId, String emojiStatus, String username) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO quote_stickers
                        (chat_id, pack_name, owner_user_id, file_id, from_json, text, entities_json, emoji,
                         avatar_file_id, emoji_status, username)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setString(2, packName);
                ps.setLong(3, ownerUserId);
                ps.setString(4, fileId);
                ps.setString(5, fromJson);
                ps.setString(6, text);
                ps.setString(7, entitiesJson);
                ps.setString(8, emoji);
                ps.setString(9, avatarFileId);
                ps.setString(10, emojiStatus);
                ps.setString(11, username);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save quote sticker for chat {}", chatId, e);
            }
        }
    }

    @Override
    public List<String> findDistinctPackNames() {
        synchronized (lock) {
            List<String> names = new ArrayList<>();
            String sql = "SELECT DISTINCT pack_name FROM quote_stickers";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("pack_name"));
                }
            } catch (SQLException e) {
                log.error("Failed to list quote sticker pack names", e);
            }
            return names;
        }
    }

    @Override
    public List<QuoteSticker> findByPack(String packName) {
        synchronized (lock) {
            List<QuoteSticker> result = new ArrayList<>();
            String sql = """
                    SELECT id, chat_id, owner_user_id, file_id, from_json, text, entities_json, emoji,
                           avatar_file_id, emoji_status, username
                    FROM quote_stickers WHERE pack_name = ? ORDER BY id
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, packName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new QuoteSticker(
                                rs.getLong("id"),
                                rs.getLong("chat_id"),
                                packName,
                                rs.getLong("owner_user_id"),
                                rs.getString("file_id"),
                                rs.getString("from_json"),
                                rs.getString("text"),
                                rs.getString("entities_json"),
                                rs.getString("emoji"),
                                rs.getString("avatar_file_id"),
                                rs.getString("emoji_status"),
                                rs.getString("username")));
                    }
                }
            } catch (SQLException e) {
                log.error("Failed to load quote stickers for pack {}", packName, e);
            }
            return result;
        }
    }

    @Override
    public void updateFileId(long id, String newFileId) {
        synchronized (lock) {
            String sql = "UPDATE quote_stickers SET file_id = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, newFileId);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to update file_id for quote sticker {}", id, e);
            }
        }
    }

    @Override
    public void updateProfile(long id, String avatarFileId, String emojiStatus, String username) {
        synchronized (lock) {
            String sql = "UPDATE quote_stickers SET avatar_file_id = ?, emoji_status = ?, username = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, avatarFileId);
                ps.setString(2, emojiStatus);
                ps.setString(3, username);
                ps.setLong(4, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to update profile for quote sticker {}", id, e);
            }
        }
    }

    @Override
    public void delete(long id) {
        synchronized (lock) {
            String sql = "DELETE FROM quote_stickers WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to delete quote sticker {}", id, e);
            }
        }
    }

    @Override
    public void deleteByFileId(String fileId) {
        synchronized (lock) {
            String sql = "DELETE FROM quote_stickers WHERE file_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, fileId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to delete quote sticker by file_id", e);
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
