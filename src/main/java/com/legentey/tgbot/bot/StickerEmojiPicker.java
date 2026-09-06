package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The emoji picker behind a bare /s — the same idea as {@link QuoteEmojiPicker},
 * but for adding an existing sticker to the chat's own sticker pack instead of
 * rendering a quote card.
 *
 * <p>A bare /s doesn't necessarily need the picker at all: if the replied-to
 * sticker already carries its own emoji (Telegram's Sticker.emoji field), that's
 * offered as a one-tap "add with this emoji?" confirmation first — see
 * {@link #promptOrConfirmSourceEmoji}. Only a "no" there, or a source with no
 * emoji of its own (e.g. a plain PNG/WEBP file), falls through to the regular
 * 24-preset-plus-custom grid.
 *
 * <p>Unlike {@link QuoteEmojiPicker#handleCallback}, this isn't the last handler
 * in {@link BotService}'s callback chain, so it only claims its own callback_data
 * ("s|..." and "sconf|...") and leaves anything else untouched.
 */
class StickerEmojiPicker {

    private static final Logger log = LoggerFactory.getLogger(StickerEmojiPicker.class);

    private static final String CALLBACK_PREFIX = "s|";
    private static final String CUSTOM_CALLBACK = "s|*";
    private static final String CONFIRM_PREFIX = "sconf|";
    private static final String CONFIRM_YES = "sconf|yes";
    private static final String CONFIRM_NO = "sconf|no";
    private static final int COLUMNS = 6;

    private static final List<String> EMOJIS = List.of(
            "😂", "🔥", "💀", "🤡", "😭", "👍",
            "🤔", "❤️", "🥴", "😎", "🙏", "💩",
            "😱", "🤮", "🥺", "😐", "🤯", "👀",
            "🤝", "🫡", "😈", "🍿", "💯", "🤣"
    );

    private static final String PICK_PROMPT = "Выберите смайл для стикера:";
    private static final String CUSTOM_BUTTON = "Свой смайл";
    private static final String CUSTOM_PROMPT = "Отправьте свой смайл ответом на это сообщение.";
    private static final String YES_BUTTON = "Да";
    private static final String NO_BUTTON = "Нет";
    private static final String EXPIRED = "Кнопка устарела — вызовите /s заново.";
    private static final String NOT_YOURS = "Это не ваша кнопка.";
    private static final String NOT_AN_EMOJI = "Это не похоже на смайл — пришлите один смайл.";

    private final TelegramApiClient client;
    private final PlainStickerService stickerService;
    private final PendingEmojiPrompt pending;
    private final Config config;

    StickerEmojiPicker(TelegramApiClient client, PlainStickerService stickerService, PendingEmojiPrompt pending,
                       Config config) {
        this.client = client;
        this.stickerService = stickerService;
        this.pending = pending;
        this.config = config;
    }

    /** Answers a bare /s with the emoji keyboard, remembering which sticker is being added. */
    void prompt(long chatId, long requesterUserId, long commandMessageId, JsonNode stickerMessage) {
        JsonNode sent = client.sendMessage(chatId, PICK_PROMPT, null, commandMessageId, markup());
        pending.put(chatId, sent.path("message_id").asLong(), stickerMessage, requesterUserId, commandMessageId);
    }

    /**
     * Entry point for a bare /s. If the replied-to sticker already has its own
     * emoji, offers to reuse it instead of going straight to the picker — a
     * plain PNG/WEBP source (no "sticker" field at all) never has one, so this
     * falls through to {@link #prompt} immediately for those.
     */
    void promptOrConfirmSourceEmoji(long chatId, long requesterUserId, long commandMessageId, JsonNode stickerMessage) {
        String sourceEmoji = stickerMessage.path("sticker").path("emoji").asText("").trim();
        if (sourceEmoji.isBlank()) {
            prompt(chatId, requesterUserId, commandMessageId, stickerMessage);
            return;
        }
        String text = "У этого стикера уже есть эмодзи: " + sourceEmoji + ". Добавить с этим эмодзи?";
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", YES_BUTTON, "callback_data", CONFIRM_YES),
                Map.of("text", NO_BUTTON, "callback_data", CONFIRM_NO))));
        JsonNode sent = client.sendMessage(chatId, text, null, commandMessageId, markup);
        pending.put(chatId, sent.path("message_id").asLong(), stickerMessage, requesterUserId, commandMessageId);
    }

    /** Handles a tap on one of the keyboard's buttons. Returns false if the data isn't this picker's. */
    boolean handleCallback(JsonNode callbackQuery) {
        String data = callbackQuery.path("data").asText("");
        if (data.startsWith(CONFIRM_PREFIX)) {
            return handleConfirmCallback(callbackQuery, data);
        }
        if (!data.startsWith(CALLBACK_PREFIX)) {
            return false;
        }
        String callbackId = callbackQuery.path("id").asText();
        if (config.adminChatId() == null) {
            client.answerCallbackQuery(callbackId, null, false);
            return true;
        }

        JsonNode promptMessage = callbackQuery.path("message");
        long chatId = promptMessage.path("chat").path("id").asLong();
        long promptMessageId = promptMessage.path("message_id").asLong();
        long userId = callbackQuery.path("from").path("id").asLong();

        Optional<PendingEmojiPrompt.Pending> found = pending.peek(chatId, promptMessageId);
        if (found.isEmpty()) {
            client.answerCallbackQuery(callbackId, EXPIRED, false);
            deleteQuietly(chatId, promptMessageId);
            return true;
        }
        if (found.get().requesterUserId() != userId) {
            client.answerCallbackQuery(callbackId, NOT_YOURS, false);
            return true;
        }
        client.answerCallbackQuery(callbackId, null, false);

        pending.remove(chatId, promptMessageId);
        deleteQuietly(chatId, promptMessageId);
        if (CUSTOM_CALLBACK.equals(data)) {
            askForCustomEmoji(chatId, found.get());
            return true;
        }
        // Sticker packs are always owned by the admin account, regardless of who tapped.
        stickerService.addSticker(chatId, config.adminChatId(), found.get().targetMessage(),
                data.substring(CALLBACK_PREFIX.length()));
        return true;
    }

    /** Handles a tap on the "add with the source sticker's own emoji?" confirmation. */
    private boolean handleConfirmCallback(JsonNode callbackQuery, String data) {
        String callbackId = callbackQuery.path("id").asText();
        if (config.adminChatId() == null) {
            client.answerCallbackQuery(callbackId, null, false);
            return true;
        }

        JsonNode promptMessage = callbackQuery.path("message");
        long chatId = promptMessage.path("chat").path("id").asLong();
        long promptMessageId = promptMessage.path("message_id").asLong();
        long userId = callbackQuery.path("from").path("id").asLong();

        Optional<PendingEmojiPrompt.Pending> found = pending.peek(chatId, promptMessageId);
        if (found.isEmpty()) {
            client.answerCallbackQuery(callbackId, EXPIRED, false);
            deleteQuietly(chatId, promptMessageId);
            return true;
        }
        if (found.get().requesterUserId() != userId) {
            client.answerCallbackQuery(callbackId, NOT_YOURS, false);
            return true;
        }
        client.answerCallbackQuery(callbackId, null, false);

        PendingEmojiPrompt.Pending p = found.get();
        pending.remove(chatId, promptMessageId);
        deleteQuietly(chatId, promptMessageId);

        if (CONFIRM_NO.equals(data)) {
            prompt(chatId, p.requesterUserId(), p.commandMessageId(), p.targetMessage());
            return true;
        }
        // Re-derived rather than carried in Pending: the source message is immutable, so this
        // is exactly the same value promptOrConfirmSourceEmoji already confirmed was non-blank.
        String emoji = p.targetMessage().path("sticker").path("emoji").asText("").trim();
        if (emoji.isBlank()) {
            prompt(chatId, p.requesterUserId(), p.commandMessageId(), p.targetMessage());
            return true;
        }
        stickerService.addSticker(chatId, config.adminChatId(), p.targetMessage(), emoji);
        return true;
    }

    /**
     * Consumes a reply to a pending "send me an emoji" prompt. Returns false if
     * the message is something else entirely, so normal handling can continue.
     */
    boolean handleCustomEmojiReply(JsonNode message) {
        if (config.adminChatId() == null) {
            return false;
        }
        JsonNode replyTo = message.path("reply_to_message");
        if (replyTo.isMissingNode() || replyTo.isNull()) {
            return false;
        }
        long chatId = message.path("chat").path("id").asLong();
        long promptMessageId = replyTo.path("message_id").asLong();
        Optional<PendingEmojiPrompt.Pending> found = pending.peek(chatId, promptMessageId);
        if (found.isEmpty() || found.get().requesterUserId() != message.path("from").path("id").asLong()) {
            return false;
        }

        String emoji = message.path("text").asText("").trim();
        if (!EmojiValidation.isPlausibleEmoji(emoji)) {
            // Keep the entry alive so the user can simply reply again.
            client.sendMessage(chatId, NOT_AN_EMOJI, null, message.path("message_id").asLong());
            return true;
        }
        pending.remove(chatId, promptMessageId);
        deleteQuietly(chatId, promptMessageId);
        stickerService.addSticker(chatId, config.adminChatId(), found.get().targetMessage(), emoji);
        return true;
    }

    /**
     * The force_reply prompt replies to the original /s so that "selective" aims
     * it at that one user — otherwise every member of the group would get their
     * input box hijacked.
     */
    private void askForCustomEmoji(long chatId, PendingEmojiPrompt.Pending found) {
        JsonNode sent = client.sendMessage(chatId, CUSTOM_PROMPT, null, found.commandMessageId(),
                Map.of("force_reply", true, "selective", true, "input_field_placeholder", "Смайл"));
        pending.put(chatId, sent.path("message_id").asLong(), found);
    }

    /** Telegram InlineKeyboardMarkup: COLUMNS emoji per row, then a full-width custom button. */
    private static Map<String, Object> markup() {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (int i = 0; i < EMOJIS.size(); i += COLUMNS) {
            List<Map<String, String>> row = new ArrayList<>();
            for (String emoji : EMOJIS.subList(i, Math.min(i + COLUMNS, EMOJIS.size()))) {
                row.add(Map.of("text", emoji, "callback_data", CALLBACK_PREFIX + emoji));
            }
            rows.add(row);
        }
        rows.add(List.of(Map.of("text", CUSTOM_BUTTON, "callback_data", CUSTOM_CALLBACK)));
        return Map.of("inline_keyboard", rows);
    }

    private void deleteQuietly(long chatId, long messageId) {
        try {
            client.deleteMessage(chatId, messageId);
        } catch (TelegramApiException e) {
            log.debug("Could not delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }
}
