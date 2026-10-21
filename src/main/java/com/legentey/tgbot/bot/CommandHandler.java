package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.storage.ApprovalSettingsStore;
import com.legentey.tgbot.storage.ChatUser;
import com.legentey.tgbot.storage.UserStore;
import com.legentey.tgbot.telegram.TelegramApiClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CommandHandler {

    private final TelegramApiClient client;
    private final UserStore store;
    private final StickerService stickerService;
    private final QuoteEmojiPicker emojiPicker;
    private final DeletionVote deletionVote;
    private final ApprovalSettingsStore approvalSettingsStore;
    private final PlainStickerService plainStickerService;
    private final StickerEmojiPicker stickerEmojiPicker;
    private final ApprovalSettingsStore stickerApprovalSettingsStore;
    private final Config config;

    public CommandHandler(TelegramApiClient client, UserStore store, StickerService stickerService,
                          QuoteEmojiPicker emojiPicker, DeletionVote deletionVote,
                          ApprovalSettingsStore approvalSettingsStore, PlainStickerService plainStickerService,
                          StickerEmojiPicker stickerEmojiPicker,
                          ApprovalSettingsStore stickerApprovalSettingsStore, Config config) {
        this.client = client;
        this.store = store;
        this.stickerService = stickerService;
        this.emojiPicker = emojiPicker;
        this.deletionVote = deletionVote;
        this.approvalSettingsStore = approvalSettingsStore;
        this.plainStickerService = plainStickerService;
        this.stickerEmojiPicker = stickerEmojiPicker;
        this.stickerApprovalSettingsStore = stickerApprovalSettingsStore;
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
            case "add" -> handleAdd(message, chatId, isGroup);
            case "remove" -> handleRemove(message, chatId, isGroup);
            case "q" -> handleQ(message, chatId, isGroup, args);
            case "q_get_approve_count" -> handleQGetApproveCount(message, chatId);
            case "q_set_approve_count" -> handleQSetApproveCount(message, chatId, args);
            case "q_get_approve_timeout" -> handleQGetApproveTimeout(message, chatId);
            case "q_set_approve_timeout" -> handleQSetApproveTimeout(message, chatId, args);
            case "q_rerender" -> handleQRerender(message, chatId);
            case "q_get_width" -> handleQGetWidth(message, chatId);
            case "q_set_width" -> handleQSetWidth(message, chatId, args);
            case "s" -> handleS(message, chatId, isGroup, args);
            case "del" -> handleDel(message, chatId, isGroup);
            case "s_get_approve_count" -> handleSGetApproveCount(message, chatId);
            case "s_set_approve_count" -> handleSSetApproveCount(message, chatId, args);
            case "s_get_approve_timeout" -> handleSGetApproveTimeout(message, chatId);
            case "s_set_approve_timeout" -> handleSSetApproveTimeout(message, chatId, args);
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

    /** Chat admins only: puts people the bot can't see on its own onto this chat's /all list. */
    private void handleAdd(JsonNode message, long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        if (!isChatAdmin(message, chatId)) {
            client.sendMessage(chatId, "Добавлять участников в список /all могут только админы чата.", null, messageId);
            return;
        }
        List<ChatUser> mentioned = mentionedUsers(message);
        if (mentioned.isEmpty()) {
            client.sendMessage(chatId, "Укажите участников через @, например: /add @username1 @username2",
                    null, messageId);
            return;
        }
        List<String> added = new ArrayList<>();
        List<String> alreadyThere = new ArrayList<>();
        for (ChatUser user : mentioned) {
            boolean isNew;
            if (user.userId() != null) {
                isNew = !store.hasUser(chatId, user.userId());
                store.upsertUser(chatId, user.userId(), user.username(), user.firstName(), user.lastName());
            } else {
                isNew = store.addUsername(chatId, user.username());
            }
            (isNew ? added : alreadyThere).add(label(user));
        }
        client.sendMessage(chatId, summary("Добавлены", added, "Уже были в списке", alreadyThere), null, messageId);
    }

    /**
     * Chat admins only: drops people from this chat's /all list — for someone who left without
     * the bot noticing. Anyone removed who later writes in the chat is picked up again as usual.
     */
    private void handleRemove(JsonNode message, long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        if (!isChatAdmin(message, chatId)) {
            client.sendMessage(chatId, "Удалять участников из списка /all могут только админы чата.", null, messageId);
            return;
        }
        List<ChatUser> mentioned = mentionedUsers(message);
        if (mentioned.isEmpty()) {
            client.sendMessage(chatId, "Укажите участников через @, например: /remove @username1 @username2",
                    null, messageId);
            return;
        }
        List<String> removed = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (ChatUser user : mentioned) {
            boolean wasThere = user.userId() != null
                    ? store.removeUser(chatId, user.userId())
                    : store.removeUsername(chatId, user.username());
            (wasThere ? removed : notFound).add(label(user));
        }
        client.sendMessage(chatId, summary("Удалены", removed, "Не найдены в списке", notFound), null, messageId);
    }

    /**
     * Everyone named in a command's text, deduplicated, in order. "@username" arrives as a "mention"
     * entity; someone without a username, picked from the client's @-suggestions, arrives as a
     * "text_mention" carrying the whole user — id included.
     */
    private static List<ChatUser> mentionedUsers(JsonNode message) {
        long chatId = message.path("chat").path("id").asLong();
        String text = message.path("text").asText();
        Map<String, ChatUser> byKey = new LinkedHashMap<>();
        for (JsonNode entity : message.path("entities")) {
            switch (entity.path("type").asText("")) {
                case "mention" -> {
                    // Offsets count UTF-16 code units, which is exactly how a Java String indexes.
                    int offset = entity.path("offset").asInt();
                    String username = text.substring(offset + 1, offset + entity.path("length").asInt());
                    byKey.putIfAbsent("@" + username.toLowerCase(Locale.ROOT),
                            new ChatUser(chatId, null, username, null, null));
                }
                case "text_mention" -> {
                    JsonNode user = entity.path("user");
                    long userId = user.path("id").asLong();
                    byKey.putIfAbsent("#" + userId, new ChatUser(chatId, userId, textOrNull(user, "username"),
                            textOrNull(user, "first_name"), textOrNull(user, "last_name")));
                }
                default -> {
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String label(ChatUser user) {
        return user.username() != null ? "@" + user.username() : user.displayName();
    }

    /** Plain text, one line per non-empty group, e.g. "Добавлены: @a, @b". */
    private static String summary(String firstTitle, List<String> first, String secondTitle, List<String> second) {
        List<String> lines = new ArrayList<>();
        if (!first.isEmpty()) {
            lines.add(firstTitle + ": " + String.join(", ", first));
        }
        if (!second.isEmpty()) {
            lines.add(secondTitle + ": " + String.join(", ", second));
        }
        return String.join("\n", lines);
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
        if (StickerService.quotedText(replyTo).isBlank()) {
            // Checked here, before the emoji picker, so nobody picks an emoji only to be told afterward
            // that there was nothing to quote.
            client.sendMessage(chatId, "В этом сообщении нет текста — цитировать нечего.", null, messageId);
            return;
        }
        if (emoji.isBlank()) {
            emojiPicker.prompt(chatId, message.path("from").path("id").asLong(), messageId, replyTo);
            return;
        }
        // Sticker packs are always owned by the admin account, regardless of who calls /q.
        stickerService.createQuote(chatId, config.adminChatId(), replyTo, emoji);
    }

    /** Admin-only, hidden: reports how many others must approve a /del of a /q sticker. */
    private void handleQGetApproveCount(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Нужно подтверждений для удаления: "
                + approvalSettingsStore.getApproveCount(), null, null);
    }

    /** Admin-only, hidden: changes how many others must approve a /del of a /q sticker. */
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
                        ? "Подтверждения выключены: /del удаляет цитату сразу."
                        : "Нужно подтверждений для удаления: " + count + ".",
                null, null);
    }

    /** Admin-only, hidden: reports how long a /del vote on a /q sticker stays open. */
    private void handleQGetApproveTimeout(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Таймаут голосования: "
                + approvalSettingsStore.getApproveTimeoutSeconds() + " сек.", null, null);
    }

    /**
     * Admin-only, hidden: changes how long a /del vote on a /q sticker stays open.
     * Votes already under way keep the timeout they opened with.
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

    private void handleS(JsonNode message, long chatId, boolean isGroup, String emoji) {
        if (!isGroup) {
            return;
        }
        if (config.adminChatId() == null) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        JsonNode replyTo = message.path("reply_to_message");
        if (replyTo.isMissingNode() || replyTo.isNull() || !hasStickerSource(replyTo)) {
            // This is how a bare /s tapped in Telegram's command menu arrives: sent at once, with nothing to add.
            // Whether the source itself is actually usable (right format, right size) is checked later, once an
            // emoji is known — same as /q defers "was there any text to quote" until stickerService.createQuote.
            client.sendMessage(chatId,
                    "Ответьте командой /s на стикер или на файл с картинкой (PNG/WEBP, отправленной именно как файл, "
                            + "не сжатым фото), которую нужно добавить в стикерпак чата.",
                    null, messageId);
            return;
        }
        if (emoji.isBlank()) {
            stickerEmojiPicker.promptOrConfirmSourceEmoji(chatId, message.path("from").path("id").asLong(), messageId,
                    replyTo);
            return;
        }
        // Sticker packs are always owned by the admin account, regardless of who calls /s.
        plainStickerService.addSticker(chatId, config.adminChatId(), replyTo, emoji);
    }

    /** Structural check only — a sticker/photo/document is there at all, not that it's a usable one. */
    private static boolean hasStickerSource(JsonNode message) {
        JsonNode sticker = message.path("sticker");
        if (!sticker.isMissingNode() && !sticker.isNull()) {
            return true;
        }
        JsonNode document = message.path("document");
        return !document.isMissingNode() && !document.isNull();
    }

    private void handleDel(JsonNode message, long chatId, boolean isGroup) {
        if (!isGroup) {
            return;
        }
        long messageId = message.path("message_id").asLong();
        JsonNode stickerMessage = message.path("reply_to_message");
        JsonNode sticker = stickerMessage.path("sticker");
        if (sticker.isMissingNode() || sticker.isNull()) {
            client.sendMessage(chatId, "Ответьте командой /del на стикер, который нужно удалить.",
                    null, messageId);
            return;
        }
        deletionVote.requestDeletion(chatId, message.path("from").path("id").asLong(), messageId, stickerMessage);
    }

    /** Admin-only, hidden: reports how many others must approve a /del of an /s sticker. */
    private void handleSGetApproveCount(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Нужно подтверждений для удаления: "
                + stickerApprovalSettingsStore.getApproveCount(), null, null);
    }

    /** Admin-only, hidden: changes how many others must approve a /del of an /s sticker. */
    private void handleSSetApproveCount(JsonNode message, long chatId, String args) {
        if (!isAdmin(message)) {
            return;
        }
        Integer count = parseNonNegativeInt(args);
        if (count == null) {
            client.sendMessage(chatId, "Укажите неотрицательное число, например: /s_set_approve_count 2", null, null);
            return;
        }
        stickerApprovalSettingsStore.setApproveCount(count);
        client.sendMessage(chatId, count == 0
                        ? "Подтверждения выключены: /del удаляет стикер сразу."
                        : "Нужно подтверждений для удаления: " + count + ".",
                null, null);
    }

    /** Admin-only, hidden: reports how long a /del vote on an /s sticker stays open. */
    private void handleSGetApproveTimeout(JsonNode message, long chatId) {
        if (!isAdmin(message)) {
            return;
        }
        client.sendMessage(chatId, "Таймаут голосования: "
                + stickerApprovalSettingsStore.getApproveTimeoutSeconds() + " сек.", null, null);
    }

    /**
     * Admin-only, hidden: changes how long a /del vote on an /s sticker stays open.
     * Votes already under way keep the timeout they opened with.
     */
    private void handleSSetApproveTimeout(JsonNode message, long chatId, String args) {
        if (!isAdmin(message)) {
            return;
        }
        Integer seconds = parsePositiveInt(args);
        if (seconds == null) {
            client.sendMessage(chatId, "Укажите положительное число секунд, например: /s_set_approve_timeout 300",
                    null, null);
            return;
        }
        stickerApprovalSettingsStore.setApproveTimeoutSeconds(seconds);
        client.sendMessage(chatId, "Таймаут голосования: " + seconds + " сек.", null, null);
    }

    private boolean isAdmin(JsonNode message) {
        Long adminId = config.adminChatId();
        long fromId = message.path("from").path("id").asLong();
        return adminId != null && fromId == adminId;
    }

    /** An admin of this particular chat — unlike {@link #isAdmin}, which means the bot's one owner. */
    private boolean isChatAdmin(JsonNode message, long chatId) {
        // An admin posting anonymously ("as the group") arrives with the group itself as sender_chat.
        if (message.path("sender_chat").path("id").asLong(0) == chatId) {
            return true;
        }
        long fromId = message.path("from").path("id").asLong();
        String status = client.getChatMember(chatId, fromId).path("status").asText("");
        return "creator".equals(status) || "administrator".equals(status);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
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
