package com.legentey.tgbot.bot;

import com.legentey.tgbot.storage.ChatUser;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the actual "@everyone" messages, using MarkdownV2.
 *
 * <p>Users with a public {@code @username} are mentioned by it directly (renders
 * reliably everywhere). Users without one are mentioned via a {@code tg://user?id=}
 * link on their display name, which Telegram clients render as a clickable mention
 * even though the user has no username.
 */
public final class MentionFormatter {

    // Stay comfortably under Telegram's 4096-character message limit.
    private static final int MAX_MESSAGE_LENGTH = 3500;

    private MentionFormatter() {
    }

    public static List<String> buildMentionMessages(List<ChatUser> users) {
        List<String> messages = new ArrayList<>();
        if (users.isEmpty()) {
            return messages;
        }

        StringBuilder current = new StringBuilder();
        boolean currentEmpty = true;

        for (ChatUser user : users) {
            String token = mentionToken(user);
            // +1 for the separating space we add between tokens.
            if (!currentEmpty && current.length() + token.length() + 1 > MAX_MESSAGE_LENGTH) {
                messages.add(current.toString());
                current = new StringBuilder();
                currentEmpty = true;
            }
            if (!currentEmpty) {
                current.append(' ');
            }
            current.append(token);
            currentEmpty = false;
        }

        if (!currentEmpty) {
            messages.add(current.toString());
        }
        return messages;
    }

    private static String mentionToken(ChatUser user) {
        if (user.username() != null && !user.username().isBlank()) {
            // Usernames may contain "_", which MarkdownV2 reads as an italic marker: an odd
            // number of them across the message makes Telegram reject the whole send.
            return "@" + escape(user.username());
        }
        return "[" + escape(user.displayName()) + "](tg://user?id=" + user.userId() + ")";
    }

    /** Escapes MarkdownV2 special characters in plain (non-URL) text. */
    public static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if ("_*[]()~`>#+-=|{}.!\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
