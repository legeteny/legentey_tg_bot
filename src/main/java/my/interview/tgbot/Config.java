package my.interview.tgbot;

public record Config(
        String botToken,
        String dbPath,
        boolean tagAllAdminOnly,
        int getUpdatesTimeoutSeconds,
        Long adminChatId
) {

    public static Config fromEnv() {
        String token = require("BOT_TOKEN");
        String dbPath = System.getenv().getOrDefault("DB_PATH", "/data/bot.db");
        boolean adminOnly = Boolean.parseBoolean(System.getenv().getOrDefault("TAGALL_ADMIN_ONLY", "true"));
        int timeout = parseIntOrDefault(System.getenv("POLL_TIMEOUT_SECONDS"), 30);
        Long adminChatId = parseLongOrNull(System.getenv("ADMIN_CHAT_ID"));
        return new Config(token, dbPath, adminOnly, timeout, adminChatId);
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
