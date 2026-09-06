package com.legentey.tgbot.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.legentey.tgbot.quoteapi.QuoteApiClient;
import com.legentey.tgbot.storage.QuoteSticker;
import com.legentey.tgbot.storage.QuoteStickerStore;
import com.legentey.tgbot.storage.RenderSettingsStore;
import com.legentey.tgbot.storage.StickerPack;
import com.legentey.tgbot.storage.StickerPackStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final QuoteStickerStore quoteStickerStore;
    private final RenderSettingsStore renderSettingsStore;
    private final String botUsername;
    private final ObjectMapper mapper = new ObjectMapper();

    public StickerService(TelegramApiClient client, QuoteApiClient quoteApiClient, StickerPackStore packStore,
                           QuoteStickerStore quoteStickerStore, RenderSettingsStore renderSettingsStore,
                           String botUsername) {
        this.client = client;
        this.quoteApiClient = quoteApiClient;
        this.packStore = packStore;
        this.quoteStickerStore = quoteStickerStore;
        this.renderSettingsStore = renderSettingsStore;
        this.botUsername = botUsername;
    }

    public int getRenderWidth() {
        return renderSettingsStore.getWidth();
    }

    public void setRenderWidth(int width) {
        renderSettingsStore.setWidth(width);
    }

    /**
     * Finds a sticker in this chat's own pack by its file_unique_id and returns the
     * pack's current file_id for it.
     *
     * <p>Matching goes by file_unique_id because a file_id is not stable across
     * contexts — the one arriving on a sticker message is not necessarily the one
     * the pack knows, and only the latter can be deleted. Empty means the sticker
     * isn't this chat's quote, which is also what keeps /del from touching other packs.
     */
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

    /** Removes one sticker from its pack and forgets the render inputs behind it. */
    public void deleteQuote(String fileId) {
        client.deleteStickerFromSet(fileId);
        quoteStickerStore.deleteByFileId(fileId);
    }

    /** Renders the quoted message and adds it to chatId's sticker pack, tagged with all of emojiList in order. */
    public void createQuote(long chatId, long ownerUserId, JsonNode quotedMessage, List<String> emojiList) {
        String text = quotedText(quotedMessage);
        if (text.isBlank()) {
            client.sendMessage(chatId, "В этом сообщении нет текста — цитировать нечего.", null, null);
            return;
        }
        JsonNode author = resolveAuthor(quotedMessage);
        Profile profile = fetchProfile(author.path("id").asLong());
        JsonNode from = applyProfile(author, profile);
        JsonNode entities = quotedEntities(quotedMessage);

        byte[] png;
        try {
            png = StickerImage.normalizeForSticker(
                    quoteApiClient.generateQuotePng(from, text, entities, renderSettingsStore.getWidth()));
        } catch (RuntimeException e) {
            log.warn("quote-api failed to render a quote for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось сгенерировать стикер.", null, null);
            return;
        }

        String chatTitle = quotedMessage.path("chat").path("title").asText("");
        addToPack(chatId, ownerUserId, png, emojiList, chatTitle, author, profile, text, entities);
    }

    /**
     * Re-renders every remembered quote sticker with the current render width and
     * swaps each one in place in its pack — including pinning it back to the exact
     * position it held before, since replaceStickerInSet's documented
     * position-preserving behavior isn't reliably observed in practice (confirmed:
     * a rerender moved replaced stickers to the front of the pack). Stickers with
     * no remembered render inputs (created before this bot started tracking them)
     * are simply skipped — left exactly as they are, in their existing position.
     * Records whose sticker no longer exists in its pack (deleted by hand, or the
     * pack got recreated) are dropped from the database instead of retried forever.
     */
    public RerenderResult rerenderAll() {
        int width = renderSettingsStore.getWidth();
        int updated = 0;
        int failed = 0;
        int removed = 0;
        for (String packName : quoteStickerStore.findDistinctPackNames()) {
            List<QuoteSticker> stickers = quoteStickerStore.findByPack(packName);
            if (stickers.isEmpty()) {
                continue;
            }
            RerenderResult packResult = rerenderPack(packName, stickers, width);
            updated += packResult.updated();
            failed += packResult.failed();
            removed += packResult.removed();
        }
        return new RerenderResult(updated, failed, removed);
    }

    private RerenderResult rerenderPack(String packName, List<QuoteSticker> stickers, int width) {
        Map<String, Integer> positions = fetchPositions(packName);
        // Grows as each sticker gets its turn, so findNewFileId() stays correct even
        // when several stickers in this same pack are replaced back to back.
        Set<String> knownFileIds = new HashSet<>(positions.keySet());

        int updated = 0;
        int failed = 0;
        int removed = 0;
        for (QuoteSticker sticker : stickers) {
            OneResult result = rerenderOne(sticker, width, knownFileIds);
            if (result.removed()) {
                removed++;
                continue;
            }
            if (result.newFileId() == null) {
                failed++;
                continue;
            }
            Integer position = positions.get(sticker.fileId());
            if (position != null) {
                try {
                    client.setStickerPositionInSet(result.newFileId(), position);
                } catch (TelegramApiException e) {
                    log.warn("Failed to pin position for sticker {} in pack {}: {}",
                            sticker.id(), packName, e.getMessage());
                }
            }
            quoteStickerStore.updateFileId(sticker.id(), result.newFileId());
            updated++;
        }
        return new RerenderResult(updated, failed, removed);
    }

    /** file_id -> current index within the pack, snapshotted once before any replaces this run. */
    private Map<String, Integer> fetchPositions(String packName) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        try {
            JsonNode setStickers = client.getStickerSet(packName).path("stickers");
            for (int i = 0; i < setStickers.size(); i++) {
                String fileId = setStickers.get(i).path("file_id").asText(null);
                if (fileId != null) {
                    positions.put(fileId, i);
                }
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to fetch sticker positions for pack {}: {}", packName, e.getMessage());
        }
        return positions;
    }

    /** Result of re-rendering one sticker: either a new file_id, a plain failure, or removed as stale. */
    private record OneResult(String newFileId, boolean removed) {
        static OneResult updated(String fileId) {
            return new OneResult(fileId, false);
        }

        static final OneResult FAILED = new OneResult(null, false);
        static final OneResult REMOVED = new OneResult(null, true);
    }

    private OneResult rerenderOne(QuoteSticker sticker, int width, Set<String> knownFileIds) {
        JsonNode base;
        JsonNode entities;
        try {
            base = mapper.readTree(sticker.fromJson());
            entities = sticker.entitiesJson() == null ? null : mapper.readTree(sticker.entitiesJson());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored render inputs for quote sticker {}: {}", sticker.id(), e.getMessage());
            return OneResult.FAILED;
        }

        Profile stored = new Profile(sticker.avatarFileId(), sticker.emojiStatus(), sticker.username());
        Profile effective = fillMissing(stored, base.path("id").asLong());
        JsonNode from = applyProfile(base, effective);

        byte[] png;
        try {
            png = StickerImage.normalizeForSticker(
                    quoteApiClient.generateQuotePng(from, sticker.text(), entities, width));
        } catch (RuntimeException e) {
            log.warn("quote-api failed to re-render quote sticker {} in pack {}: {}",
                    sticker.id(), sticker.packName(), e.getMessage());
            return OneResult.FAILED;
        }

        try {
            client.replaceStickerInSet(sticker.ownerUserId(), sticker.packName(), sticker.fileId(),
                    png, parseEmojiList(sticker.emoji()));
        } catch (TelegramApiException e) {
            if (e.getMessage().contains("STICKERSET_INVALID") || e.getMessage().contains("STICKER_ALREADY_DELETED")) {
                // The sticker this record points at no longer exists in the pack — deleted by
                // hand, or the pack itself was recreated after we last saw it. Nothing to retry.
                log.info("Sticker {} no longer exists in pack {}, dropping its stale record",
                        sticker.id(), sticker.packName());
                quoteStickerStore.delete(sticker.id());
                return OneResult.REMOVED;
            }
            log.warn("Failed to re-render quote sticker {} in pack {}: {}",
                    sticker.id(), sticker.packName(), e.getMessage());
            return OneResult.FAILED;
        }

        if (!effective.equals(stored)) {
            quoteStickerStore.updateProfile(sticker.id(),
                    effective.avatarFileId(), effective.emojiStatus(), effective.username());
        }

        try {
            return OneResult.updated(findNewFileId(sticker.packName(), knownFileIds));
        } catch (RuntimeException e) {
            log.warn("Sticker {} was replaced but its new file_id could not be looked up: {}",
                    sticker.id(), e.getMessage());
            return OneResult.FAILED;
        }
    }

    /**
     * replaceStickerInSet doesn't return the replacement's file_id, so we refetch
     * the set and pick out whichever sticker isn't already in knownFileIds — that's
     * the one that was just added — then remember it so it isn't picked again.
     */
    private String findNewFileId(String packName, Set<String> knownFileIds) {
        JsonNode setStickers = client.getStickerSet(packName).path("stickers");
        for (JsonNode s : setStickers) {
            String fileId = s.path("file_id").asText(null);
            if (fileId != null && knownFileIds.add(fileId)) {
                return fileId;
            }
        }
        return null;
    }

    /** Fills any null field of a stored profile with that author's current value. */
    private Profile fillMissing(Profile stored, long userId) {
        if (stored.avatarFileId() != null && stored.emojiStatus() != null && stored.username() != null) {
            return stored;
        }
        Profile live = fetchProfile(userId);
        return new Profile(
                stored.avatarFileId() != null ? stored.avatarFileId() : live.avatarFileId(),
                stored.emojiStatus() != null ? stored.emojiStatus() : live.emojiStatus(),
                stored.username() != null ? stored.username() : live.username());
    }

    public record RerenderResult(int updated, int failed, int removed) {
    }

    /**
     * An author's avatar (as a Telegram file_id — see getChat's "photo.big_file_id"),
     * Premium emoji status and username, as known at some point in time. Any field
     * may be null (not fetched, or the author genuinely has none).
     */
    private record Profile(String avatarFileId, String emojiStatus, String username) {
        static final Profile EMPTY = new Profile(null, null, null);
    }

    /** Live lookup of an author's current avatar/emoji-status/username via getChat. */
    private Profile fetchProfile(long userId) {
        if (userId <= 0) {
            return Profile.EMPTY;
        }
        try {
            JsonNode chat = client.getChat(userId);
            String avatarFileId = blankToNull(chat.path("photo").path("big_file_id").asText(null));
            String emojiStatus = blankToNull(chat.path("emoji_status_custom_emoji_id").asText(null));
            String username = blankToNull(chat.path("username").asText(null));
            return new Profile(avatarFileId, emojiStatus, username);
        } catch (TelegramApiException e) {
            log.debug("Could not fetch profile for user {}: {}", userId, e.getMessage());
            return Profile.EMPTY;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    /** Overlays a profile's avatar/emoji-status/username onto a copy of a base "from" node. */
    private static JsonNode applyProfile(JsonNode baseFrom, Profile profile) {
        if (!baseFrom.isObject()) {
            return baseFrom;
        }
        ObjectNode node = ((ObjectNode) baseFrom).deepCopy();
        setOrRemove(node, "username", profile.username());
        setOrRemove(node, "emoji_status", profile.emojiStatus());
        if (profile.avatarFileId() != null) {
            node.putObject("photo").put("big_file_id", profile.avatarFileId());
        } else {
            node.remove("photo");
        }
        return node;
    }

    private static void setOrRemove(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        } else {
            node.remove(field);
        }
    }

    private void addToPack(long chatId, long ownerUserId, byte[] png, List<String> emojiList, String chatTitle,
                            JsonNode author, Profile profile, String text, JsonNode entities) {
        Optional<StickerPack> existing = packStore.findForChat(chatId);
        String packName;
        try {
            if (existing.isPresent()) {
                StickerPack pack = existing.get();
                try {
                    client.addStickerToSet(pack.ownerUserId(), pack.packName(), png, emojiList);
                    sendAddedConfirmation(chatId, pack.packName());
                    packName = pack.packName();
                } catch (TelegramApiException e) {
                    if (!e.getMessage().contains("STICKERSET_INVALID")) {
                        throw e;
                    }
                    // The pack we remembered no longer exists (e.g. the owner deleted it by hand) — recreate it.
                    log.info("Pack {} for chat {} is gone, recreating", pack.packName(), chatId);
                    packName = createFreshPack(chatId, ownerUserId, pack.packName(), png, emojiList, chatTitle);
                }
            } else {
                String name = "q" + Math.abs(chatId) + "_by_" + botUsername;
                packName = createFreshPack(chatId, ownerUserId, name, png, emojiList, chatTitle);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to update sticker pack for chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, "Не получилось добавить стикер: " + e.getMessage(), null, null);
            return;
        }
        rememberRenderInputs(chatId, packName, ownerUserId, author, profile, text, entities, emojiList);
    }

    /**
     * Saves the render inputs behind the sticker just added, keyed to the file_id
     * Telegram assigned it, so a later rerenderAll() can regenerate and swap it.
     * Best-effort: if we can't fetch the file_id, the sticker still exists, it just
     * won't be picked up by a future rerender.
     */
    private void rememberRenderInputs(long chatId, String packName, long ownerUserId, JsonNode author,
                                       Profile profile, String text, JsonNode entities, List<String> emojiList) {
        try {
            JsonNode setStickers = client.getStickerSet(packName).path("stickers");
            int n = setStickers.size();
            if (n == 0) {
                return;
            }
            String fileId = setStickers.get(n - 1).path("file_id").asText(null);
            if (fileId == null) {
                return;
            }
            String entitiesJson = entities != null && entities.isArray() && !entities.isEmpty()
                    ? entities.toString() : null;
            quoteStickerStore.save(chatId, packName, ownerUserId, fileId, author.toString(), text, entitiesJson,
                    serializeEmojiList(emojiList), profile.avatarFileId(), profile.emojiStatus(),
                    profile.username());
        } catch (TelegramApiException e) {
            log.warn("Could not remember render inputs for new sticker in pack {}: {}", packName, e.getMessage());
        }
    }

    /**
     * The DB column predates multi-emoji support, so it stores a JSON array now but
     * may still hold rows written back when it was just a bare emoji string — this
     * reads either shape back into a list.
     */
    private List<String> parseEmojiList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (node.isArray()) {
                List<String> list = new ArrayList<>();
                node.forEach(e -> list.add(e.asText()));
                return list;
            }
        } catch (JsonProcessingException ignored) {
            // Not JSON — a pre-multi-emoji row storing a bare emoji string. Fall through.
        }
        return List.of(raw);
    }

    private String serializeEmojiList(List<String> emojiList) {
        try {
            return mapper.writeValueAsString(emojiList);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize an emoji list", e);
        }
    }

    private String createFreshPack(long chatId, long ownerUserId, String name, byte[] png,
                                    List<String> emojiList, String chatTitle) {
        String title = truncate(chatTitle.isBlank() ? "Цитаты" : "Цитаты " + chatTitle, 64);
        client.createNewStickerSet(ownerUserId, name, title, png, emojiList);
        packStore.save(chatId, name, ownerUserId);
        sendAddedConfirmation(chatId, name);
        return name;
    }

    private void sendAddedConfirmation(long chatId, String packName) {
        client.sendMessage(chatId, "Цитата добавлена: t.me/addstickers/" + packName, null, null);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
