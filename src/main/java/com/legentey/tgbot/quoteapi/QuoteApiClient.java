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

    /**
     * Max line width handed to quote-api, against its own default of 512. A
     * narrower card wraps sooner, so a quote of any length comes out closer to
     * square — and since the render is downscaled to a 512px sticker by its
     * longer side, a squarer card survives that downscale with larger text.
     */
    private static final int RENDER_WIDTH = 215;

    /**
     * quote-api treats "height" purely as a truncation budget: text overflowing
     * it is cut with an ellipsis. Raised in step with the narrower width
     * (512 / 0.42) so the same amount of text still fits before being cut.
     */
    private static final int RENDER_HEIGHT = 1220;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuoteApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Renders a single-message quote card and returns PNG bytes. */
    public byte[] generateQuotePng(JsonNode from, String text, JsonNode entities) {
        ObjectNode message = mapper.createObjectNode();
        message.set("from", from);
        message.put("text", text == null ? "" : text);
        message.put("avatar", true);
        if (entities != null && entities.isArray() && !entities.isEmpty()) {
            message.set("entities", entities);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("messages").add(message);
        payload.put("width", RENDER_WIDTH);
        payload.put("height", RENDER_HEIGHT);

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
