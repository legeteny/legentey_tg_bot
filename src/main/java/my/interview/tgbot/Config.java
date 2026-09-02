package my.interview.tgbot;

public record Config(
        String botToken,
        String dbPath,
        int getUpdatesTimeoutSeconds,
        Long adminChatId,
        Long quotlyGroupChatId
) {

    public static Config fromEnv() {
        String token = require("BOT_TOKEN");
        String dbPath = System.getenv().getOrDefault("DB_PATH", "/data/bot.db");
        int timeout = parseIntOrDefault(System.getenv("POLL_TIMEOUT_SECONDS"), 30);
        Long adminChatId = parseLongOrNull(System.getenv("ADMIN_CHAT_ID"));
        Long quotlyGroupChatId = parseLongOrNull(System.getenv("QUOTLY_GROUP_CHAT_ID"));
        return new Config(token, dbPath, timeout, adminChatId, quotlyGroupChatId);
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required but not set");
        }
        return value;
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
