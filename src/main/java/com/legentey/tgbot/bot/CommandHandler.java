package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.storage.ChatUser;
import com.legentey.tgbot.storage.UserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;

import java.util.List;
import java.util.Locale;

public class CommandHandler {

    private final TelegramApiClient client;
    private final UserStore store;
    private final StickerService stickerService;
    private final Config config;

    public CommandHandler(TelegramApiClient client, UserStore store, StickerService stickerService, Config config) {
        this.client = client;
        this.store = store;
        this.stickerService = stickerService;
        this.config = config;
    }

    public void handle(JsonNode message, boolean isGroup) {
        long chatId = message.path("chat").path("id").asLong();
        String text = message.path("text").asText();

        String[] parts = text.trim().split("\\s+", 2);
        String rawCommand = parts[0];
        String args = parts.length > 1 ? parts[1].trim() : "";
        int at = rawCommand.indexOf('@');
        String command = (at >= 0 ? rawCommand.substring(1, at) : rawCommand.substring(1))
                .toLowerCase(Locale.ROOT);

        switch (command) {
            case "all" -> handleAll(chatId, isGroup);
            case "q" -> handleQ(message, chatId, isGroup, args);
            default -> {
            }
        }
    }

    private void handleAll(long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }

        List<ChatUser> users = store.listChatUsers(chatId);
        if (users.isEmpty()) {
            return;
        }

        List<String> chunks = MentionFormatter.buildMentionMessages(users);
        for (int i = 0; i < chunks.size(); i++) {
            client.sendMessage(chatId, chunks.get(i), "MarkdownV2", null);
            if (i < chunks.size() - 1) {
                sleepQuietly(1200);
            }
        }
    }

    private void handleQ(JsonNode message, long chatId, boolean isGroup, String emoji) {
        if (!isGroup) {
            return;
        }
        long requesterUserId = message.path("from").path("id").asLong();
        if (config.adminChatId() == null || requesterUserId != config.adminChatId()) {
            return;
        }
        JsonNode replyTo = message.path("reply_to_message");
        if (replyTo.isMissingNode()) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        if (emoji.isBlank()) {
            client.sendMessage(chatId, "Укажите смайл после команды, например: /q 😂", null, messageId);
            return;
        }
        stickerService.createQuote(chatId, requesterUserId, replyTo, emoji);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
