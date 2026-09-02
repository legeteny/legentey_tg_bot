package my.interview.tgbot.storage;

public record ChatUser(
        long chatId,
        long userId,
        String username,
        String firstName,
        String lastName,
        boolean optedOut
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
