package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which message a pending emoji-picker prompt (from a bare /q or /s)
 * is about.
 *
 * <p>Needed because Telegram never nests reply_to_message more than one level
 * deep: once the user replies to the bot's "send me an emoji" prompt, the
 * original target message is no longer reachable from the update, and the Bot
 * API offers no way to fetch a message by id. Kept in memory only — a restart
 * just means the user runs the command again. /q and /s each keep their own
 * instance, so their prompts never collide.
 */
class PendingEmojiPrompt {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final int MAX_ENTRIES = 200;

    /** commandMessageId is the /q or /s message itself, used to aim a selective force_reply at its author. */
    record Pending(JsonNode targetMessage, long requesterUserId, long commandMessageId, Instant createdAt) {
    }

    private final Map<String, Pending> byPrompt = new ConcurrentHashMap<>();

    void put(long chatId, long promptMessageId, JsonNode targetMessage, long requesterUserId, long commandMessageId) {
        evict();
        byPrompt.put(key(chatId, promptMessageId),
                new Pending(targetMessage, requesterUserId, commandMessageId, Instant.now()));
    }

    /** Re-registers an existing entry under a new prompt message, keeping its original age. */
    void put(long chatId, long promptMessageId, Pending pending) {
        evict();
        byPrompt.put(key(chatId, promptMessageId), pending);
    }

    Optional<Pending> peek(long chatId, long promptMessageId) {
        Pending pending = byPrompt.get(key(chatId, promptMessageId));
        return pending == null || isStale(pending) ? Optional.empty() : Optional.of(pending);
    }

    void remove(long chatId, long promptMessageId) {
        byPrompt.remove(key(chatId, promptMessageId));
    }

    private void evict() {
        byPrompt.values().removeIf(PendingEmojiPrompt::isStale);
        while (byPrompt.size() >= MAX_ENTRIES) {
            byPrompt.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(oldest -> byPrompt.remove(oldest.getKey()));
        }
    }

    private static boolean isStale(Pending pending) {
        return pending.createdAt().plus(TTL).isBefore(Instant.now());
    }

    private static String key(long chatId, long promptMessageId) {
        return chatId + ":" + promptMessageId;
    }
}
