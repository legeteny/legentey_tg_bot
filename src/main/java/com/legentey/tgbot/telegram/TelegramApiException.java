package com.legentey.tgbot.telegram;

public class TelegramApiException extends RuntimeException {

    private final int errorCode;
    private final Integer retryAfterSeconds;

    public TelegramApiException(String message, int errorCode, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
