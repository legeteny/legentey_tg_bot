package com.legentey.tgbot.quoteapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to a self-hosted instance of https://github.com/LyoSU/quote-api — the
 * actual rendering engine behind @QuotLyBot, minus the Telegram bot-polling
 * frontend. Renders a "quote card" PNG directly from message data we already
 * have (real author, real text/entities), so there's no need to relay anything
 * through another bot to get correct attribution.
 */
public class QuoteApiClient {

    private final String baseUrl;
    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param botToken sent back to quote-api on every request so it can recognize
     *                 these as our own bot's calls and exempt them from its
     *                 per-IP rate limit (quote-api whitelists requests whose
     *                 "botToken" field matches its own BOT_TOKEN — otherwise a
     *                 batch rerender of more than ~20 stickers within under a
     *                 minute starts getting HTTP 429'd).
     */
    public QuoteApiClient(String baseUrl, String botToken) {
        this.baseUrl = baseUrl;
        this.botToken = botToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Renders a single-message quote card and returns PNG bytes.
     *
     * @param width max line width handed to quote-api, against its own default of
     *              512 (see RenderSettingsStore/{@code /q_set_width}). A narrower
     *              card wraps sooner, so a quote of any length comes out closer to
     *              square — and since the render is downscaled to a 512px sticker
     *              by its longer side, a squarer card survives that downscale with
     *              larger text.
     */
    public byte[] generateQuotePng(JsonNode from, String text, JsonNode entities, int width) {
        ObjectNode message = mapper.createObjectNode();
        message.set("from", from);
        message.put("text", text == null ? "" : text);
        message.put("avatar", true);
        if (entities != null && entities.isArray() && !entities.isEmpty()) {
            message.set("entities", entities);
        }

        // quote-api treats "height" purely as a truncation budget: text overflowing
        // it is cut with an ellipsis. Derived from the width so that narrowing the
        // card never costs text: whatever fraction of 512 the width gives up, the
        // height gains back.
        int height = 512 * 512 / width;

        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("messages").add(message);
        payload.put("width", width);
        payload.put("height", height);
        payload.put("botToken", botToken);

        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/generate.png"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("quote-api returned HTTP " + response.statusCode() + ": "
                        + new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("I/O error calling quote-api", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling quote-api", e);
        }
    }
}
