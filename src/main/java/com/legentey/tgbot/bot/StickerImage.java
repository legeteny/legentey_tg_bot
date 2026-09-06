package com.legentey.tgbot.bot;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Telegram requires a static sticker's PNG to have its longer side at exactly
 * 512px (the other side ≤512px) — {@code STICKER_PNG_DIMENSIONS} otherwise.
 * quote-api renders at whatever size fits its content, and /s can be fed a PNG
 * or WEBP picture of any size, so the result is resized (and always re-encoded
 * as an actual PNG, regardless of the source format) here before upload.
 */
final class StickerImage {

    private static final int STICKER_SIZE = 512;

    private StickerImage() {
    }

    static byte[] normalizeForSticker(byte[] imageBytes) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) {
                // ImageIO.read returns null (not an exception) when no registered reader
                // recognizes the bytes — a corrupt file, or a genuinely unsupported format.
                throw new IOException("no ImageIO reader could decode this file");
            }
            int width = original.getWidth();
            int height = original.getHeight();
            int longerSide = Math.max(width, height);

            // Skipping the resize when it's already the right size must NOT skip re-encoding:
            // returning imageBytes verbatim here would upload a non-PNG source (e.g. WEBP) as-is
            // under a PNG filename/content-type, which Telegram then rejects as bad PNG dimensions.
            BufferedImage toEncode = original;
            if (longerSide != STICKER_SIZE) {
                double scale = (double) STICKER_SIZE / longerSide;
                int newWidth = Math.max(1, (int) Math.round(width * scale));
                int newHeight = Math.max(1, (int) Math.round(height * scale));

                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(original, 0, 0, newWidth, newHeight, null);
                g.dispose();
                toEncode = resized;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(toEncode, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
