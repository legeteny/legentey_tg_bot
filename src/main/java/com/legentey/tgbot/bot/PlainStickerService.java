package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.storage.StickerPack;
import com.legentey.tgbot.storage.StickerPackStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Handles /s: adds a replied-to sticker — or a plain PNG/WEBP picture that isn't
 * a sticker yet — to that chat's own sticker pack. Only the static format is
 * supported (see the class-level note on why), so unlike {@link StickerService}'s
 * /q, exactly one pack per chat.
 *
 * <p>Telegram locks a sticker set to a single format (static/animated/video) for
 * its whole life, and a chat's pack is meant to stay singular, so animated and
 * video stickers are rejected outright rather than spawning a second pack.
 *
 * <p>Two different sources feed /s:
 * <ul>
 *     <li>a reply to an existing (static) sticker — its file_id already lives on
 *     Telegram's servers, so it's passed straight through to the sticker-set
 *     methods with no download at all;</li>
 *     <li>a reply to a plain picture — a compressed "photo" (always JPEG) or a
 *     "document" with an image/png or image/webp MIME type — which has to be
 *     downloaded and turned into an actual sticker upload. All three decode and
 *     resize the same way, through {@link StickerImage#normalizeForSticker}
 *     ({@code ImageIO}, with the {@code imageio-webp} dependency registering a
 *     WEBP reader the JDK doesn't ship on its own), and are re-encoded as PNG.</li>
 * </ul>
 */
public class PlainStickerService {

    private static final Logger log = LoggerFactory.getLogger(PlainStickerService.class);

    private static final String STATIC_FORMAT = "static";

    private final TelegramApiClient client;
    private final StickerPackStore packStore;
    private final String botUsername;

    public PlainStickerService(TelegramApiClient client, StickerPackStore packStore, String botUsername) {
        this.client = client;
        this.packStore = packStore;
        this.botUsername = botUsername;
    }

    /** Finds a sticker in this chat's own pack by its file_unique_id and returns its current file_id. */
    public Optional<String> findInChatPack(long chatId, String fileUniqueId) {
        if (fileUniqueId == null || fileUniqueId.isBlank()) {
            return Optional.empty();
        }
        Optional<StickerPack> pack = packStore.findForChat(chatId);
        if (pack.isEmpty()) {
            return Optional.empty();
        }
        JsonNode setStickers;
        try {
            setStickers = client.getStickerSet(pack.get().packName()).path("stickers");
        } catch (TelegramApiException e) {
            if (!e.getMessage().contains("STICKERSET_INVALID")) {
                throw e;
            }
            log.info("Pack {} for chat {} is gone", pack.get().packName(), chatId);
            return Optional.empty();
        }
        for (JsonNode sticker : setStickers) {
            if (fileUniqueId.equals(sticker.path("file_unique_id").asText(null))) {
                return Optional.ofNullable(sticker.path("file_id").asText(null));
            }
        }
        return Optional.empty();
    }

    /** Removes one sticker from the pack. */
    public void deleteSticker(String fileId) {
        client.deleteStickerFromSet(fileId);
    }

    /** Adds whatever repliedMessage carries (sticker or picture) to chatId's own pack. */
    public void addSticker(long chatId, long ownerUserId, JsonNode repliedMessage, String emoji) {
        Upload upload;
        try {
            upload = resolveUpload(repliedMessage);
        } catch (UnsupportedSourceException e) {
            client.sendMessage(chatId, e.getMessage(), null, null);
            return;
        } catch (RuntimeException e) {
            log.warn("Failed to read a sticker source for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось прочитать файл: " + e.getMessage(), null, null);
            return;
        }

        String chatTitle = repliedMessage.path("chat").path("title").asText("");
        List<String> emojiList = List.of(emoji);

        try {
            Optional<StickerPack> existing = packStore.findForChat(chatId);
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                try {
                    upload.addToSet(client, pack.ownerUserId(), pack.packName(), emojiList);
                    sendAddedConfirmation(chatId, pack.packName());
                } catch (TelegramApiException e) {
                    if (!e.getMessage().contains("STICKERSET_INVALID")) {
                        throw e;
                    }
                    // The pack we remembered no longer exists (e.g. the owner deleted it by hand) — recreate it.
                    log.info("Pack {} for chat {} is gone, recreating", pack.packName(), chatId);
                    createFreshPack(chatId, ownerUserId, pack.packName(), upload, emojiList, chatTitle);
                }
            } else {
                String name = "s" + Math.abs(chatId) + "_by_" + botUsername;
                createFreshPack(chatId, ownerUserId, name, upload, emojiList, chatTitle);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось добавить стикер: " + e.getMessage(), null, null);
        }
    }

    private void createFreshPack(long chatId, long ownerUserId, String name, Upload upload,
                                  List<String> emojiList, String chatTitle) {
        String title = truncate(chatTitle.isBlank() ? "Стикеры" : "Стикеры " + chatTitle, 64);
        upload.createSet(client, ownerUserId, name, title, emojiList);
        packStore.save(chatId, name, ownerUserId);
        sendAddedConfirmation(chatId, name);
    }

    private void sendAddedConfirmation(long chatId, String packName) {
        client.sendMessage(chatId, "Стикер добавлен: t.me/addstickers/" + packName, null, null);
    }

    /**
     * Figures out what /s was pointed at and gets it into an uploadable shape.
     * Animated/video stickers and unsupported file types are rejected here, before
     * any pack is touched.
     */
    private Upload resolveUpload(JsonNode message) {
        JsonNode sticker = message.path("sticker");
        if (!sticker.isMissingNode() && !sticker.isNull()) {
            if (sticker.path("is_video").asBoolean(false) || sticker.path("is_animated").asBoolean(false)) {
                throw new UnsupportedSourceException(
                        "Поддерживаются только статические стикеры (PNG/WEBP) — анимированные и видео нельзя.");
            }
            String fileId = sticker.path("file_id").asText(null);
            if (fileId == null) {
                throw new IllegalStateException("Telegram did not include a file_id for this sticker");
            }
            return Upload.ofFileId(fileId);
        }

        JsonNode photo = message.path("photo");
        if (photo.isArray() && !photo.isEmpty()) {
            // Telegram sends several sizes of the same photo; the last is the largest.
            String fileId = photo.get(photo.size() - 1).path("file_id").asText(null);
            return Upload.ofBytes(downloadAndNormalize(fileId), "sticker.png", "image/png");
        }

        JsonNode document = message.path("document");
        if (!document.isMissingNode() && !document.isNull()) {
            String mimeType = document.path("mime_type").asText("");
            String fileId = document.path("file_id").asText(null);
            return switch (mimeType) {
                case "image/png", "image/webp" ->
                        Upload.ofBytes(downloadAndNormalize(fileId), "sticker.png", "image/png");
                default -> throw new UnsupportedSourceException(
                        "Неподдерживаемый формат файла — поддерживаются только PNG и WEBP.");
            };
        }

        throw new UnsupportedSourceException("Ответьте на стикер или картинку (PNG/WEBP).");
    }

    /** JPEG (Telegram photos), PNG and WEBP all decode fine via ImageIO, so all three get resized to fit. */
    private byte[] downloadAndNormalize(String fileId) {
        return StickerImage.normalizeForSticker(downloadRaw(fileId));
    }

    private byte[] downloadRaw(String fileId) {
        String filePath = client.getFile(fileId).path("file_path").asText(null);
        if (filePath == null) {
            throw new IllegalStateException("Telegram did not return a file_path for " + fileId);
        }
        return client.downloadFile(filePath);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** A rejection with a message meant to go straight back to the chat, not into the logs as a failure. */
    private static final class UnsupportedSourceException extends RuntimeException {
        UnsupportedSourceException(String message) {
            super(message);
        }
    }

    /** Either an existing file_id or freshly downloaded bytes, ready to hand to Telegram. */
    private record Upload(String fileId, byte[] bytes, String fileName, String contentType) {
        static Upload ofFileId(String fileId) {
            return new Upload(fileId, null, null, null);
        }

        static Upload ofBytes(byte[] bytes, String fileName, String contentType) {
            return new Upload(null, bytes, fileName, contentType);
        }

        void addToSet(TelegramApiClient client, long ownerUserId, String packName, List<String> emojiList) {
            if (fileId != null) {
                client.addStickerToSetFromFileId(ownerUserId, packName, fileId, STATIC_FORMAT, emojiList);
            } else {
                client.addStickerToSet(ownerUserId, packName, bytes, fileName, contentType, emojiList);
            }
        }

        void createSet(TelegramApiClient client, long ownerUserId, String packName, String title,
                       List<String> emojiList) {
            if (fileId != null) {
                client.createNewStickerSetFromFileId(ownerUserId, packName, title, fileId, STATIC_FORMAT, emojiList);
            } else {
                client.createNewStickerSet(ownerUserId, packName, title, bytes, fileName, contentType, emojiList);
            }
        }
    }
}
