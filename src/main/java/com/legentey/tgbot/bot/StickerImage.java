package com.legentey.tgbot.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Telegram requires a static sticker's PNG to have its longer side at exactly
 * 512px (the other side ≤512px) — {@code STICKER_PNG_DIMENSIONS} otherwise.
 * quote-api renders at whatever size fits its content, and /s can be fed a PNG
 * or WEBP picture of any size, so the result is resized (and always re-encoded
 * as an actual PNG, regardless of the source format) here before upload.
 */
final class StickerImage {

    private static final Logger log = LoggerFactory.getLogger(StickerImage.class);

    private static final int STICKER_SIZE = 512;

    private StickerImage() {
    }

    static byte[] normalizeForSticker(byte[] imageBytes) {
        try {
            BufferedImage original = decode(imageBytes);
            int width = original.getWidth();
            int height = original.getHeight();
            int longerSide = Math.max(width, height);

            // Skipping the resize when it's already the right size must NOT skip re-encoding:
            // returning imageBytes verbatim here would upload a non-PNG source (e.g. WEBP) as-is
            // under a PNG filename/content-type, which Telegram then rejects as bad PNG dimensions.
            BufferedImage toEncode = original;
            int newWidth = width;
            int newHeight = height;
            if (longerSide != STICKER_SIZE) {
                double scale = (double) STICKER_SIZE / longerSide;
                newWidth = Math.min(STICKER_SIZE, Math.max(1, (int) Math.round(width * scale)));
                newHeight = Math.min(STICKER_SIZE, Math.max(1, (int) Math.round(height * scale)));
                // Whichever side started as the longer one must land on exactly 512 — clamping
                // above protects the *other* side from a rounding overshoot, and this restores
                // the longer side in case clamping (or rounding) nudged it off target.
                if (width >= height) {
                    newWidth = STICKER_SIZE;
                } else {
                    newHeight = STICKER_SIZE;
                }

                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(original, 0, 0, newWidth, newHeight, null);
                g.dispose();
                toEncode = resized;
            }
            log.debug("Sticker source decoded as {}x{}, encoding as {}x{}", width, height, newWidth, newHeight);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(toEncode, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes via the first ImageIO reader that claims the bytes, logging which reader
     * that was — the JDK ships no WEBP reader on its own, so this is how the imageio-webp
     * dependency's plugin is confirmed to actually be the one used, versus e.g. a stray
     * false-positive match from an unrelated built-in reader.
     */
    private static BufferedImage decode(byte[] imageBytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (iis == null) {
                throw new IOException("ImageIO could not open an input stream for this file");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                // No registered reader recognizes the bytes — a corrupt file, or a genuinely
                // unsupported format (e.g. the imageio-webp plugin isn't actually on the classpath).
                throw new IOException("no ImageIO reader recognizes this file");
            }
            ImageReader reader = readers.next();
            log.debug("Decoding sticker source with {}", reader.getClass().getName());
            try {
                reader.setInput(iis);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
