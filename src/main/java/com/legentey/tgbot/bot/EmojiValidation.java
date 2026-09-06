package com.legentey.tgbot.bot;

/** Shared by {@link QuoteEmojiPicker} and {@link StickerEmojiPicker}. */
final class EmojiValidation {

    private EmojiValidation() {
    }

    /**
     * addStickerToSet rejects an emoji_list holding anything but emoji, so the
     * obvious non-emoji is caught here instead of surfacing as an API error.
     */
    static boolean isPlausibleEmoji(String text) {
        if (text.isEmpty() || text.length() > 16) {
            return false;
        }
        boolean hasSymbol = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint < 0x80 || Character.isWhitespace(codePoint)) {
                return false;
            }
            if (codePoint >= 0x2000) {
                hasSymbol = true;
            }
            i += Character.charCount(codePoint);
        }
        return hasSymbol;
    }
}
