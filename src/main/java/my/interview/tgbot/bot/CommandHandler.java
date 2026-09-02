package my.interview.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import my.interview.tgbot.Config;
import my.interview.tgbot.storage.ChatUser;
import my.interview.tgbot.storage.UserStore;
import my.interview.tgbot.telegram.TelegramApiClient;
import my.interview.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final TelegramApiClient client;
    private final UserStore store;
    private final Config config;

    public CommandHandler(TelegramApiClient client, UserStore store, Config config) {
        this.client = client;
        this.store = store;
        this.config = config;
    }

    public void handle(long chatId, long messageId, boolean isGroup, JsonNode from, String text) {
        String[] parts = text.trim().split("\\s+", 2);
        String rawCommand = parts[0];
        String args = parts.length > 1 ? parts[1].trim() : "";

        int at = rawCommand.indexOf('@');
        String command = (at >= 0 ? rawCommand.substring(1, at) : rawCommand.substring(1))
                .toLowerCase(Locale.ROOT);

        long senderId = from.path("id").asLong();

        switch (command) {
            case "all" -> handleAll(chatId, messageId, isGroup, senderId, args);
            default -> {
                // Unknown command — stay quiet to avoid spamming unrelated bot commands in the group.
            }
        }
    }

    private void handleAll(long chatId, long messageId, boolean isGroup, long senderId, String args) {
        if (!isGroup) {
            client.sendMessage(chatId, "Эта команда работает только в группах.", null, messageId);
            return;
        }

        if (config.tagAllAdminOnly() && !isAdmin(chatId, senderId)) {
            client.sendMessage(chatId, "⛔ Эту команду может использовать только администратор группы.", null, messageId);
            return;
        }

        List<ChatUser> users = store.listChatUsers(chatId);

        if (users.isEmpty()) {
            client.sendMessage(chatId,
                    "Пока не знаю ни одного участника этого чата для упоминания. "
                            + "Я запоминаю участников по мере того, как они пишут сообщения.",
                    null, messageId);
            return;
        }

        String header = args.isBlank() ? "📢 Внимание всем:" : "📢 " + args;
        List<String> chunks = MentionFormatter.buildMentionMessages(users, header);

        for (int i = 0; i < chunks.size(); i++) {
            client.sendMessage(chatId, chunks.get(i), "MarkdownV2", i == 0 ? messageId : null);
            if (i < chunks.size() - 1) {
                sleepQuietly(1200);
            }
        }
    }

    private boolean isAdmin(long chatId, long userId) {
        try {
            JsonNode admins = client.getChatAdministrators(chatId);
            for (JsonNode member : admins) {
                if (member.path("user").path("id").asLong() == userId) {
                    return true;
                }
            }
            return false;
        } catch (TelegramApiException e) {
            log.warn("Failed to fetch chat administrators for chat {}: {}", chatId, e.getMessage());
            // Fail closed: if we can't verify admin rights, don't allow a mass-mention.
            return false;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
