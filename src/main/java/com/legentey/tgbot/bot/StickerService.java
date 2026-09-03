package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.legentey.tgbot.quoteapi.QuoteApiClient;
import com.legentey.tgbot.storage.StickerPack;
import com.legentey.tgbot.storage.StickerPackStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Handles /q: renders a quote-card sticker for a replied-to message via a
 * self-hosted quote-api (https://github.com/LyoSU/quote-api — the actual
 * rendering engine behind @QuotLyBot, without its Telegram bot frontend) and
 * adds it to that chat's sticker pack.
 *
 * <p>Rendering locally from the real message data (not by relaying it through
 * another bot) means the quote always credits the real author — no forwarding
 * trick needed, and none of its attribution quirks.
 *
 * <p>Each chat gets its own sticker pack, created lazily on first use, because
 * Telegram only allows a bot to add stickers to a set that bot itself created
 * (confirmed empirically: adding to a pre-existing, human-created pack fails with
 * STICKERSET_INVALID even when the bot owner and pack owner are the same person).
 */
public class StickerService {

    private static final Logger log = LoggerFactory.getLogger(StickerService.class);

    private final TelegramApiClient client;
    private final QuoteApiClient quoteApiClient;
    private final StickerPackStore packStore;
    private final String botUsername;

    public StickerService(TelegramApiClient client, QuoteApiClient quoteApiClient,
                           StickerPackStore packStore, String botUsername) {
        this.client = client;
        this.quoteApiClient = quoteApiClient;
        this.packStore = packStore;
        this.botUsername = botUsername;
    }

    /** Renders the quoted message and adds it to chatId's sticker pack. */
    public void createQuote(long chatId, long ownerUserId, JsonNode quotedMessage, String emoji) {
        String text = quotedText(quotedMessage);
        if (text.isBlank()) {
            client.sendMessage(chatId, "В этом сообщении нет текста — цитировать нечего.", null, null);
            return;
        }
        JsonNode from = buildFromNode(resolveAuthor(quotedMessage));
        JsonNode entities = quotedEntities(quotedMessage);

        byte[] png;
        try {
            png = StickerImage.normalizeForSticker(quoteApiClient.generateQuotePng(from, text, entities));
        } catch (RuntimeException e) {
            log.warn("quote-api failed to render a quote for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось сгенерировать стикер.", null, null);
            return;
        }

        String chatTitle = quotedMessage.path("chat").path("title").asText("");
        addToPack(chatId, ownerUserId, png, emoji, chatTitle);
    }

    /**
     * Telegram carries a media message's text in "caption", not "text", so a
     * photo with a caption looks textless unless both fields are checked.
     */
    private static String quotedText(JsonNode quotedMessage) {
        String text = quotedMessage.path("text").asText("");
        return text.isEmpty() ? quotedMessage.path("caption").asText("") : text;
    }

    /** Entities must come from the same field the text did. */
    private static JsonNode quotedEntities(JsonNode quotedMessage) {
        return quotedMessage.path("text").asText("").isEmpty()
                ? quotedMessage.path("caption_entities")
                : quotedMessage.path("entities");
    }

    /**
     * If the quoted message is itself a forward, credit the original author
     * instead of whoever forwarded it into this chat.
     */
    private JsonNode resolveAuthor(JsonNode quotedMessage) {
        JsonNode origin = quotedMessage.path("forward_origin");
        if (origin.isMissingNode()) {
            return quotedMessage.path("from");
        }
        return switch (origin.path("type").asText("")) {
            case "user" -> origin.path("sender_user");
            case "hidden_user" -> chatlikeFrom(0, origin.path("sender_user_name").asText(""));
            case "chat" -> chatlikeFrom(origin.path("sender_chat"));
            case "channel" -> chatlikeFrom(origin.path("chat"));
            default -> quotedMessage.path("from");
        };
    }

    private JsonNode chatlikeFrom(JsonNode chat) {
        return chatlikeFrom(chat.path("id").asLong(), chat.path("title").asText(""));
    }

    private JsonNode chatlikeFrom(long id, String name) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("name", name);
        return node;
    }

    /**
     * Best-effort: attaches the user's Premium emoji status, if any. quote-api
     * fetches the avatar itself (via its own getChat call) once "avatar": true
     * is set, so we don't need to pre-fetch a photo — but emoji_status isn't
     * part of a plain message's "from" and has to come from getChat here.
     */
    private JsonNode buildFromNode(JsonNode telegramFrom) {
        if (!telegramFrom.isObject()) {
            return telegramFrom;
        }
        ObjectNode node = ((ObjectNode) telegramFrom).deepCopy();
        long userId = telegramFrom.path("id").asLong();
        if (userId <= 0) {
            return node;
        }
        try {
            String emojiStatusId = client.getChat(userId).path("emoji_status_custom_emoji_id").asText(null);
            if (emojiStatusId != null && !emojiStatusId.isBlank()) {
                node.put("emoji_status", emojiStatusId);
            }
        } catch (TelegramApiException e) {
            log.debug("Could not fetch chat info for user {}: {}", userId, e.getMessage());
        }
        return node;
    }

    private void addToPack(long chatId, long ownerUserId, byte[] png, String emoji, String chatTitle) {
        List<String> emojiList = List.of(emoji);
        Optional<StickerPack> existing = packStore.findForChat(chatId);
        try {
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                try {
                    client.addStickerToSet(pack.ownerUserId(), pack.packName(), png, emojiList);
                    sendAddedConfirmation(chatId, pack.packName());
                } catch (TelegramApiException e) {
                    if (!e.getMessage().contains("STICKERSET_INVALID")) {
                        throw e;
                    }
                    // The pack we remembered no longer exists (e.g. the owner deleted it by hand) — recreate it.
                    log.info("Pack {} for chat {} is gone, recreating", pack.packName(), chatId);
                    createFreshPack(chatId, ownerUserId, pack.packName(), png, emojiList, chatTitle);
                }
            } else {
                String name = "q" + Math.abs(chatId) + "_by_" + botUsername;
                createFreshPack(chatId, ownerUserId, name, png, emojiList, chatTitle);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось добавить стикер: " + e.getMessage(), null, null);
        }
    }

    private void createFreshPack(long chatId, long ownerUserId, String name, byte[] png,
                                  List<String> emojiList, String chatTitle) {
        String title = truncate(chatTitle.isBlank() ? "Цитаты" : "Цитаты " + chatTitle, 64);
        client.createNewStickerSet(ownerUserId, name, title, png, emojiList);
        packStore.save(chatId, name, ownerUserId);
        sendAddedConfirmation(chatId, name);
    }

    private void sendAddedConfirmation(long chatId, String packName) {
        client.sendMessage(chatId, "Цитата добавлена: t.me/addstickers/" + packName, null, null);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
