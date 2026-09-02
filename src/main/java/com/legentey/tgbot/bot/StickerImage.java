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
 * quote-api renders at whatever size fits its content, so the result is resized
 * here before upload.
 */
final class StickerImage {

    private static final int STICKER_SIZE = 512;

    private StickerImage() {
    }

    static byte[] normalizeForSticker(byte[] png) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(png));
            int width = original.getWidth();
            int height = original.getHeight();
            int longerSide = Math.max(width, height);
            if (longerSide == STICKER_SIZE) {
                return png;
            }

            double scale = (double) STICKER_SIZE / longerSide;
            int newWidth = Math.max(1, (int) Math.round(width * scale));
            int newHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, newWidth, newHeight, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
