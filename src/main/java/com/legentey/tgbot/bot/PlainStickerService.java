package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.storage.RegularStickerPackStore;
import com.legentey.tgbot.storage.StickerPack;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Handles /s: adds a replied-to sticker to that chat's own sticker pack.
 *
 * <p>Unlike {@link StickerService}'s /q, there is nothing to render — the
 * replied-to message already carries a valid Telegram sticker file, so its
 * file_id is simply passed straight through to Telegram's sticker-set methods,
 * which accept a file_id already on Telegram's servers in place of a fresh
 * upload. That sidesteps re-encoding entirely, so whichever format the sticker
 * is already in — static webp/png, animated tgs, or video webm — works without
 * extra handling.
 *
 * <p>Telegram locks a sticker set to a single format, so a chat that has
 * collected /s stickers of more than one format ends up with one pack per
 * format, each still titled after the chat.
 */
public class PlainStickerService {

    private static final Logger log = LoggerFactory.getLogger(PlainStickerService.class);

    private final TelegramApiClient client;
    private final RegularStickerPackStore packStore;
    private final String botUsername;

    public PlainStickerService(TelegramApiClient client, RegularStickerPackStore packStore, String botUsername) {
        this.client = client;
        this.packStore = packStore;
        this.botUsername = botUsername;
    }

    /**
     * Finds a sticker across this chat's own packs (one per format) by its
     * file_unique_id and returns the pack's current file_id for it.
     */
    public Optional<String> findInChatPack(long chatId, String fileUniqueId) {
        if (fileUniqueId == null || fileUniqueId.isBlank()) {
            return Optional.empty();
        }
        for (StickerPack pack : packStore.findAllForChat(chatId)) {
            JsonNode setStickers;
            try {
                setStickers = client.getStickerSet(pack.packName()).path("stickers");
            } catch (TelegramApiException e) {
                if (!e.getMessage().contains("STICKERSET_INVALID")) {
                    throw e;
                }
                log.info("Pack {} for chat {} is gone", pack.packName(), chatId);
                continue;
            }
            for (JsonNode sticker : setStickers) {
                if (fileUniqueId.equals(sticker.path("file_unique_id").asText(null))) {
                    return Optional.ofNullable(sticker.path("file_id").asText(null));
                }
            }
        }
        return Optional.empty();
    }

    /** Removes one sticker from its pack. */
    public void deleteSticker(String fileId) {
        client.deleteStickerFromSet(fileId);
    }

    /** Adds the sticker carried by stickerMessage to chatId's own pack for its format. */
    public void addSticker(long chatId, long ownerUserId, JsonNode stickerMessage, String emoji) {
        JsonNode sticker = stickerMessage.path("sticker");
        String fileId = sticker.path("file_id").asText(null);
        if (fileId == null) {
            client.sendMessage(chatId, "Не получилось прочитать стикер.", null, null);
            return;
        }
        String format = formatOf(sticker);
        String chatTitle = stickerMessage.path("chat").path("title").asText("");
        List<String> emojiList = List.of(emoji);

        try {
            Optional<StickerPack> existing = packStore.findForChat(chatId, format);
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                try {
                    client.addStickerToSetFromFileId(pack.ownerUserId(), pack.packName(), fileId, format, emojiList);
                    sendAddedConfirmation(chatId, pack.packName());
                } catch (TelegramApiException e) {
                    if (!e.getMessage().contains("STICKERSET_INVALID")) {
                        throw e;
                    }
                    // The pack we remembered no longer exists (e.g. the owner deleted it by hand) — recreate it.
                    log.info("Pack {} for chat {} is gone, recreating", pack.packName(), chatId);
                    createFreshPack(chatId, ownerUserId, pack.packName(), fileId, format, emojiList, chatTitle);
                }
            } else {
                String name = "s" + Math.abs(chatId) + "_" + formatSuffix(format) + "_by_" + botUsername;
                createFreshPack(chatId, ownerUserId, name, fileId, format, emojiList, chatTitle);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось добавить стикер: " + e.getMessage(), null, null);
        }
    }

    private void createFreshPack(long chatId, long ownerUserId, String name, String fileId, String format,
                                  List<String> emojiList, String chatTitle) {
        String title = truncate(chatTitle.isBlank() ? "Стикеры" : "Стикеры " + chatTitle, 64);
        client.createNewStickerSetFromFileId(ownerUserId, name, title, fileId, format, emojiList);
        packStore.save(chatId, format, name, ownerUserId);
        sendAddedConfirmation(chatId, name);
    }

    private void sendAddedConfirmation(long chatId, String packName) {
        client.sendMessage(chatId, "Стикер добавлен: t.me/addstickers/" + packName, null, null);
    }

    private static String formatOf(JsonNode sticker) {
        if (sticker.path("is_video").asBoolean(false)) {
            return "video";
        }
        if (sticker.path("is_animated").asBoolean(false)) {
            return "animated";
        }
        return "static";
    }

    /** Kept short so the generated pack name (capped at 64 chars by Telegram) has room for the chat id. */
    private static String formatSuffix(String format) {
        return switch (format) {
            case "video" -> "vid";
            case "animated" -> "anim";
            default -> "static";
        };
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
