package com.extantape.bluemapmini;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads a pack's fonts and draws components the way Minecraft's client does, using the rules read from
 * the 26.3 client: providers must satisfy {@code ascent <= height}, a glyph's advance is
 * {@code (int)(0.5 + litWidth * scale) + 1}, its top is {@code textY + 7 - ascent}, and text starts at
 * {@code screenWidth / 2 - totalAdvance / 2}.
 */
final class SimulatedClient {
    record Glyph(BufferedImage sheet, int cellX, int cellY, int cellW, int cellH, float scale, int ascent, int advance) {}
    record Font(Map<Integer, Glyph> glyphs, Map<Integer, Integer> spaces) {}

    final Map<String, byte[]> files = new HashMap<>();
    final Map<String, Font> fonts = new HashMap<>();

    SimulatedClient(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) files.put(entry.getName(), in.readAllBytes());
        }
        for (String path : files.keySet()) {
            if (path.startsWith("assets/bluemapmini/font/") && path.endsWith(".json")) {
                String name = path.substring("assets/bluemapmini/font/".length(), path.length() - 5);
                fonts.put(name, loadFont(new String(files.get(path), StandardCharsets.UTF_8)));
            }
        }
    }

    private Font loadFont(String json) throws IOException {
        Map<Integer, Glyph> glyphs = new HashMap<>();
        Map<Integer, Integer> spaces = new HashMap<>();
        Map<String, BufferedImage> sheets = new HashMap<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            if (provider.get("type").getAsString().equals("space")) {
                for (Map.Entry<String, JsonElement> advance : provider.getAsJsonObject("advances").entrySet()) {
                    int codepoint = advance.getKey().codePointAt(0);
                    if (spaces.put(codepoint, advance.getValue().getAsInt()) != null) throw new AssertionError("Duplicate space");
                }
                continue;
            }
            int height = provider.get("height").getAsInt();
            int ascent = provider.get("ascent").getAsInt();
            if (ascent > height) throw new AssertionError("Client rejects ascent " + ascent + " > height " + height);
            String file = "assets/bluemapmini/textures/" + provider.get("file").getAsString().substring("bluemapmini:".length());
            byte[] png = files.get(file);
            if (png == null) throw new AssertionError("Missing texture " + file);
            BufferedImage sheet = sheets.computeIfAbsent(file, f -> {
                try {
                    return ImageIO.read(new ByteArrayInputStream(png));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });

            var rows = provider.getAsJsonArray("chars");
            int[][] chars = new int[rows.size()][];
            for (int i = 0; i < chars.length; i++) chars[i] = rows.get(i).getAsString().codePoints().toArray();
            int columns = chars[0].length;
            for (int[] row : chars) {
                if (row.length != columns) throw new AssertionError("Client rejects uneven chars rows");
            }
            if (sheet.getWidth() % columns != 0 || sheet.getHeight() % chars.length != 0) throw new AssertionError("Sheet doesn't divide evenly");
            int cellW = sheet.getWidth() / columns, cellH = sheet.getHeight() / chars.length;
            float scale = (float) height / cellH;
            if (cellH > 256 || cellW > 256) throw new AssertionError("Glyph too big for the font atlas");
            for (int r = 0; r < chars.length; r++) {
                for (int c = 0; c < columns; c++) {
                    int codepoint = chars[r][c];
                    if (codepoint == 0) continue;
                    int lit = litWidth(sheet, c * cellW, r * cellH, cellW, cellH);
                    int advance = (int) (0.5 + lit * scale) + 1;
                    if (glyphs.put(codepoint, new Glyph(sheet, c * cellW, r * cellH, cellW, cellH, scale, ascent, advance)) != null) {
                        throw new AssertionError("Codepoint declared twice: " + Integer.toHexString(codepoint));
                    }
                }
            }
        }
        return new Font(glyphs, spaces);
    }

    private static int litWidth(BufferedImage sheet, int x0, int y0, int w, int h) {
        for (int x = w - 1; x >= 0; x--) {
            for (int y = 0; y < h; y++) {
                if (sheet.getRGB(x0 + x, y0 + y) >>> 24 != 0) return x + 1;
            }
        }
        return 0;
    }

    /** The sum of all advances, which the client uses for centring. */
    int totalAdvance(Component component) {
        Font font = fonts.get(component.style().font().value());
        int total = 0;
        for (Component part : component.children()) {
            total += ((TextComponent) part).content().codePoints().map(c -> advance(font, c)).sum();
        }
        return total;
    }

    private int advance(Font font, int codepoint) {
        Integer space = font.spaces.get(codepoint);
        if (space != null) return space;
        Glyph glyph = font.glyphs.get(codepoint);
        if (glyph == null) throw new AssertionError("Missing glyph " + Integer.toHexString(codepoint) + ": the client would draw a box");
        return glyph.advance;
    }

    /** Draws a component at a text origin onto an image, in GUI pixels. */
    void draw(Component component, BufferedImage image, int textY) {
        Font font = fonts.get(component.style().font().value());
        int x = image.getWidth() / 2 - totalAdvance(component) / 2;
        for (Component part : component.children()) {
            int color = part.color() == null ? 0xFFFFFF : part.color().value();
            for (int codepoint : ((TextComponent) part).content().codePoints().toArray()) {
                Glyph glyph = font.glyphs.get(codepoint);
                if (glyph != null) blit(image, glyph, x, textY + 7 - glyph.ascent, color);
                x += advance(font, codepoint);
            }
        }
    }

    private static void blit(BufferedImage image, Glyph glyph, int left, int top, int color) {
        int width = Math.round(glyph.cellW * glyph.scale), height = Math.round(glyph.cellH * glyph.scale);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sx = glyph.cellX + (int) (x / glyph.scale), sy = glyph.cellY + (int) (y / glyph.scale);
                int texel = glyph.sheet.getRGB(sx, sy), alpha = texel >>> 24;
                if (alpha < 26) continue; // The text shader discards anything under 0.1.
                int px = left + x, py = top + y;
                // The shader multiplies the texture's colour by the text colour, so white glyphs take the text colour.
                int tinted = multiply(texel, color);
                if (px >= 0 && py >= 0 && px < image.getWidth() && py < image.getHeight()) image.setRGB(px, py, 0xFF000000 | mix(image.getRGB(px, py), tinted, alpha));
            }
        }
    }

    private static int multiply(int texel, int color) {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            result |= ((texel >> shift & 255) * (color >> shift & 255) / 255) << shift;
        }
        return result;
    }

    private static int mix(int under, int over, int alpha) {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = under >> shift & 255, b = over >> shift & 255;
            result |= (a + (b - a) * alpha / 255) << shift;
        }
        return result;
    }

    /** The uncompressed and zlib-compressed sizes of the component as it goes in a packet, approximating NBT. */
    static int[] packetBytes(Component component) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Component part : component.children()) {
            byte[] text = ((TextComponent) part).content().getBytes(StandardCharsets.UTF_8);
            raw.writeBytes(new byte[] {8, 0, 4, 't', 'e', 'x', 't', (byte) (text.length >> 8), (byte) text.length});
            raw.writeBytes(text);
            if (part.color() != null) {
                raw.writeBytes(new byte[] {8, 0, 5, 'c', 'o', 'l', 'o', 'r', 0, 7});
                raw.writeBytes(part.color().asHexString().getBytes(StandardCharsets.UTF_8));
            }
            raw.write(0);
        }
        byte[] bytes = raw.toByteArray();
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        byte[] out = new byte[bytes.length + 64];
        int compressed = deflater.deflate(out);
        deflater.end();
        return new int[] {bytes.length, compressed};
    }
}
