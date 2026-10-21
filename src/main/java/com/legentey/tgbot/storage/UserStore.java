package com.legentey.tgbot.storage;

import java.util.List;

public interface UserStore {

    /**
     * Inserts or updates a known member of a chat, seen via a message or a join event.
     * Absorbs any hand-added {@code @username}-only entry for the same username.
     */
    void upsertUser(long chatId, long userId, String username, String firstName, String lastName);

    boolean hasUser(long chatId, long userId);

    /**
     * Adds a hand-entered {@code @username} (without the "@") with no user id yet.
     * Returns false if someone with that username is already on the chat's list.
     */
    boolean addUsername(long chatId, String username);

    /** Removes a user from a chat's known-members list (e.g. they left the chat). Returns false if absent. */
    boolean removeUser(long chatId, long userId);

    /** Removes everyone on the chat's list with this username (without the "@"). Returns false if none. */
    boolean removeUsername(long chatId, String username);

    List<ChatUser> listChatUsers(long chatId);
}
