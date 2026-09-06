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
 * The emoji picker behind a bare /q.
 *
 * <p>A command tapped in Telegram's command menu is sent immediately, and no Bot
 * API flag can make a client pre-fill the input box instead. So the emoji a quote
 * sticker needs is chosen after the fact: the bot answers with an inline
 * keyboard, and either a tap or a typed custom emoji finishes the job.
 */
class QuoteEmojiPicker {

    private static final Logger log = LoggerFactory.getLogger(QuoteEmojiPicker.class);

    private static final String CALLBACK_PREFIX = "q|";
    private static final String CUSTOM_CALLBACK = "q|*";
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
    private static final String EXPIRED = "Кнопка устарела — вызовите /q заново.";
    private static final String NOT_YOURS = "Это не ваша кнопка.";
    private static final String NOT_AN_EMOJI = "Это не похоже на смайл — пришлите один смайл.";

    private final TelegramApiClient client;
    private final StickerService stickerService;
    private final PendingEmojiPrompt pending;
    private final Config config;

    QuoteEmojiPicker(TelegramApiClient client, StickerService stickerService, PendingEmojiPrompt pending, Config config) {
        this.client = client;
        this.stickerService = stickerService;
        this.pending = pending;
        this.config = config;
    }

    /** Answers a bare /q with the emoji keyboard, remembering what is being quoted. */
    void prompt(long chatId, long requesterUserId, long commandMessageId, JsonNode quotedMessage) {
        JsonNode sent = client.sendMessage(chatId, PICK_PROMPT, null, commandMessageId, markup());
        pending.put(chatId, sent.path("message_id").asLong(), quotedMessage, requesterUserId, commandMessageId);
    }

    /** Handles a tap on one of the keyboard's buttons. */
    void handleCallback(JsonNode callbackQuery) {
        String callbackId = callbackQuery.path("id").asText();
        String data = callbackQuery.path("data").asText("");
        if (!data.startsWith(CALLBACK_PREFIX) || config.adminChatId() == null) {
            client.answerCallbackQuery(callbackId, null, false);
            return;
        }

        JsonNode promptMessage = callbackQuery.path("message");
        long chatId = promptMessage.path("chat").path("id").asLong();
        long promptMessageId = promptMessage.path("message_id").asLong();
        long userId = callbackQuery.path("from").path("id").asLong();

        Optional<PendingEmojiPrompt.Pending> found = pending.peek(chatId, promptMessageId);
        if (found.isEmpty()) {
            client.answerCallbackQuery(callbackId, EXPIRED, false);
            deleteQuietly(chatId, promptMessageId);
            return;
        }
        if (found.get().requesterUserId() != userId) {
            client.answerCallbackQuery(callbackId, NOT_YOURS, false);
            return;
        }
        client.answerCallbackQuery(callbackId, null, false);

        pending.remove(chatId, promptMessageId);
        deleteQuietly(chatId, promptMessageId);
        if (CUSTOM_CALLBACK.equals(data)) {
            askForCustomEmoji(chatId, found.get());
            return;
        }
        // Sticker packs are always owned by the admin account, regardless of who tapped.
        stickerService.createQuote(chatId, config.adminChatId(), found.get().targetMessage(),
                data.substring(CALLBACK_PREFIX.length()));
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
        stickerService.createQuote(chatId, config.adminChatId(), found.get().targetMessage(), emoji);
        return true;
    }

    /**
     * The force_reply prompt replies to the original /q so that "selective" aims
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
