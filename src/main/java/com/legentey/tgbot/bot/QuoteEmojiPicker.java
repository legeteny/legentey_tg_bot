package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.Config;
import com.legentey.tgbot.telegram.TelegramApiClient;

import java.util.Optional;

/**
 * The emoji picker behind a bare /q.
 *
 * <p>A command tapped in Telegram's command menu is sent immediately, and no Bot
 * API flag can make a client pre-fill the input box instead. So the emoji a quote
 * sticker needs is chosen after the fact: the bot answers with an inline
 * keyboard, and either a tap or a typed custom emoji finishes the job. The grid
 * itself, its custom-emoji prompt, and their shared text live in
 * {@link EmojiPickerUi}, since {@link StickerEmojiPicker} needs the exact same
 * pieces for /s.
 */
class QuoteEmojiPicker {

    private static final String CALLBACK_PREFIX = "q|";
    private static final String CUSTOM_CALLBACK = "q|*";
    private static final String EXPIRED = "Кнопка устарела — вызовите /q заново.";

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
        JsonNode sent = client.sendMessage(chatId, EmojiPickerUi.PICK_PROMPT, null, commandMessageId,
                EmojiPickerUi.markup(CALLBACK_PREFIX, CUSTOM_CALLBACK));
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
            EmojiPickerUi.deleteQuietly(client, chatId, promptMessageId);
            return;
        }
        if (found.get().requesterUserId() != userId) {
            client.answerCallbackQuery(callbackId, EmojiPickerUi.NOT_YOURS, false);
            return;
        }
        client.answerCallbackQuery(callbackId, null, false);

        pending.remove(chatId, promptMessageId);
        EmojiPickerUi.deleteQuietly(client, chatId, promptMessageId);
        if (CUSTOM_CALLBACK.equals(data)) {
            EmojiPickerUi.askForCustomEmoji(client, pending, chatId, found.get());
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
            client.sendMessage(chatId, EmojiPickerUi.NOT_AN_EMOJI, null, message.path("message_id").asLong());
            return true;
        }
        pending.remove(chatId, promptMessageId);
        EmojiPickerUi.deleteQuietly(client, chatId, promptMessageId);
        stickerService.createQuote(chatId, config.adminChatId(), found.get().targetMessage(), emoji);
        return true;
    }
}
