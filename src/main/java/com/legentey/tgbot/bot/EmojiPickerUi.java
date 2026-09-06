package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The 24-emoji grid, its custom-emoji prompt, and the text around them — shared
 * byte-for-byte between {@link QuoteEmojiPicker} (/q) and {@link
 * StickerEmojiPicker} (/s), which otherwise differ in what a chosen emoji goes
 * on to do and in how each sits in {@link BotService}'s callback chain.
 */
final class EmojiPickerUi {

    private static final Logger log = LoggerFactory.getLogger(EmojiPickerUi.class);

    private static final int COLUMNS = 6;

    static final List<String> EMOJIS = List.of(
            "😂", "🔥", "💀", "🤡", "😭", "👍",
            "🤔", "❤️", "🥴", "😎", "🙏", "💩",
            "😱", "🤮", "🥺", "😐", "🤯", "👀",
            "🤝", "🫡", "😈", "🍿", "💯", "🤣"
    );

    static final String PICK_PROMPT = "Выберите эмодзи для стикера:";
    static final String CUSTOM_BUTTON = "Свой эмодзи";
    static final String CUSTOM_PROMPT = "Отправьте свой эмодзи ответом на это сообщение.";
    static final String NOT_YOURS = "Это не ваша кнопка.";
    static final String NOT_AN_EMOJI = "Это не похоже на эмодзи — пришлите один эмодзи.";

    private EmojiPickerUi() {
    }

    /** Telegram InlineKeyboardMarkup: COLUMNS emoji per row, then a full-width custom button. */
    static Map<String, Object> markup(String callbackPrefix, String customCallback) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (int i = 0; i < EMOJIS.size(); i += COLUMNS) {
            List<Map<String, String>> row = new ArrayList<>();
            for (String emoji : EMOJIS.subList(i, Math.min(i + COLUMNS, EMOJIS.size()))) {
                row.add(Map.of("text", emoji, "callback_data", callbackPrefix + emoji));
            }
            rows.add(row);
        }
        rows.add(List.of(Map.of("text", CUSTOM_BUTTON, "callback_data", customCallback)));
        return Map.of("inline_keyboard", rows);
    }

    /**
     * The force_reply prompt replies to the original /q or /s so that "selective"
     * aims it at that one user — otherwise every member of the group would get
     * their input box hijacked.
     */
    static void askForCustomEmoji(TelegramApiClient client, PendingEmojiPrompt pending, long chatId,
                                  PendingEmojiPrompt.Pending found) {
        JsonNode sent = client.sendMessage(chatId, CUSTOM_PROMPT, null, found.commandMessageId(),
                Map.of("force_reply", true, "selective", true, "input_field_placeholder", "Эмодзи"));
        pending.put(chatId, sent.path("message_id").asLong(), found);
    }

    static void deleteQuietly(TelegramApiClient client, long chatId, long messageId) {
        try {
            client.deleteMessage(chatId, messageId);
        } catch (TelegramApiException e) {
            log.debug("Could not delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }
}
