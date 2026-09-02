package my.interview.tgbot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    public SqliteUserStore(String dbPath) {
        try {
            File dbFile = new File(dbPath);
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
            log.info("Opened SQLite database at {}", dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite storage at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chat_users (
                        chat_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        username TEXT,
                        first_name TEXT,
                        last_name TEXT,
                        opted_out INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (chat_id, user_id)
                    )
                    """);
        }
    }

    @Override
    public void upsertUser(long chatId, long userId, String username, String firstName, String lastName) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO chat_users (chat_id, user_id, username, first_name, last_name, opted_out, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0, ?)
                    ON CONFLICT(chat_id, user_id) DO UPDATE SET
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
    public void removeUser(long chatId, long userId) {
        synchronized (lock) {
            String sql = "DELETE FROM chat_users WHERE chat_id = ? AND user_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to remove user {} from chat {}", userId, chatId, e);
            }
        }
    }

    @Override
    public void setOptedOut(long chatId, long userId, boolean optedOut) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO chat_users (chat_id, user_id, opted_out, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(chat_id, user_id) DO UPDATE SET
                        opted_out = excluded.opted_out,
                        updated_at = excluded.updated_at
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.setLong(2, userId);
                ps.setInt(3, optedOut ? 1 : 0);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to set opted_out for user {} in chat {}", userId, chatId, e);
            }
        }
    }

    @Override
    public List<ChatUser> listChatUsers(long chatId) {
        synchronized (lock) {
            String sql = "SELECT user_id, username, first_name, last_name, opted_out FROM chat_users WHERE chat_id = ?";
            List<ChatUser> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ChatUser(
                                chatId,
                                rs.getLong("user_id"),
                                rs.getString("username"),
                                rs.getString("first_name"),
                                rs.getString("last_name"),
                                rs.getInt("opted_out") != 0
                        ));
                    }
                }
            } catch (SQLException e) {
                log.error("Failed to list users for chat {}", chatId, e);
            }
            return result;
        }
    }

    @Override
    public int countChatUsers(long chatId) {
        synchronized (lock) {
            String sql = "SELECT COUNT(*) FROM chat_users WHERE chat_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("Failed to count users for chat {}", chatId, e);
                return 0;
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
