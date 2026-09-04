package com.legentey.tgbot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.legentey.tgbot.storage.ApprovalSettingsStore;
import com.legentey.tgbot.telegram.TelegramApiClient;
import com.legentey.tgbot.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles /q_del: deleting a quote takes the agreement of other people in the
 * chat, so the command opens a vote rather than removing anything on the spot.
 * How many others have to agree is the admin's call (/q_set_approve_count), and
 * at zero the vote is skipped entirely.
 */
class QuoteDeletionVote {

    private static final Logger log = LoggerFactory.getLogger(QuoteDeletionVote.class);

    private static final String CALLBACK_DATA = "qdel|ok";
    private static final long SWEEP_INTERVAL_SECONDS = 30;

    private static final String NOT_OUR_STICKER = "Этот стикер не из цитат этого чата.";
    private static final String VOTE_PROMPT = "Удалить эту цитату?";
    private static final String DELETED = "Цитата удалена.";
    private static final String DELETE_FAILED = "Не получилось удалить стикер: ";
    private static final String EXPIRED = "Голосование устарело — вызовите /q_del заново.";
    private static final String OWN_VOTE = "Нельзя подтвердить своё же удаление.";
    private static final String ALREADY_VOTED = "Вы уже подтвердили.";

    private final TelegramApiClient client;
    private final StickerService stickerService;
    private final ApprovalSettingsStore approvalSettings;
    private final PendingDeletions pending;

    QuoteDeletionVote(TelegramApiClient client, StickerService stickerService,
                      ApprovalSettingsStore approvalSettings, PendingDeletions pending) {
        this.client = client;
        this.stickerService = stickerService;
        this.approvalSettings = approvalSettings;
        this.pending = pending;
        startSweeper();
    }

    /**
     * Acts on /q_del sent as a reply to a sticker: either deletes the quote outright
     * (approvals turned off) or posts the vote the deletion now waits on.
     */
    void requestDeletion(long chatId, long requesterUserId, long commandMessageId, JsonNode repliedSticker) {
        Optional<String> fileId = stickerService.findInChatPack(
                chatId, repliedSticker.path("file_unique_id").asText(null));
        if (fileId.isEmpty()) {
            client.sendMessage(chatId, NOT_OUR_STICKER, null, commandMessageId);
            return;
        }

        int required = approvalSettings.getApproveCount();
        if (required <= 0) {
            deleteAndReport(chatId, fileId.get(), commandMessageId);
            return;
        }

        JsonNode sent = client.sendMessage(chatId, VOTE_PROMPT, null, commandMessageId, markup(0, required));
        pending.put(chatId, sent.path("message_id").asLong(), fileId.get(), requesterUserId, required);
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
            editMarkupQuietly(chatId, messageId, markup(approved, vote.required()));
            return true;
        }
        pending.remove(chatId, messageId);
        deleteQuietly(chatId, messageId);
        deleteAndReport(chatId, vote.stickerFileId(), null);
        return true;
    }

    /**
     * The vote message is already gone by this point, so a failure here has to be
     * reported explicitly — otherwise the chat sees nothing happen at all.
     */
    private void deleteAndReport(long chatId, String fileId, Long replyToMessageId) {
        try {
            stickerService.deleteQuote(fileId);
        } catch (TelegramApiException e) {
            log.warn("Failed to delete a quote sticker in chat {}: {}", chatId, e.getMessage());
            client.sendMessage(chatId, DELETE_FAILED + e.getMessage(), null, replyToMessageId);
            return;
        }
        client.sendMessage(chatId, DELETED, null, replyToMessageId);
    }

    /** Takes down the buttons of votes nobody finished, so no stale one is left to tap. */
    private void startSweeper() {
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qdel-sweeper");
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
        } catch (RuntimeException e) {
            // A throw here would kill the scheduled task for good.
            log.warn("Failed to sweep expired deletion votes", e);
        }
    }

    private static Map<String, Object> markup(int approved, int required) {
        return Map.of("inline_keyboard", List.of(List.of(Map.of(
                "text", "Удалить (" + approved + "/" + required + ")",
                "callback_data", CALLBACK_DATA))));
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
