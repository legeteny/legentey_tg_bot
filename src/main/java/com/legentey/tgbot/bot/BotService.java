package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.GitInfo;
import com.legentey.tgbot.quoteapi.QuoteApiClient;
import com.legentey.tgbot.storage.StickerPackStore;
import com.legentey.tgbot.storage.UserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class BotService {

    private static final List<Map<String, String>> COMMANDS = List.of(
            Map.of("command", "all", "description", "Упомянуть всех известных участников чата"),
            Map.of("command", "q", "description", "Ответом на сообщение и смайлом — сделать из него стикер-цитату")
    );

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final TelegramApiClient client;
    private final UserStore store;
    private final StickerPackStore stickerPackStore;
    private final Config config;

    private CommandHandler commandHandler;
    private StickerService stickerService;

    private volatile boolean running = true;

    public BotService(TelegramApiClient client, UserStore store, StickerPackStore stickerPackStore, Config config) {
        this.client = client;
        this.store = store;
        this.stickerPackStore = stickerPackStore;
        this.config = config;
    }

    public void stop() {
        running = false;
    }

    public void run() {
        JsonNode me = client.getMe();
        String botUsername = me.path("username").asText("");
        log.info("Logged in as @{} (id={})", botUsername, me.path("id").asLong());

        QuoteApiClient quoteApiClient = new QuoteApiClient(config.quoteApiUrl());
        this.stickerService = new StickerService(client, quoteApiClient, stickerPackStore, botUsername);
        this.commandHandler = new CommandHandler(client, store, stickerService, config);

        try {
            client.setMyCommands(COMMANDS);
            log.info("Registered command menu ({} commands)", COMMANDS.size());
        } catch (TelegramApiException e) {
            log.warn("Failed to register command menu: {}", e.getMessage());
        }

        notifyStartup();

        long offset = 0;
        while (running) {
            try {
                JsonNode updates = client.getUpdates(offset, config.getUpdatesTimeoutSeconds());
                for (JsonNode update : updates) {
                    offset = update.path("update_id").asLong() + 1;
                    try {
                        handleUpdate(update);
                    } catch (Exception e) {
                        log.error("Failed to handle update {}", update.path("update_id").asLong(), e);
                    }
                }
            } catch (TelegramApiException e) {
                Integer retryAfter = e.retryAfterSeconds();
                long sleepMs = retryAfter != null ? retryAfter * 1000L : 5000L;
                log.warn("Telegram API error while polling: {} (sleeping {} ms)", e.getMessage(), sleepMs);
                sleepQuietly(sleepMs);
            } catch (Exception e) {
                log.error("Unexpected error while polling for updates", e);
                sleepQuietly(5000);
            }
        }
    }

    private void notifyStartup() {
        if (config.adminChatId() == null) {
            log.info("ADMIN_CHAT_ID not set, skipping startup notification");
            return;
        }
        GitInfo git = GitInfo.detect();
        String text = "🚀 Бот запущен\nКоммит: " + git.hash()
                + (git.message().isBlank() ? "" : "\n" + git.message());
        try {
            client.sendMessage(config.adminChatId(), text, null, null);
        } catch (TelegramApiException e) {
            log.warn("Failed to send startup notification: {}", e.getMessage());
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return;
        }

        JsonNode chat = message.path("chat");
        long chatId = chat.path("id").asLong();
        String chatType = chat.path("type").asText("");
        boolean isGroup = "group".equals(chatType) || "supergroup".equals(chatType);

        if (isGroup) {
            JsonNode from = message.path("from");
            if (!from.isMissingNode() && !from.path("is_bot").asBoolean(false)) {
                store.upsertUser(chatId, from.path("id").asLong(),
                        textOrNull(from, "username"), textOrNull(from, "first_name"), textOrNull(from, "last_name"));
            }

            JsonNode newMembers = message.path("new_chat_members");
            if (newMembers.isArray()) {
                for (JsonNode member : newMembers) {
                    if (!member.path("is_bot").asBoolean(false)) {
                        store.upsertUser(chatId, member.path("id").asLong(),
                                textOrNull(member, "username"), textOrNull(member, "first_name"), textOrNull(member, "last_name"));
                    }
                }
            }

            JsonNode leftMember = message.path("left_chat_member");
            if (!leftMember.isMissingNode() && !leftMember.isNull()) {
                store.removeUser(chatId, leftMember.path("id").asLong());
            }
        }

        JsonNode textNode = message.path("text");
        if (textNode.isMissingNode() || textNode.isNull()) {
            return;
        }
        if (!textNode.asText().startsWith("/")) {
            return;
        }

        commandHandler.handle(message, isGroup);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
