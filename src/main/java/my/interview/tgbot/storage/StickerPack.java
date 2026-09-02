package my.interview.tgbot.storage;

/** The sticker set our bot created and manages for one chat. */
public record StickerPack(long chatId, String packName, long ownerUserId) {
}
