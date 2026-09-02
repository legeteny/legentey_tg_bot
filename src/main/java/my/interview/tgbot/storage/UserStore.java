package my.interview.tgbot.storage;

import java.util.List;

public interface UserStore {

    /** Inserts or updates a known member of a chat, seen via a message or a join event. */
    void upsertUser(long chatId, long userId, String username, String firstName, String lastName);

    /** Removes a user from a chat's known-members list (e.g. they left the chat). */
    void removeUser(long chatId, long userId);

    List<ChatUser> listChatUsers(long chatId);
}
