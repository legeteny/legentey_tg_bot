package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
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
    public void createQuote(long chatId, long requesterUserId, JsonNode quotedMessage, String emoji) {
        JsonNode from = buildFromNode(quotedMessage.path("from"));
        String text = quotedMessage.path("text").asText("");
        JsonNode entities = quotedMessage.path("entities");

        byte[] png;
        try {
            png = quoteApiClient.generateQuotePng(from, text, entities);
        } catch (RuntimeException e) {
            log.warn("quote-api failed to render a quote for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "⚠️ Не получилось сгенерировать стикер.", null, null);
            return;
        }

        addToPack(chatId, requesterUserId, png, emoji);
    }

    /** Best-effort: attaches the user's current profile photo as a big_file_id, if any. */
    private JsonNode buildFromNode(JsonNode telegramFrom) {
        if (!telegramFrom.isObject()) {
            return telegramFrom;
        }
        ObjectNode node = ((ObjectNode) telegramFrom).deepCopy();
        long userId = telegramFrom.path("id").asLong();
        try {
            JsonNode photos = client.getUserProfilePhotos(userId).path("photos");
            if (photos.isArray() && !photos.isEmpty() && photos.get(0).isArray() && !photos.get(0).isEmpty()) {
                JsonNode sizes = photos.get(0);
                String bigFileId = sizes.get(sizes.size() - 1).path("file_id").asText();
                node.putObject("photo").put("big_file_id", bigFileId);
            }
        } catch (TelegramApiException e) {
            log.debug("Could not fetch profile photo for user {}: {}", userId, e.getMessage());
        }
        return node;
    }

    private void addToPack(long chatId, long requesterUserId, byte[] png, String emoji) {
        List<String> emojiList = List.of(emoji);
        Optional<StickerPack> existing = packStore.findForChat(chatId);
        try {
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                client.addStickerToSet(pack.ownerUserId(), pack.packName(), png, emojiList);
                client.sendMessage(chatId, "✅ Добавлено: t.me/addstickers/" + pack.packName(), null, null);
            } else {
                String name = "q" + Math.abs(chatId) + "_by_" + botUsername;
                client.createNewStickerSet(requesterUserId, name, "Цитаты", png, emojiList);
                packStore.save(chatId, name, requesterUserId);
                client.sendMessage(chatId, "✅ Создан стикерпак: t.me/addstickers/" + name, null, null);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "⚠️ Не получилось добавить стикер: " + e.getMessage(), null, null);
        }
    }
}
