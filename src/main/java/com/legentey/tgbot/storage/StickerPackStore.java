package com.legentey.tgbot.storage;

import java.util.Optional;

public interface StickerPackStore {

    Optional<StickerPack> findForChat(long chatId);

    void save(long chatId, String packName, long ownerUserId);
}
