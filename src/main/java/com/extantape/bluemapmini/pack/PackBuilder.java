package com.extantape.bluemapmini.pack;

import com.extantape.bluemapmini.config.HudConfig;
import com.extantape.bluemapmini.render.FontLayout;
import com.extantape.bluemapmini.render.HudFonts;
import com.extantape.bluemapmini.render.HudIcon;
import com.extantape.bluemapmini.render.IconSet;
import com.extantape.bluemapmini.render.ReadoutFont;
import com.extantape.bluemapmini.render.TinyFont;
import com.extantape.bluemapmini.theme.Theme;
import com.extantape.bluemapmini.theme.ThemeSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the resource pack in memory. Everything lives under the bluemapmini namespace, apart from
 * the optional transparent boss bar textures. No shaders are involved.
 */
public final class PackBuilder {
    /** Format 84 is every 26.1 client (26.1, 26.1.1, 26.1.2), the oldest supported. The range stays open so new clients don't warn. */
    private static final int MIN_FORMAT = 84;
    private static final int MAX_FORMAT = 999;
    private static final int SHEET_COLUMNS = 16;
    private static final long FIXED_ZIP_TIME = 1_000_000_000_000L;
    private static final int RUN_CELL_WIDTH = FontLayout.MAX_RUN;
    /**
     * Below the text shader's 10% cut-off, so never drawn, but the client still counts it when it measures a
     * glyph. It gives the clock digits and pictures fixed widths.
     */
    private static final int WIDTH_MARKER = 0x14FFFFFF;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public record Built(byte[] zip, String sha1Hex, byte[] sha1) {}

    private PackBuilder() {}

    public static Built build(HudConfig config, HudFonts fonts, IconSet icons) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", packMeta());
        addLayout(files, fonts.minimap, icons, fonts.themes);
        addLayout(files, fonts.fullscreen, icons, fonts.themes);
        if (config.pack().hideBossBar()) {
            byte[] blank = png(new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB));
            String color = config.pack().bossBarColor();
            files.put("assets/minecraft/textures/gui/sprites/boss_bar/" + color + "_background.png", blank);
            files.put("assets/minecraft/textures/gui/sprites/boss_bar/" + color + "_progress.png", blank);
        }
        byte[] zip = zip(files);
        byte[] sha1 = sha1(zip);
        return new Built(zip, HexFormat.of().formatHex(sha1), sha1);
    }

    private static byte[] packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "BlueMapMini minimap");
        pack.addProperty("min_format", MIN_FORMAT);
        pack.addProperty("max_format", MAX_FORMAT);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static void addLayout(Map<String, byte[]> files, FontLayout layout, IconSet icons, ThemeSet themes) throws IOException {
        String name = layout.name();
        int runCells = FontLayout.paddedCells(layout.maxMapAscent(), layout.step(), 1);
        int iconCells = FontLayout.paddedCells(layout.maxIconAscent(), 1, HudIcon.SIZE);
        int textCells = FontLayout.paddedCells(layout.maxTextAscent(), 1, TinyFont.HEIGHT);

        files.put("assets/bluemapmini/textures/font/" + name + "_runs.png", png(runSheet(runCells)));
        files.put("assets/bluemapmini/textures/font/" + name + "_icons.png", png(iconSheet(icons, iconCells)));
        files.put("assets/bluemapmini/textures/font/" + name + "_text.png", png(textSheet(textCells)));
        int readoutCells = FontLayout.paddedCells(layout.maxReadoutAscent(), 1, ReadoutFont.HEIGHT);
        int plateCells = FontLayout.paddedCells(layout.maxPlateAscent(), 1, FontLayout.PLATE_HEIGHT);
        files.put("assets/bluemapmini/textures/font/" + name + "_readout.png", png(readoutSheet(readoutCells)));
        // One plate sheet for each opacity the themes use. The colour comes from the frame.
        for (int v = 0; v < themes.plateOpacities().size(); v++) {
            files.put("assets/bluemapmini/textures/font/" + name + "_plate" + v + ".png", png(plateSheet(plateCells, themes.plateOpacities().get(v))));
        }
        int clockCells = FontLayout.paddedCells(layout.maxClockAscent(), 1, Theme.CLOCK_SIZE);
        files.put("assets/bluemapmini/textures/font/" + name + "_clock.png", png(clockSheet(themes, clockCells)));

        JsonArray providers = new JsonArray();
        providers.add(spaceProvider());
        for (int row = 0; row < layout.rows(); row++) {
            String[] runs = new String[FontLayout.RUN_LENGTHS];
            for (int i = 0; i < runs.length; i++) runs[i] = new String(Character.toChars(layout.runChar(row, i)));
            providers.add(bitmap(name + "_runs", runCells * layout.step(), layout.mapAscent(row), runs));

            final int r = row;
            providers.add(bitmap(name + "_icons", iconCells, layout.iconAscent(row),
                    sheetRows(HudIcon.COUNT, i -> layout.iconChar(r, i))));
            providers.add(bitmap(name + "_text", textCells, layout.textAscent(row),
                    sheetRows(TinyFont.glyphCount(), i -> layout.textChar(r, i))));
            providers.add(bitmap(name + "_readout", readoutCells, layout.readoutAscent(row),
                    sheetRows(ReadoutFont.glyphCount(), i -> layout.readoutChar(r, i))));
            // Every row gets the first plate style, as before. The rest, and the clock, only where the
            // coordinate line can be.
            boolean readout = layout.readoutRow(row);
            for (int v = 0; v < (readout ? themes.plateOpacities().size() : 1); v++) {
                String[] plates = new String[FontLayout.PLATE_LENGTHS];
                for (int i = 0; i < plates.length; i++) plates[i] = new String(Character.toChars(layout.plateChar(row, v, i)));
                providers.add(bitmap(name + "_plate" + v, plateCells, layout.plateAscent(row), plates));
            }
            if (readout) {
                providers.add(bitmap(name + "_clock", clockCells, layout.clockAscent(row),
                        sheetRows(themes.clockGlyphs(), i -> layout.clockChar(r, i))));
            }
        }
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        files.put("assets/bluemapmini/font/" + name + ".json", GSON.toJson(font).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject spaceProvider() {
        JsonObject advances = new JsonObject();
        for (int power = 0; power <= FontLayout.MAX_SPACE_POWER; power++) {
            advances.addProperty(new String(Character.toChars(FontLayout.spaceChar(false, power))), 1 << power);
            advances.addProperty(new String(Character.toChars(FontLayout.spaceChar(true, power))), -(1 << power));
        }
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "space");
        provider.add("advances", advances);
        return provider;
    }

    private static JsonObject bitmap(String file, int height, int ascent, String[] chars) {
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", "bluemapmini:font/" + file + ".png");
        provider.addProperty("height", height);
        provider.addProperty("ascent", ascent);
        JsonArray rows = new JsonArray();
        for (String row : chars) rows.add(row);
        provider.add("chars", rows);
        return provider;
    }

    private interface CharAt { int at(int index); }

    /** Lays characters out in the same grid as the sheets, with unused cells mapped to NUL. */
    private static String[] sheetRows(int count, CharAt chars) {
        int rows = (count + SHEET_COLUMNS - 1) / SHEET_COLUMNS;
        String[] result = new String[rows];
        for (int row = 0; row < rows; row++) {
            StringBuilder builder = new StringBuilder();
            for (int column = 0; column < SHEET_COLUMNS; column++) {
                int index = row * SHEET_COLUMNS + column;
                builder.appendCodePoint(index < count ? chars.at(index) : 0);
            }
            result[row] = builder.toString();
        }
        return result;
    }

    /** One column of cells. Row {@code i} lights its top {@code 2^i} pixels across, leaving padding below. */
    private static BufferedImage runSheet(int cellHeight) {
        BufferedImage image = new BufferedImage(RUN_CELL_WIDTH, cellHeight * FontLayout.RUN_LENGTHS, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < FontLayout.RUN_LENGTHS; i++) {
            for (int x = 0; x < 1 << i; x++) image.setRGB(x, i * cellHeight, 0xFFFFFFFF);
        }
        return image;
    }

    private static BufferedImage iconSheet(IconSet icons, int cellHeight) {
        int rows = (HudIcon.COUNT + SHEET_COLUMNS - 1) / SHEET_COLUMNS;
        BufferedImage image = new BufferedImage(HudIcon.SIZE * SHEET_COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_ARGB);
        for (int icon = 0; icon < HudIcon.COUNT; icon++) {
            int originX = icon % SHEET_COLUMNS * HudIcon.SIZE;
            int originY = icon / SHEET_COLUMNS * cellHeight;
            BufferedImage art = icons.image(icon);
            for (int y = 0; y < HudIcon.SIZE; y++) {
                for (int x = 0; x < HudIcon.SIZE; x++) image.setRGB(originX + x, originY + y, art.getRGB(x, y));
            }
        }
        return image;
    }

    private static BufferedImage textSheet(int cellHeight) {
        int count = TinyFont.glyphCount();
        int rows = (count + SHEET_COLUMNS - 1) / SHEET_COLUMNS;
        BufferedImage image = new BufferedImage(TinyFont.WIDTH * SHEET_COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_ARGB);
        for (int glyph = 0; glyph < count; glyph++) {
            int originX = glyph % SHEET_COLUMNS * TinyFont.WIDTH;
            int originY = glyph / SHEET_COLUMNS * cellHeight;
            for (int y = 0; y < TinyFont.HEIGHT; y++) {
                for (int x = 0; x < TinyFont.WIDTH; x++) {
                    if (TinyFont.pixel(glyph, x, y)) image.setRGB(originX + x, originY + y, 0xFFFFFFFF);
                }
            }
        }
        return image;
    }

    private static BufferedImage readoutSheet(int cellHeight) {
        int count = ReadoutFont.glyphCount();
        int rows = (count + SHEET_COLUMNS - 1) / SHEET_COLUMNS;
        BufferedImage image = new BufferedImage(ReadoutFont.WIDTH * SHEET_COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_ARGB);
        for (int glyph = 0; glyph < count; glyph++) {
            int originX = glyph % SHEET_COLUMNS * ReadoutFont.WIDTH;
            int originY = glyph / SHEET_COLUMNS * cellHeight;
            for (int y = 0; y < ReadoutFont.HEIGHT; y++) {
                for (int x = 0; x < ReadoutFont.WIDTH; x++) {
                    if (ReadoutFont.pixel(glyph, x, y)) image.setRGB(originX + x, originY + y, 0xFFFFFFFF);
                }
            }
            int marker = ReadoutFont.widthMarkerRow(glyph);
            if (marker >= 0) image.setRGB(originX + ReadoutFont.WIDTH - 1, originY + marker, WIDTH_MARKER);
        }
        return image;
    }

    /** Every theme's clock frames in order, in their own colours, each measured at the full width. */
    private static BufferedImage clockSheet(ThemeSet themes, int cellHeight) {
        int count = Math.max(1, themes.clockGlyphs());
        int size = Theme.CLOCK_SIZE;
        int rows = (count + SHEET_COLUMNS - 1) / SHEET_COLUMNS;
        BufferedImage image = new BufferedImage(size * SHEET_COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_ARGB);
        int glyph = 0;
        for (Theme theme : themes.themes()) {
            for (int[] frame : theme.clockFrames()) {
                int originX = glyph % SHEET_COLUMNS * size, originY = glyph / SHEET_COLUMNS * cellHeight;
                boolean lastColumnLit = false;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int argb = frame[y * size + x];
                        if (argb >>> 24 == 0) continue;
                        image.setRGB(originX + x, originY + y, argb);
                        if (x == size - 1) lastColumnLit = true;
                    }
                }
                if (!lastColumnLit) image.setRGB(originX + size - 1, originY + size - 1, WIDTH_MARKER);
                glyph++;
            }
        }
        return image;
    }

    /** One column of cells. Row {@code i} is a plate {@code 2^i} pixels wide, with padding below. */
    private static BufferedImage plateSheet(int cellHeight, int opacity) {
        int pixel = opacity << 24 | 0xFFFFFF;
        BufferedImage image = new BufferedImage(1 << (FontLayout.PLATE_LENGTHS - 1), cellHeight * FontLayout.PLATE_LENGTHS, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < FontLayout.PLATE_LENGTHS; i++) {
            for (int y = 0; y < FontLayout.PLATE_HEIGHT; y++) {
                for (int x = 0; x < 1 << i; x++) image.setRGB(x, i * cellHeight + y, pixel);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Fixed timestamps keep the zip, and so its hash, the same when nothing has changed. */
    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(FIXED_ZIP_TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
