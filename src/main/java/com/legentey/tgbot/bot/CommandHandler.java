package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.storage.ApprovalSettingsStore;
import com.legentey.tgbot.storage.ChatUser;
import com.legentey.tgbot.storage.UserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;

import java.util.List;
import java.util.Locale;

public class CommandHandler {

    private final TelegramApiClient client;
    private final UserStore store;
    private final StickerService stickerService;
    private final QuoteEmojiPicker emojiPicker;
    private final QuoteDeletionVote deletionVote;
    private final ApprovalSettingsStore approvalSettingsStore;
    private final Config config;

    public CommandHandler(TelegramApiClient client, UserStore store, StickerService stickerService,
                          QuoteEmojiPicker emojiPicker, QuoteDeletionVote deletionVote,
                          ApprovalSettingsStore approvalSettingsStore, Config config) {
        this.client = client;
        this.store = store;
        this.stickerService = stickerService;
        this.emojiPicker = emojiPicker;
        this.deletionVote = deletionVote;
        this.approvalSettingsStore = approvalSettingsStore;
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
            case "q_rand" -> handleQRand(chatId, isGroup);
            case "q_del" -> handleQDel(message, chatId, isGroup);
            case "q_get_approve_count" -> handleQGetApproveCount(message, chatId);
            case "q_set_approve_count" -> handleQSetApproveCount(message, chatId, args);
            case "q_get_approve_timeout" -> handleQGetApproveTimeout(message, chatId);
            case "q_set_approve_timeout" -> handleQSetApproveTimeout(message, chatId, args);
            case "q_rerender" -> handleQRerender(message, chatId);
            case "q_get_width" -> handleQGetWidth(message, chatId);
            case "q_set_width" -> handleQSetWidth(message, chatId, args);
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
        if (config.adminChatId() == null) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        JsonNode replyTo = message.path("reply_to_message");
        if (replyTo.isMissingNode() || replyTo.isNull()) {
            // This is how a /q tapped in Telegram's command menu arrives: sent at once, with nothing to quote.
            client.sendMessage(chatId, "Ответьте командой /q на сообщение, из которого нужно сделать стикер.",
                    null, messageId);
            return;
        }
        if (emoji.isBlank()) {
            emojiPicker.prompt(chatId, message.path("from").path("id").asLong(), messageId, replyTo);
            return;
        }
        // Sticker packs are always owned by the admin account, regardless of who calls /q.
        stickerService.createQuote(chatId, config.adminChatId(), replyTo, emoji);
    }

    private void handleQRand(long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }
        stickerService.sendRandomQuote(chatId);
    }

    private void handleQDel(JsonNode message, long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        JsonNode sticker = message.path("reply_to_message").path("sticker");
        if (sticker.isMissingNode() || sticker.isNull()) {
            client.sendMessage(chatId, "Ответьте командой /q_del на стикер-цитату, которую нужно удалить.",
                    null, messageId);
            return;
        }
        deletionVote.requestDeletion(chatId, message.path("from").path("id").asLong(), messageId, sticker);
    }

    /** Admin-only, hidden: reports how many others must approve a /q_del. */
    private void handleQGetApproveCount(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Нужно подтверждений для удаления: "
                + approvalSettingsStore.getApproveCount(), null, null);
    }

    /** Admin-only, hidden: changes how many others must approve a /q_del. */
    private void handleQSetApproveCount(JsonNode message, long chatId, String args) {
        if (!isAdmin(message)) {
            return;
        }
        Integer count = parseNonNegativeInt(args);
        if (count == null) {
            client.sendMessage(chatId, "Укажите неотрицательное число, например: /q_set_approve_count 2", null, null);
            return;
        }
        approvalSettingsStore.setApproveCount(count);
        client.sendMessage(chatId, count == 0
                        ? "Подтверждения выключены: /q_del удаляет цитату сразу."
                        : "Нужно подтверждений для удаления: " + count + ".",
                null, null);
    }

    /** Admin-only, hidden: reports how long a /q_del vote stays open. */
    private void handleQGetApproveTimeout(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Таймаут голосования: "
                + approvalSettingsStore.getApproveTimeoutSeconds() + " сек.", null, null);
    }

    /**
     * Admin-only, hidden: changes how long a /q_del vote stays open. Votes already
     * under way keep the timeout they opened with.
     */
    private void handleQSetApproveTimeout(JsonNode message, long chatId, String args) {
        if (!isAdmin(message)) {
            return;
        }
        Integer seconds = parsePositiveInt(args);
        if (seconds == null) {
            client.sendMessage(chatId, "Укажите положительное число секунд, например: /q_set_approve_timeout 300",
                    null, null);
            return;
        }
        approvalSettingsStore.setApproveTimeoutSeconds(seconds);
        client.sendMessage(chatId, "Таймаут голосования: " + seconds + " сек.", null, null);
    }

    /**
     * Admin-only, hidden: re-renders every quote sticker in every pack with the
     * current render width and swaps them in place. Runs off-thread since a full
     * rerender across all packs can take a while and shouldn't stall the
     * update-polling loop.
     */
    private void handleQRerender(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Перерендериваю все стикеры-цитаты...", null, null);
        new Thread(() -> {
            StickerService.RerenderResult result = stickerService.rerenderAll();
            client.sendMessage(chatId,
                    "Готово: обновлено " + result.updated() + ", ошибок " + result.failed()
                            + ", удалено устаревших " + result.removed() + ".", null, null);
        }, "rerender-quotes").start();
    }

    /** Admin-only, hidden: reports the render width new /q stickers use. */
    private void handleQGetWidth(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Текущая ширина рендера: " + stickerService.getRenderWidth(), null, null);
    }

    /**
     * Admin-only, hidden: changes the render width for future /q stickers. Doesn't
     * touch existing stickers by itself — follow up with /q_rerender for those.
     */
    private void handleQSetWidth(JsonNode message, long chatId, String args) {
        if (!isAdmin(message)) {
            return;
        }
        Integer width = parsePositiveInt(args);
        if (width == null) {
            client.sendMessage(chatId, "Укажите положительное число, например: /q_set_width 280", null, null);
            return;
        }
        stickerService.setRenderWidth(width);
        client.sendMessage(chatId,
                "Ширина рендера установлена: " + width + ". Существующие стикеры не тронуты — для них /q_rerender.",
                null, null);
    }

    private boolean isAdmin(JsonNode message) {
        Long adminId = config.adminChatId();
        long fromId = message.path("from").path("id").asLong();
        return adminId != null && fromId == adminId;
    }

    private static Integer parsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Zero is meaningful here: it turns deletion approvals off entirely. */
    private static Integer parseNonNegativeInt(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
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
