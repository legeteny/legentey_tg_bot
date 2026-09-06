package com.legentey.tgbot.bot;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/** Shared by {@link QuoteEmojiPicker} and {@link StickerEmojiPicker}. */
final class EmojiValidation {

    /** Up to Telegram's own emoji_list cap (20 per sticker) worth of 2-UTF16-unit emoji, with headroom. */
    private static final int MAX_LENGTH = 64;

    private EmojiValidation() {
    }

    /**
     * addStickerToSet rejects an emoji_list holding anything but emoji, so the
     * obvious non-emoji is caught here instead of surfacing as an API error. Passes
     * for one emoji as much as for several typed in a row — {@link #splitEmoji}
     * is what breaks a passing string into individual emoji_list entries.
     */
    static boolean isPlausibleEmoji(String text) {
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
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

    /**
     * Splits text into its individual grapheme clusters, in order — each one
     * meant as one entry of Telegram's emoji_list. Also how a sticker's own
     * Sticker.emoji field is unpacked: it's a single String, but a sticker tagged
     * with several emoji at creation time comes back as all of them concatenated
     * with no separator (e.g. "😀😄🎉"). Empty (not null) if text is blank.
     */
    static List<String> splitEmoji(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String cluster = text.substring(start, end);
            if (!cluster.isBlank()) {
                result.add(cluster);
            }
        }
        return result;
    }
}
