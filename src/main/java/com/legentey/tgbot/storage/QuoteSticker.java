package com.legentey.tgbot.storage;

/**
 * The render inputs behind one quote sticker already sitting in a pack, kept so
 * the sticker can later be regenerated (e.g. after a quote-api layout change)
 * and swapped in place via replaceStickerInSet.
 *
 * <p>avatarFileId/emojiStatus/username are the author's profile as it looked at
 * creation time (or last rerender) — frozen on purpose so a rerender reproduces
 * the same-looking card. Any of the three can be null (never captured, or the
 * author simply had none at the time); a rerender then falls back to that
 * author's current value instead of leaving the field empty.
 */
public record QuoteSticker(
        long id,
        long chatId,
        String packName,
        long ownerUserId,
        String fileId,
        String fromJson,
        String text,
        String entitiesJson,
        String emoji,
        String avatarFileId,
        String emojiStatus,
        String username
) {
}
