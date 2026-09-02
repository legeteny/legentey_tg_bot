package com.legentey.tgbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the Telegram Bot HTTP API. Talks to it directly instead of
 * pulling in a full-blown Telegram bot library, since this bot only needs a
 * handful of methods (getUpdates, sendMessage, setMyCommands).
 */
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramApiClient(String botToken) {
        this.baseUrl = "https://api.telegram.org/bot" + botToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public JsonNode getMe() {
        return call("getMe", Map.of(), Duration.ofSeconds(15));
    }

    /** Long-polls Telegram for new updates. Blocks up to timeoutSeconds. */
    public JsonNode getUpdates(long offset, int timeoutSeconds) {
        Map<String, Object> params = Map.of(
                "offset", offset,
                "timeout", timeoutSeconds,
                "allowed_updates", new String[]{"message"}
        );
        // Give the HTTP call some headroom over Telegram's own long-poll timeout.
        Duration httpTimeout = Duration.ofSeconds(timeoutSeconds + 15L);
        return call("getUpdates", params, httpTimeout);
    }

    public JsonNode sendMessage(long chatId, String text, String parseMode, Long replyToMessageId) {
        var params = new java.util.HashMap<String, Object>();
        params.put("chat_id", chatId);
        params.put("text", text);
        if (parseMode != null) {
            params.put("parse_mode", parseMode);
        }
        if (replyToMessageId != null) {
            params.put("reply_to_message_id", replyToMessageId);
            params.put("allow_sending_without_reply", true);
        }
        return call("sendMessage", params, Duration.ofSeconds(20));
    }

    /** Copies a message from one chat into another. Returns the new message (with its own message_id). */
    public JsonNode forwardMessage(long chatId, long fromChatId, long messageId) {
        Map<String, Object> params = Map.of(
                "chat_id", chatId,
                "from_chat_id", fromChatId,
                "message_id", messageId
        );
        return call("forwardMessage", params, Duration.ofSeconds(20));
    }

    /** Registers the bot's command menu (the suggestions shown when a user types "/"). */
    public void setMyCommands(List<Map<String, String>> commands) {
        call("setMyCommands", Map.of("commands", commands), Duration.ofSeconds(15));
    }

    /** Creates a brand new sticker set owned by userId, seeded with one sticker. */
    public void createNewStickerSet(long userId, String name, String title, String stickerFileId,
                                     String format, List<String> emojiList) {
        Map<String, Object> params = Map.of(
                "user_id", userId,
                "name", name,
                "title", title,
                "stickers", List.of(inputSticker(stickerFileId, format, emojiList))
        );
        call("createNewStickerSet", params, Duration.ofSeconds(20));
    }

    /** Adds one more sticker to a set previously created by this same bot. */
    public void addStickerToSet(long userId, String name, String stickerFileId,
                                 String format, List<String> emojiList) {
        Map<String, Object> params = Map.of(
                "user_id", userId,
                "name", name,
                "sticker", inputSticker(stickerFileId, format, emojiList)
        );
        call("addStickerToSet", params, Duration.ofSeconds(20));
    }

    private static Map<String, Object> inputSticker(String stickerFileId, String format, List<String> emojiList) {
        return Map.of(
                "sticker", stickerFileId,
                "format", format,
                "emoji_list", emojiList
        );
    }

    private JsonNode call(String method, Map<String, Object> params, Duration timeout) {
        try {
            String body = mapper.writeValueAsString(params);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());

            boolean ok = json.path("ok").asBoolean(false);
            if (!ok) {
                int errorCode = json.path("error_code").asInt(-1);
                String description = json.path("description").asText("unknown error");
                Integer retryAfter = json.path("parameters").path("retry_after").isMissingNode()
                        ? null
                        : json.path("parameters").path("retry_after").asInt();
                throw new TelegramApiException(
                        "Telegram API method '" + method + "' failed: " + description, errorCode, retryAfter);
            }
            return json.path("result");
        } catch (IOException e) {
            throw new RuntimeException("I/O error calling Telegram method " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling Telegram method " + method, e);
        }
    }
}
