package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.storage.ApprovalSettingsStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles /s_del: the same approval-vote flow as {@link QuoteDeletionVote}, but
 * for removing a plain sticker added via /s rather than a quote card. Kept as
 * its own class — with its own {@link ApprovalSettingsStore} and
 * {@link PendingDeletions} instance passed in — so the two features' approval
 * counts, timeouts, and open votes never interact.
 */
class PlainStickerDeletionVote {

    private static final Logger log = LoggerFactory.getLogger(PlainStickerDeletionVote.class);

    private static final String CALLBACK_DATA = "sdel|ok";
    private static final long SWEEP_INTERVAL_SECONDS = 30;

    private static final String NOT_OUR_STICKER = "Этот стикер не из стикеров этого чата.";
    private static final String DELETED = "Стикер удалён из пака.";
    private static final String DELETE_FAILED = "Не получилось удалить стикер: ";
    private static final String EXPIRED = "Голосование устарело — вызовите /s_del заново.";
    private static final String OWN_VOTE = "Нельзя подтвердить своё же удаление.";
    private static final String ALREADY_VOTED = "Вы уже подтвердили.";

    private final TelegramApiClient client;
    private final PlainStickerService stickerService;
    private final ApprovalSettingsStore approvalSettings;
    private final PendingDeletions pending;

    PlainStickerDeletionVote(TelegramApiClient client, PlainStickerService stickerService,
                             ApprovalSettingsStore approvalSettings, PendingDeletions pending) {
        this.client = client;
        this.stickerService = stickerService;
        this.approvalSettings = approvalSettings;
        this.pending = pending;
        startSweeper();
    }

    /**
     * Acts on /s_del sent as a reply to a sticker: either deletes it outright
     * (approvals turned off) or posts the vote the deletion now waits on.
     */
    void requestDeletion(long chatId, long requesterUserId, long commandMessageId, JsonNode stickerMessage) {
        long stickerMessageId = stickerMessage.path("message_id").asLong();
        Optional<String> fileId = stickerService.findInChatPack(
                chatId, stickerMessage.path("sticker").path("file_unique_id").asText(null));
        if (fileId.isEmpty()) {
            client.sendMessage(chatId, NOT_OUR_STICKER, null, commandMessageId);
            return;
        }

        int required = approvalSettings.getApproveCount();
        if (required <= 0) {
            deleteAndReport(chatId, fileId.get(), stickerMessageId);
            return;
        }

        Duration ttl = Duration.ofSeconds(approvalSettings.getApproveTimeoutSeconds());
        JsonNode sent = client.sendMessage(chatId, votePrompt(required), null, stickerMessageId,
                markup(0, required, ttl));
        pending.put(chatId, sent.path("message_id").asLong(), fileId.get(), stickerMessageId, requesterUserId,
                required, ttl);
    }

    /** Counts one approval. Returns false if the callback belongs to some other keyboard. */
    boolean handleCallback(JsonNode callbackQuery) {
        if (!CALLBACK_DATA.equals(callbackQuery.path("data").asText(""))) {
            return false;
        }
        String callbackId = callbackQuery.path("id").asText();
        JsonNode voteMessage = callbackQuery.path("message");
        long chatId = voteMessage.path("chat").path("id").asLong();
        long messageId = voteMessage.path("message_id").asLong();
        long userId = callbackQuery.path("from").path("id").asLong();

        Optional<PendingDeletions.Vote> found = pending.peek(chatId, messageId);
        if (found.isEmpty()) {
            client.answerCallbackQuery(callbackId, EXPIRED, false);
            deleteQuietly(chatId, messageId);
            return true;
        }
        PendingDeletions.Vote vote = found.get();
        if (vote.initiatorUserId() == userId) {
            client.answerCallbackQuery(callbackId, OWN_VOTE, false);
            return true;
        }
        if (!vote.approvers().add(userId)) {
            client.answerCallbackQuery(callbackId, ALREADY_VOTED, false);
            return true;
        }
        client.answerCallbackQuery(callbackId, null, false);

        int approved = vote.approvers().size();
        if (approved < vote.required()) {
            editMarkupQuietly(chatId, messageId, markup(approved, vote.required(),
                    Duration.between(Instant.now(), vote.expiresAt())));
            return true;
        }
        pending.remove(chatId, messageId);
        deleteQuietly(chatId, messageId);
        deleteAndReport(chatId, vote.stickerFileId(), vote.stickerMessageId());
        return true;
    }

    /**
     * The vote message is already gone by this point, so a failure here has to be
     * reported explicitly — otherwise the chat sees nothing happen at all.
     */
    private void deleteAndReport(long chatId, String fileId, Long replyToMessageId) {
        try {
            stickerService.deleteSticker(fileId);
        } catch (TelegramApiException e) {
            log.warn("Failed to delete a sticker in chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, DELETE_FAILED + e.getMessage(), null, replyToMessageId);
            return;
        }
        client.sendMessage(chatId, DELETED, null, replyToMessageId);
    }

    /** Takes down the buttons of votes nobody finished, so no stale one is left to tap. */
    private void startSweeper() {
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sdel-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep,
                SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void sweep() {
        try {
            for (PendingDeletions.Key key : pending.removeExpired()) {
                deleteQuietly(key.chatId(), key.messageId());
            }
            refreshCountdowns();
        } catch (RuntimeException e) {
            // A throw here would kill the scheduled task for good.
            log.warn("Failed to sweep expired deletion votes", e);
        }
    }

    /**
     * Keeps the time left on each open vote's button honest between taps. The step
     * is the sweep interval, so the countdown moves in half-minutes — plenty for the
     * minutes-long votes this is meant for.
     */
    private void refreshCountdowns() {
        Instant now = Instant.now();
        for (PendingDeletions.Key key : pending.activeKeys()) {
            pending.peek(key.chatId(), key.messageId()).ifPresent(vote -> editMarkupQuietly(
                    key.chatId(), key.messageId(),
                    markup(vote.approvers().size(), vote.required(), Duration.between(now, vote.expiresAt()))));
        }
    }

    /** "Нужно голосов: N" rather than "N голосов" — no number/noun agreement to get wrong. */
    private static String votePrompt(int required) {
        return "Нажав кнопку, вы голосуете за удаление этого стикера из пака. Нужно голосов: " + required;
    }

    private static Map<String, Object> markup(int approved, int required, Duration remaining) {
        return Map.of("inline_keyboard", List.of(List.of(Map.of(
                "text", "Удалить (" + approved + "/" + required + ") · до конца " + formatRemaining(remaining),
                "callback_data", CALLBACK_DATA))));
    }

    /**
     * Time left in the vote, always HH:MM:SS. The timeout is an admin setting with
     * no upper bound, so the hours field is always there rather than appearing only
     * once it is needed — and it grows past two digits instead of wrapping.
     */
    private static String formatRemaining(Duration remaining) {
        long seconds = Math.max(0, remaining.getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    private void editMarkupQuietly(long chatId, long messageId, Map<String, Object> markup) {
        try {
            client.editMessageReplyMarkup(chatId, messageId, markup);
        } catch (TelegramApiException e) {
            log.debug("Could not update vote buttons on message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    private void deleteQuietly(long chatId, long messageId) {
        try {
            client.deleteMessage(chatId, messageId);
        } catch (TelegramApiException e) {
            log.debug("Could not delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }
}
