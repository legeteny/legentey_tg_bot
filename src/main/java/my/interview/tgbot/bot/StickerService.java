package my.interview.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import my.interview.tgbot.storage.StickerPack;
import my.interview.tgbot.storage.StickerPackStore;
import my.interview.tgbot.telegram.TelegramApiClient;
import my.interview.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Bridges the /q command to @QuotLyBot: a bot can't message another bot in
 * private (Telegram rejects that outright with USER_BOT_TO_BOT_DISABLED), but both
 * bots being members of the same group works fine — a reply addressed to
 * "@QuotLyBot" in a group is delivered to it like any other message, regardless of
 * its privacy mode, and it answers in the same group with a ready-made sticker.
 *
 * <p>/q is issued from an arbitrary chat that does NOT have @QuotLyBot in it, so the
 * target message is first forwarded into a dedicated relay chat (relayGroupChatId)
 * where @QuotLyBot actually lives; all QuotLyBot back-and-forth and confirmations
 * happen there, never in the chat the command was typed in.
 *
 * <p>Each originating chat gets its own sticker pack, created lazily on first use,
 * because Telegram only allows a bot to add stickers to a set that bot itself created
 * (confirmed empirically: adding to a pre-existing, human-created pack fails with
 * STICKERSET_INVALID even when the bot owner and pack owner are the same person).
 */
public class StickerService {

    private static final Logger log = LoggerFactory.getLogger(StickerService.class);

    private static final long QUOTLY_BOT_ID = 1031952739L;
    private static final String QUOTLY_TRIGGER = "/q@QuotLyBot";
    private static final long REQUEST_TIMEOUT_MILLIS = 30_000;

    private record PendingRequest(long originalChatId, long requesterUserId, String emoji, long requestedAtMillis) {
    }

    private final TelegramApiClient client;
    private final StickerPackStore packStore;
    private final String botUsername;
    private final Long relayGroupChatId;

    // Only one /q can be in flight at a time — everything funnels through the single relay chat.
    private volatile PendingRequest pending;

    public StickerService(TelegramApiClient client, StickerPackStore packStore, String botUsername, Long relayGroupChatId) {
        this.client = client;
        this.packStore = packStore;
        this.botUsername = botUsername;
        this.relayGroupChatId = relayGroupChatId;
    }

    /** Kicks off a /q request: forwards the target message into the relay chat and pings @QuotLyBot there. */
    public void requestSticker(long originalChatId, long requesterUserId, long targetMessageId, String emoji) {
        if (relayGroupChatId == null) {
            return;
        }
        JsonNode forwarded = client.forwardMessage(relayGroupChatId, originalChatId, targetMessageId);
        long forwardedMessageId = forwarded.path("message_id").asLong();
        pending = new PendingRequest(originalChatId, requesterUserId, emoji, System.currentTimeMillis());
        client.sendMessage(relayGroupChatId, QUOTLY_TRIGGER, null, forwardedMessageId);
    }

    /**
     * Call for every incoming message. Returns true if it was @QuotLyBot's answer
     * to a pending /q request in the relay chat (and it has been fully handled).
     */
    public boolean tryHandleReply(JsonNode message) {
        if (relayGroupChatId == null || message.path("chat").path("id").asLong() != relayGroupChatId) {
            return false;
        }
        if (message.path("from").path("id").asLong() != QUOTLY_BOT_ID) {
            return false;
        }
        JsonNode sticker = message.path("sticker");
        if (sticker.isMissingNode()) {
            return false;
        }
        PendingRequest current = pending;
        if (current == null) {
            return false;
        }
        pending = null;

        String fileId = sticker.path("file_id").asText();
        String format = sticker.path("is_animated").asBoolean(false) ? "animated"
                : sticker.path("is_video").asBoolean(false) ? "video"
                : "static";

        addToPack(current, fileId, format);
        return true;
    }

    /** Call periodically to give up on a request @QuotLyBot never answered. */
    public void pruneStaleRequests() {
        PendingRequest current = pending;
        if (current == null || relayGroupChatId == null) {
            return;
        }
        if (System.currentTimeMillis() - current.requestedAtMillis() < REQUEST_TIMEOUT_MILLIS) {
            return;
        }
        pending = null;
        client.sendMessage(relayGroupChatId, "QuotLyBot не ответил, попробуйте ещё раз.", null, null);
    }

    private void addToPack(PendingRequest request, String fileId, String format) {
        List<String> emojiList = List.of(request.emoji());
        long chatId = request.originalChatId();
        Optional<StickerPack> existing = packStore.findForChat(chatId);
        try {
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                client.addStickerToSet(pack.ownerUserId(), pack.packName(), fileId, format, emojiList);
                client.sendMessage(relayGroupChatId, "✅ Добавлено: t.me/addstickers/" + pack.packName(), null, null);
            } else {
                String name = "q" + Math.abs(chatId) + "_by_" + botUsername;
                client.createNewStickerSet(request.requesterUserId(), name, "Цитаты", fileId, format, emojiList);
                packStore.save(chatId, name, request.requesterUserId());
                client.sendMessage(relayGroupChatId, "✅ Создан стикерпак: t.me/addstickers/" + name, null, null);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(relayGroupChatId, "⚠️ Не получилось добавить стикер: " + e.getMessage(), null, null);
        }
    }
}
