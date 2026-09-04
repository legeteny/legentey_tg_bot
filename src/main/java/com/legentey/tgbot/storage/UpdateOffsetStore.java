package com.legentey.tgbot.storage;

import java.util.Optional;

/**
 * Remembers which Telegram update the bot has already claimed.
 *
 * <p>Telegram drops an update only once getUpdates is called with an offset above
 * its update_id, and keeps everything unconfirmed for up to 24 hours. Without a
 * durable record of what was already taken, a crash between handling an update and
 * the next poll makes the restarted bot run that same command again — one more
 * duplicate sticker per restart.
 */
public interface UpdateOffsetStore {

    /** Empty only on a bot's very first run, never once an offset has been stored. */
    Optional<Long> lastOffset();

    void saveOffset(long offset);
}
