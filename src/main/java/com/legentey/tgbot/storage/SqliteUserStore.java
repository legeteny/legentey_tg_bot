package com.legentey.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed implementation of {@link UserStore}.
 *
 * <p>All access goes through a single connection guarded by a lock: the bot processes
 * updates one at a time on its polling thread, so there is no real concurrency to
 * exploit, and a single file-backed SQLite connection keeps things simple to run
 * inside a container with a mounted volume.
 */
public class SqliteUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteUserStore.class);

    private final Connection connection;
    private final Object lock = new Object();

    /** Schema (the chat_users table) is created by Flyway — see {@link DatabaseMigrator} — before this runs. */
    public SqliteUserStore(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            log.info("Opened SQLite database at {}", dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite storage at " + dbPath, e);
        }
    }

    @Override
    public void upsertUser(long chatId, long userId, String username, String firstName, String lastName) {
        synchronized (lock) {
            if (username != null) {
                // Someone added by hand as @username has now shown up for real — keep one row, not two.
                String merge = "DELETE FROM chat_users WHERE chat_id = ? AND user_id IS NULL AND lower(username) = lower(?)";
                try (PreparedStatement ps = connection.prepareStatement(merge)) {
                    ps.setLong(1, chatId);
                    ps.setString(2, username);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    log.error("Failed to merge manual entry @{} in chat {}", username, chatId, e);
                }
            }
            String sql = """
                    INSERT INTO chat_users (chat_id, user_id, username, first_name, last_name, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(chat_id, user_id) WHERE user_id IS NOT NULL DO UPDATE SET
                        username = excluded.username,
                        first_name = excluded.first_name,
                        last_name = excluded.last_name,
                        updated_at = excluded.updated_at
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                ps.setString(3, username);
                ps.setString(4, firstName);
                ps.setString(5, lastName);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to upsert user {} in chat {}", userId, chatId, e);
            }
        }
    }

    @Override
    public boolean hasUser(long chatId, long userId) {
        synchronized (lock) {
            String sql = "SELECT 1 FROM chat_users WHERE chat_id = ? AND user_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                log.error("Failed to look up user {} in chat {}", userId, chatId, e);
                return false;
            }
        }
    }

    @Override
    public boolean addUsername(long chatId, String username) {
        synchronized (lock) {
            String exists = "SELECT 1 FROM chat_users WHERE chat_id = ? AND lower(username) = lower(?)";
            String insert = """
                    INSERT INTO chat_users (chat_id, user_id, username, first_name, last_name, updated_at)
                    VALUES (?, NULL, ?, NULL, NULL, ?)
                    """;
            try (PreparedStatement check = connection.prepareStatement(exists)) {
                check.setLong(1, chatId);
                check.setString(2, username);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(insert)) {
                    ps.setLong(1, chatId);
                    ps.setString(2, username);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return true;
            } catch (SQLException e) {
                log.error("Failed to add @{} to chat {}", username, chatId, e);
                return false;
            }
        }
    }

    @Override
    public boolean removeUser(long chatId, long userId) {
        synchronized (lock) {
            String sql = "DELETE FROM chat_users WHERE chat_id = ? AND user_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                log.error("Failed to remove user {} from chat {}", userId, chatId, e);
                return false;
            }
        }
    }

    @Override
    public boolean removeUsername(long chatId, String username) {
        synchronized (lock) {
            String sql = "DELETE FROM chat_users WHERE chat_id = ? AND lower(username) = lower(?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setString(2, username);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                log.error("Failed to remove @{} from chat {}", username, chatId, e);
                return false;
            }
        }
    }

    @Override
    public List<ChatUser> listChatUsers(long chatId) {
        synchronized (lock) {
            String sql = "SELECT user_id, username, first_name, last_name FROM chat_users WHERE chat_id = ?";
            List<ChatUser> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long userId = rs.getLong("user_id");
                        result.add(new ChatUser(
                                chatId,
                                rs.wasNull() ? null : userId,
                                rs.getString("username"),
                                rs.getString("first_name"),
                                rs.getString("last_name")
                        ));
                    }
                }
            } catch (SQLException e) {
                log.error("Failed to list users for chat {}", chatId, e);
            }
            return result;
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
