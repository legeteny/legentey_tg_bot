package com.legentey.tgbot.storage;

import java.util.List;

public interface QuoteStickerStore {

    /** Records the render inputs behind a sticker just added to a pack. */
    void save(long chatId, String packName, long ownerUserId, String fileId, String fromJson, String text,
              String entitiesJson, String emoji, String avatarFileId, String emojiStatus, String username);

    /** All pack names that have at least one remembered quote sticker. */
    List<String> findDistinctPackNames();

    /** All remembered stickers for one pack, oldest first (matches their position in the set). */
    List<QuoteSticker> findByPack(String packName);

    /** After a replace, the sticker's file_id changes — keep it current for the next replace. */
    void updateFileId(long id, String newFileId);

    /** Backfills a profile field a rerender had to fetch live, so the next rerender reuses it. */
    void updateProfile(long id, String avatarFileId, String emojiStatus, String username);

    /** Drops a record whose sticker no longer exists in its pack (deleted by hand, pack recreated). */
    void delete(long id);
}
