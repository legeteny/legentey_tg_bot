package com.legentey.tgbot.storage;

/** userId is null for someone added by hand via /add @username whom the bot hasn't seen write yet. */
public record ChatUser(
        long chatId,
        Long userId,
        String username,
        String firstName,
        String lastName
) {

    public String displayName() {
        if (firstName != null && !firstName.isBlank()) {
            return lastName != null && !lastName.isBlank() ? firstName + " " + lastName : firstName;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return "User " + userId;
    }
}
