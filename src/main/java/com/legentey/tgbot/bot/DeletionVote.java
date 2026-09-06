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
 * Handles /del: deleting a sticker — whether it's a /q quote card or a plain /s
 * sticker — takes the agreement of other people in the chat, so the command
 * opens a vote rather than removing anything on the spot.
 *
 * <p>Which of the two a replied-to sticker is gets sorted out here, by checking
 * the /q pack first and then the /s pack. From that point on the two kinds keep
 * their own approval-count/timeout settings — still changed separately via
 * /q_set_approve_count and /s_set_approve_count — but share this one vote UI and
 * callback.
 */
class DeletionVote {

    private static final Logger log = LoggerFactory.getLogger(DeletionVote.class);

    private static final String CALLBACK_DATA = "del|ok";
    private static final long SWEEP_INTERVAL_SECONDS = 30;

    private static final String NOT_OUR_STICKER = "Этот стикер не из паков этого чата.";
    private static final String DELETED = "Стикер удалён из пака.";
    private static final String DELETE_FAILED = "Не получилось удалить стикер: ";
    private static final String EXPIRED = "Голосование устарело — вызовите /del заново.";
    private static final String OWN_VOTE = "Нельзя подтвердить своё же удаление.";
    private static final String ALREADY_VOTED = "Вы уже подтвердили.";

    private final TelegramApiClient client;
    private final StickerService quoteStickerService;
    private final PlainStickerService plainStickerService;
    private final ApprovalSettingsStore quoteApprovalSettings;
    private final ApprovalSettingsStore stickerApprovalSettings;
    private final PendingDeletions pending;

    DeletionVote(TelegramApiClient client, StickerService quoteStickerService, PlainStickerService plainStickerService,
                 ApprovalSettingsStore quoteApprovalSettings, ApprovalSettingsStore stickerApprovalSettings,
                 PendingDeletions pending) {
        this.client = client;
        this.quoteStickerService = quoteStickerService;
        this.plainStickerService = plainStickerService;
        this.quoteApprovalSettings = quoteApprovalSettings;
        this.stickerApprovalSettings = stickerApprovalSettings;
        this.pending = pending;
        startSweeper();
    }

    /**
     * Acts on /del sent as a reply to a sticker: either deletes it outright
     * (approvals turned off for its kind) or posts the vote the deletion now
     * waits on.
     *
     * <p>Everything about the outcome replies to the sticker message rather than
     * to the command, so voters can see which sticker is on the block without
     * hunting for it. Only the "wrong sticker" complaint goes back to the caller,
     * since that is feedback about the command, not about a sticker.
     */
    void requestDeletion(long chatId, long requesterUserId, long commandMessageId, JsonNode stickerMessage) {
        long stickerMessageId = stickerMessage.path("message_id").asLong();
        String fileUniqueId = stickerMessage.path("sticker").path("file_unique_id").asText(null);

        PendingDeletions.Kind kind;
        String fileId;
        ApprovalSettingsStore approvalSettings;
        Optional<String> quoteFileId = quoteStickerService.findInChatPack(chatId, fileUniqueId);
        if (quoteFileId.isPresent()) {
            kind = PendingDeletions.Kind.QUOTE;
            fileId = quoteFileId.get();
            approvalSettings = quoteApprovalSettings;
        } else {
            Optional<String> stickerFileId = plainStickerService.findInChatPack(chatId, fileUniqueId);
            if (stickerFileId.isEmpty()) {
                client.sendMessage(chatId, NOT_OUR_STICKER, null, commandMessageId);
                return;
            }
            kind = PendingDeletions.Kind.STICKER;
            fileId = stickerFileId.get();
            approvalSettings = stickerApprovalSettings;
        }

        int required = approvalSettings.getApproveCount();
        if (required <= 0) {
            deleteAndReport(chatId, kind, fileId, stickerMessageId);
            return;
        }

        Duration ttl = Duration.ofSeconds(approvalSettings.getApproveTimeoutSeconds());
        JsonNode sent = client.sendMessage(chatId, votePrompt(required), null, stickerMessageId,
                markup(0, required, ttl));
        pending.put(chatId, sent.path("message_id").asLong(), kind, fileId, stickerMessageId, requesterUserId,
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
        deleteAndReport(chatId, vote.kind(), vote.stickerFileId(), vote.stickerMessageId());
        return true;
    }

    /**
     * The vote message is already gone by this point, so a failure here has to be
     * reported explicitly — otherwise the chat sees nothing happen at all.
     */
    private void deleteAndReport(long chatId, PendingDeletions.Kind kind, String fileId, Long replyToMessageId) {
        try {
            if (kind == PendingDeletions.Kind.QUOTE) {
                quoteStickerService.deleteQuote(fileId);
            } else {
                plainStickerService.deleteSticker(fileId);
            }
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
            Thread thread = new Thread(runnable, "del-sweeper");
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
