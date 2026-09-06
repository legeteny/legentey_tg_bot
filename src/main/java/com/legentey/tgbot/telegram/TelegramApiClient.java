package com.legentey.tgbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the Telegram Bot HTTP API. Talks to it directly instead of
 * pulling in a full-blown Telegram bot library, since this bot only needs a
 * handful of methods (getUpdates, sendMessage, setMyCommands, sticker packs).
 */
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private final String baseUrl;
    private final String fileBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramApiClient(String botToken) {
        this.baseUrl = "https://api.telegram.org/bot" + botToken;
        this.fileBaseUrl = "https://api.telegram.org/file/bot" + botToken;
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
                "allowed_updates", new String[]{"message", "callback_query"}
        );
        // Give the HTTP call some headroom over Telegram's own long-poll timeout.
        Duration httpTimeout = Duration.ofSeconds(timeoutSeconds + 15L);
        return call("getUpdates", params, httpTimeout);
    }

    public JsonNode sendMessage(long chatId, String text, String parseMode, Long replyToMessageId) {
        return sendMessage(chatId, text, parseMode, replyToMessageId, null);
    }

    /** replyMarkup is a plain Map/List structure, serialized as Telegram's reply_markup JSON. */
    public JsonNode sendMessage(long chatId, String text, String parseMode, Long replyToMessageId, Object replyMarkup) {
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
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        }
        return call("sendMessage", params, Duration.ofSeconds(20));
    }

    public void deleteMessage(long chatId, long messageId) {
        call("deleteMessage", Map.of("chat_id", chatId, "message_id", messageId), Duration.ofSeconds(15));
    }

    /** Clears the spinner Telegram shows on a tapped inline button; text != null pops up a toast. */
    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        var params = new java.util.HashMap<String, Object>();
        params.put("callback_query_id", callbackQueryId);
        if (text != null) {
            params.put("text", text);
            params.put("show_alert", showAlert);
        }
        call("answerCallbackQuery", params, Duration.ofSeconds(15));
    }

    /** Swaps the inline keyboard on an existing message, leaving its text alone. */
    public void editMessageReplyMarkup(long chatId, long messageId, Object replyMarkup) {
        var params = new java.util.HashMap<String, Object>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        }
        call("editMessageReplyMarkup", params, Duration.ofSeconds(15));
    }

    /** Registers the bot's command menu (the suggestions shown when a user types "/"). */
    public void setMyCommands(List<Map<String, String>> commands) {
        setMyCommands(commands, null);
    }

    /**
     * scope is Telegram's BotCommandScope as a plain Map — a narrower scope overrides
     * the default one for the chats it covers. null publishes to the default scope,
     * which is what every chat without a more specific menu sees.
     */
    public void setMyCommands(List<Map<String, String>> commands, Object scope) {
        var params = new java.util.HashMap<String, Object>();
        params.put("commands", commands);
        if (scope != null) {
            params.put("scope", scope);
        }
        call("setMyCommands", params, Duration.ofSeconds(15));
    }

    /** Full chat/user info — used for fields not present on a plain message's "from" (e.g. emoji_status). */
    public JsonNode getChat(long chatId) {
        return call("getChat", Map.of("chat_id", chatId), Duration.ofSeconds(15));
    }

    /** Creates a brand new static sticker set owned by userId, seeded with one PNG sticker. */
    public void createNewStickerSet(long userId, String name, String title, byte[] pngBytes, List<String> emojiList) {
        createNewStickerSet(userId, name, title, pngBytes, "sticker.png", "image/png", emojiList);
    }

    /**
     * Same, but for a file that isn't necessarily PNG (e.g. a WEBP uploaded as-is,
     * un-normalized, because the JDK has no built-in WEBP decoder to resize it with).
     */
    public void createNewStickerSet(long userId, String name, String title, byte[] fileBytes, String fileName,
                                     String contentType, List<String> emojiList) {
        Map<String, String> fields = Map.of(
                "user_id", String.valueOf(userId),
                "name", name,
                "title", title,
                "stickers", "[" + inputStickerJson(emojiList) + "]"
        );
        callMultipart("createNewStickerSet", fields, fileBytes, fileName, contentType, Duration.ofSeconds(30));
    }

    /** Adds one more PNG sticker to a set previously created by this same bot. */
    public void addStickerToSet(long userId, String name, byte[] pngBytes, List<String> emojiList) {
        addStickerToSet(userId, name, pngBytes, "sticker.png", "image/png", emojiList);
    }

    /** Same, but for a file that isn't necessarily PNG — see the other overload's note. */
    public void addStickerToSet(long userId, String name, byte[] fileBytes, String fileName, String contentType,
                                 List<String> emojiList) {
        Map<String, String> fields = Map.of(
                "user_id", String.valueOf(userId),
                "name", name,
                "sticker", inputStickerJson(emojiList)
        );
        callMultipart("addStickerToSet", fields, fileBytes, fileName, contentType, Duration.ofSeconds(30));
    }

    /** Removes one sticker from the bot-owned set it belongs to. */
    public void deleteStickerFromSet(String stickerFileId) {
        call("deleteStickerFromSet", Map.of("sticker", stickerFileId), Duration.ofSeconds(20));
    }

    /** Full sticker set info, including each sticker's current file_id and its order in the set. */
    public JsonNode getStickerSet(String name) {
        return call("getStickerSet", Map.of("name", name), Duration.ofSeconds(15));
    }

    /**
     * Swaps one sticker in a bot-owned set for a freshly rendered PNG. Telegram
     * assigns the replacement a new file_id — and its documented position-preserving
     * behavior isn't reliably observed in practice, so callers that care about order
     * should follow up with {@link #setStickerPositionInSet}.
     */
    public void replaceStickerInSet(long userId, String name, String oldStickerFileId,
                                     byte[] pngBytes, List<String> emojiList) {
        Map<String, String> fields = Map.of(
                "user_id", String.valueOf(userId),
                "name", name,
                "old_sticker", oldStickerFileId,
                "sticker", inputStickerJson(emojiList)
        );
        callMultipart("replaceStickerInSet", fields, pngBytes, "sticker.png", "image/png", Duration.ofSeconds(30));
    }

    /** Metadata for an uploaded file, including "file_path" — the argument {@link #downloadFile} needs. */
    public JsonNode getFile(String fileId) {
        return call("getFile", Map.of("file_id", fileId), Duration.ofSeconds(15));
    }

    /** Downloads a file's raw bytes given the file_path from {@link #getFile}. */
    public byte[] downloadFile(String filePath) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fileBaseUrl + "/" + filePath))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download file " + filePath + ": HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("I/O error downloading file " + filePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while downloading file " + filePath, e);
        }
    }

    /** Explicitly pins a sticker to a 0-indexed position within its own set. */
    public void setStickerPositionInSet(String stickerFileId, int position) {
        Map<String, Object> params = Map.of(
                "sticker", stickerFileId,
                "position", position
        );
        call("setStickerPositionInSet", params, Duration.ofSeconds(15));
    }

    /**
     * Creates a brand new sticker set of the given format (static/animated/video),
     * seeded with a sticker that already exists on Telegram's servers — no file
     * upload needed, so this is a plain JSON call rather than multipart.
     */
    public void createNewStickerSetFromFileId(long userId, String name, String title, String fileId, String format,
                                               List<String> emojiList) {
        Map<String, Object> params = Map.of(
                "user_id", userId,
                "name", name,
                "title", title,
                "stickers", List.of(fileIdInputSticker(fileId, format, emojiList))
        );
        call("createNewStickerSet", params, Duration.ofSeconds(30));
    }

    /**
     * Adds one more sticker — already existing on Telegram's servers — to a set
     * previously created by this same bot.
     */
    public void addStickerToSetFromFileId(long userId, String name, String fileId, String format,
                                           List<String> emojiList) {
        Map<String, Object> params = Map.of(
                "user_id", userId,
                "name", name,
                "sticker", fileIdInputSticker(fileId, format, emojiList)
        );
        call("addStickerToSet", params, Duration.ofSeconds(30));
    }

    private static Map<String, Object> fileIdInputSticker(String fileId, String format, List<String> emojiList) {
        return Map.of(
                "sticker", fileId,
                "format", format,
                "emoji_list", emojiList
        );
    }

    private String inputStickerJson(List<String> emojiList) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "sticker", "attach://sticker_file",
                    "format", "static",
                    "emoji_list", emojiList
            ));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize sticker payload", e);
        }
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
            return parseResult(method, response.body());
        } catch (IOException e) {
            throw new RuntimeException("I/O error calling Telegram method " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling Telegram method " + method, e);
        }
    }

    /** Same as {@link #call} but sends a multipart/form-data body with one attached file. */
    private JsonNode callMultipart(String method, Map<String, String> fields, byte[] fileBytes, String fileName,
                                    String contentType, Duration timeout) {
        String boundary = "TgBotBoundary" + System.nanoTime();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (var entry : fields.entrySet()) {
                writeField(out, boundary, entry.getKey(), entry.getValue());
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"sticker_file\"; filename=\"" + fileName + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResult(method, response.body());
        } catch (IOException e) {
            throw new RuntimeException("I/O error calling Telegram method " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling Telegram method " + method, e);
        }
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parseResult(String method, String responseBody) throws IOException {
        JsonNode json = mapper.readTree(responseBody);

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
    }
}
