package com.legentey.tgbot.bot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The /del votes currently open, held in memory only: a vote lives minutes, and
 * a restart is far rarer than that, so losing one costs nothing but a repeated
 * /del.
 */
class PendingDeletions {

    /** Which pack the sticker being voted on lives in — decides how it's actually deleted once approved. */
    enum Kind {
        QUOTE, STICKER
    }

    /** Identifies a vote by the bot message carrying its button. */
    record Key(long chatId, long messageId) {
    }

    /**
     * Both required and expiresAt are snapshotted when the vote opens, so an admin
     * changing either setting mid-vote cannot move the goalposts on a vote already
     * under way.
     */
    record Vote(Kind kind, String stickerFileId, long stickerMessageId, long initiatorUserId, int required,
                Set<Long> approvers, Instant expiresAt) {
    }

    private final Map<Key, Vote> votes = new ConcurrentHashMap<>();

    void put(long chatId, long messageId, Kind kind, String stickerFileId, long stickerMessageId,
             long initiatorUserId, int required, Duration ttl) {
        votes.put(new Key(chatId, messageId), new Vote(kind, stickerFileId, stickerMessageId, initiatorUserId,
                required, ConcurrentHashMap.newKeySet(), Instant.now().plus(ttl)));
    }

    Optional<Vote> peek(long chatId, long messageId) {
        Vote vote = votes.get(new Key(chatId, messageId));
        return vote == null || isExpired(vote) ? Optional.empty() : Optional.of(vote);
    }

    void remove(long chatId, long messageId) {
        votes.remove(new Key(chatId, messageId));
    }

    /** Keys of every vote still open, so their buttons can be kept up to date. */
    List<Key> activeKeys() {
        List<Key> open = new ArrayList<>();
        for (Map.Entry<Key, Vote> entry : votes.entrySet()) {
            if (!isExpired(entry.getValue())) {
                open.add(entry.getKey());
            }
        }
        return open;
    }

    /** Drops every vote past its TTL and hands back their keys, so the caller can clean up the messages. */
    List<Key> removeExpired() {
        List<Key> expired = new ArrayList<>();
        for (Map.Entry<Key, Vote> entry : votes.entrySet()) {
            if (isExpired(entry.getValue()) && votes.remove(entry.getKey()) != null) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    private static boolean isExpired(Vote vote) {
        return vote.expiresAt().isBefore(Instant.now());
    }
}
