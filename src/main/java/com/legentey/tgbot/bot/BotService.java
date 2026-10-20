package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.GitInfo;
import com.legentey.tgbot.quoteapi.QuoteApiClient;
import com.legentey.tgbot.storage.ApprovalSettingsStore;
import com.legentey.tgbot.storage.DeployStore;
import com.legentey.tgbot.storage.QuoteStickerStore;
import com.legentey.tgbot.storage.RenderSettingsStore;
import com.legentey.tgbot.storage.StickerPackStore;
import com.legentey.tgbot.storage.UpdateOffsetStore;
import com.legentey.tgbot.storage.UserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BotService {

    private static final List<Map<String, String>> COMMANDS = List.of(
            Map.of("command", "all", "description", "Упомянуть всех известных участников чата"),
            Map.of("command", "q", "description", "Ответом на сообщение — сделать из него стикер-цитату"),
            Map.of("command", "s", "description", "Ответом на стикер (.png, .webp) — добавить его в стикерпак чата"),
            Map.of("command", "del", "description", "Ответом на стикер — удалить его с согласия чата"),
            Map.of("command", "yers", "description", "Упомянуть всех известных участников чата")
    );

    /** Only ever useful to one person, so published to that person's own chat scope. */
    private static final List<Map<String, String>> ADMIN_COMMANDS = List.of(
            Map.of("command", "q_rerender", "description", "Перерендерить все стикеры-цитаты"),
            Map.of("command", "q_get_width", "description", "Текущая ширина рендера"),
            Map.of("command", "q_set_width", "description", "Задать ширину рендера"),
            Map.of("command", "q_get_approve_count", "description", "Сколько подтверждений нужно для /del стикера-цитаты"),
            Map.of("command", "q_set_approve_count", "description", "Задать число подтверждений для /del стикера-цитаты"),
            Map.of("command", "q_get_approve_timeout", "description", "Таймаут голосования /del стикера-цитаты в секундах"),
            Map.of("command", "q_set_approve_timeout", "description", "Задать таймаут голосования /del стикера-цитаты"),
            Map.of("command", "s_get_approve_count", "description", "Сколько подтверждений нужно для /del обычного стикера"),
            Map.of("command", "s_set_approve_count", "description", "Задать число подтверждений для /del обычного стикера"),
            Map.of("command", "s_get_approve_timeout", "description", "Таймаут голосования /del обычного стикера в секундах"),
            Map.of("command", "s_set_approve_timeout", "description", "Задать таймаут голосования /del обычного стикера")
    );

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final TelegramApiClient client;
    private final UserStore store;
    private final StickerPackStore stickerPackStore;
    private final QuoteStickerStore quoteStickerStore;
    private final RenderSettingsStore renderSettingsStore;
    private final DeployStore deployStore;
    private final UpdateOffsetStore updateOffsetStore;
    private final ApprovalSettingsStore approvalSettingsStore;
    private final StickerPackStore regularStickerPackStore;
    private final ApprovalSettingsStore stickerApprovalSettingsStore;
    private final Config config;

    private CommandHandler commandHandler;
    private StickerService stickerService;
    private QuoteEmojiPicker emojiPicker;
    private DeletionVote deletionVote;
    private PlainStickerService plainStickerService;
    private StickerEmojiPicker stickerEmojiPicker;

    private volatile boolean running = true;

    public BotService(TelegramApiClient client, UserStore store, StickerPackStore stickerPackStore,
                      QuoteStickerStore quoteStickerStore, RenderSettingsStore renderSettingsStore,
                      DeployStore deployStore, UpdateOffsetStore updateOffsetStore,
                      ApprovalSettingsStore approvalSettingsStore, StickerPackStore regularStickerPackStore,
                      ApprovalSettingsStore stickerApprovalSettingsStore, Config config) {
        this.client = client;
        this.store = store;
        this.stickerPackStore = stickerPackStore;
        this.quoteStickerStore = quoteStickerStore;
        this.renderSettingsStore = renderSettingsStore;
        this.deployStore = deployStore;
        this.updateOffsetStore = updateOffsetStore;
        this.approvalSettingsStore = approvalSettingsStore;
        this.regularStickerPackStore = regularStickerPackStore;
        this.stickerApprovalSettingsStore = stickerApprovalSettingsStore;
        this.config = config;
    }

    public void stop() {
        running = false;
    }

    public void run() {
        JsonNode me = client.getMe();
        String botUsername = me.path("username").asText("");
        log.info("Logged in as @{} (id={})", botUsername, me.path("id").asLong());

        QuoteApiClient quoteApiClient = new QuoteApiClient(config.quoteApiUrl(), config.botToken());
        this.stickerService = new StickerService(client, quoteApiClient, stickerPackStore, quoteStickerStore,
                renderSettingsStore, botUsername);
        this.emojiPicker = new QuoteEmojiPicker(client, stickerService, new PendingEmojiPrompt(), config);
        this.plainStickerService = new PlainStickerService(client, regularStickerPackStore, botUsername);
        this.stickerEmojiPicker = new StickerEmojiPicker(client, plainStickerService, new PendingEmojiPrompt(),
                config);
        this.deletionVote = new DeletionVote(client, stickerService, plainStickerService, approvalSettingsStore,
                stickerApprovalSettingsStore, new PendingDeletions());
        this.commandHandler = new CommandHandler(client, store, stickerService, emojiPicker, deletionVote,
                approvalSettingsStore, plainStickerService, stickerEmojiPicker,
                stickerApprovalSettingsStore, config);

        try {
            client.setMyCommands(COMMANDS);
            log.info("Registered command menu ({} commands)", COMMANDS.size());
        } catch (TelegramApiException e) {
            log.warn("Failed to register command menu: {}", e.getMessage());
        }
        clearPrivateChatCommandMenu();
        registerAdminCommandMenu();

        notifyStartup();

        long offset = resolveStartOffset();
        while (running) {
            try {
                JsonNode updates = client.getUpdates(offset, config.getUpdatesTimeoutSeconds());
                for (JsonNode update : updates) {
                    long updateId = update.path("update_id").asLong();
                    offset = updateId + 1;
                    // Claim the update before running it. Telegram only drops an update once
                    // getUpdates is called with a higher offset, so without this a crash between
                    // creating a sticker and the next poll makes the restarted bot create it again.
                    updateOffsetStore.saveOffset(offset);
                    try {
                        handleUpdate(update);
                    } catch (Exception e) {
                        log.error("Failed to handle update {}", updateId, e);
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

    /**
     * Empties the menu for private chats. Every public command is group-only, so
     * offering them one-to-one just invites taps that do nothing. The admin's own
     * chat keeps its menu regardless: for a private chat Telegram resolves scopes
     * as chat, then all_private_chats, then default, so the narrower one wins.
     */
    private void clearPrivateChatCommandMenu() {
        try {
            client.setMyCommands(List.of(), Map.of("type", "all_private_chats"));
            log.info("Cleared the command menu for private chats");
        } catch (TelegramApiException e) {
            log.warn("Failed to clear the private chat command menu: {}", e.getMessage());
        }
    }

    /**
     * Publishes the hidden commands as a menu of their own, scoped to the admin's
     * private chat with the bot, so nobody else is offered them.
     *
     * <p>The public commands are deliberately left out of that scope: a chat scope
     * replaces the default menu rather than adding to it, and every public command
     * is group-only anyway, so in a private chat they would just be dead entries.
     */
    private void registerAdminCommandMenu() {
        if (config.adminChatId() == null) {
            return;
        }
        try {
            client.setMyCommands(ADMIN_COMMANDS, Map.of("type", "chat", "chat_id", config.adminChatId()));
            log.info("Registered admin command menu ({} commands)", ADMIN_COMMANDS.size());
        } catch (TelegramApiException e) {
            log.warn("Failed to register admin command menu: {}", e.getMessage());
        }
    }

    /**
     * Picks up where the previous run left off. With nothing stored — a first run, or
     * the first run on a build that has this store — Telegram's backlog can still hold
     * up to 24 hours of unconfirmed updates that an earlier build already acted on, so
     * it is dropped rather than replayed: getUpdates(-1) returns only the newest one.
     */
    private long resolveStartOffset() {
        Optional<Long> stored = updateOffsetStore.lastOffset();
        if (stored.isPresent()) {
            log.info("Resuming at update offset {}", stored.get());
            return stored.get();
        }
        long offset = 0;
        try {
            JsonNode newest = client.getUpdates(-1, 0);
            if (newest.isArray() && !newest.isEmpty()) {
                offset = newest.get(newest.size() - 1).path("update_id").asLong() + 1;
            }
        } catch (TelegramApiException e) {
            log.warn("Could not read the pending update backlog: {}", e.getMessage());
            return offset;
        }
        updateOffsetStore.saveOffset(offset);
        log.info("No stored update offset; dropped the pending backlog and starting at {}", offset);
        return offset;
    }

    private void notifyStartup() {
        if (config.adminChatId() == null) {
            log.info("ADMIN_CHAT_ID not set, skipping startup notification");
            return;
        }
        GitInfo git = GitInfo.detect();
        String text = StartupMessage.build(git, deployStore.lastAnnouncedCommit());
        try {
            client.sendMessage(config.adminChatId(), text, null, null);
            // Only after Telegram accepted it: a failed send must leave the range
            // unclaimed so the next startup reports it instead of skipping it.
            deployStore.saveAnnouncedCommit(git.hash());
        } catch (TelegramApiException e) {
            log.warn("Failed to send startup notification: {}", e.getMessage());
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode callbackQuery = update.path("callback_query");
        if (!callbackQuery.isMissingNode() && !callbackQuery.isNull()) {
            // Each handler recognizes its own callback_data, so the prefixes stay
            // its business; the quote picker is last because it also clears the
            // spinner on anything nobody claims.
            if (!deletionVote.handleCallback(callbackQuery) && !stickerEmojiPicker.handleCallback(callbackQuery)) {
                emojiPicker.handleCallback(callbackQuery);
            }
            return;
        }

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
        if (emojiPicker.handleCustomEmojiReply(message) || stickerEmojiPicker.handleCustomEmojiReply(message)) {
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
