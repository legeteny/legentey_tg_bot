package com.legentey.tgbot.storage;

import java.util.List;
import java.util.Optional;

/**
 * One sticker pack per (chat, sticker format). Telegram locks a sticker set to a
 * single format (static/animated/video), so a chat that has collected /s
 * stickers of more than one format ends up with more than one pack.
 */
public interface RegularStickerPackStore {

    Optional<StickerPack> findForChat(long chatId, String format);

    /** Every pack this chat has, across all formats — used to locate a sticker for /s_del. */
    List<StickerPack> findAllForChat(long chatId);

    void save(long chatId, String format, String packName, long ownerUserId);
}
