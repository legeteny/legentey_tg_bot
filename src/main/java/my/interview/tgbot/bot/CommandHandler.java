package my.interview.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import my.interview.tgbot.storage.ChatUser;
import my.interview.tgbot.storage.UserStore;
import my.interview.tgbot.telegram.TelegramApiClient;

import java.util.List;
import java.util.Locale;

public class CommandHandler {

    private final TelegramApiClient client;
    private final UserStore store;

    public CommandHandler(TelegramApiClient client, UserStore store) {
        this.client = client;
        this.store = store;
    }

    public void handle(long chatId, long messageId, boolean isGroup, String text) {
        String rawCommand = text.trim().split("\\s+", 2)[0];
        int at = rawCommand.indexOf('@');
        String command = (at >= 0 ? rawCommand.substring(1, at) : rawCommand.substring(1))
                .toLowerCase(Locale.ROOT);

        if (command.equals("all")) {
            handleAll(chatId, messageId, isGroup);
        }
    }

    private void handleAll(long chatId, long messageId, boolean isGroup) {
        if (!isGroup) {
            return;
        }

        List<ChatUser> users = store.listChatUsers(chatId);
        if (users.isEmpty()) {
            return;
        }

        List<String> chunks = MentionFormatter.buildMentionMessages(users);
        for (int i = 0; i < chunks.size(); i++) {
            client.sendMessage(chatId, chunks.get(i), "MarkdownV2", i == 0 ? messageId : null);
            if (i < chunks.size() - 1) {
                sleepQuietly(1200);
            }
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
