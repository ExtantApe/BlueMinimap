package com.extantape.bluemapmini.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes one frame as a single text component. Every position is relative to the centre of the screen:
 * the client centres text using its total advance, and each frame ends back at its start, so the total
 * is zero and the origin lands exactly on centre whatever the screen width.
 */
public final class MapFrame {
    private final FontLayout layout;
    private final int width;
    private final int height;
    private final int offsetRows;
    private final int left;
    private final int[] iconAdvances;
    private final List<Component> parts = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder(256);
    private int pendingColor = -1;
    private int cursor;
    private long estimatedBytes;

    /** Bytes one colour span adds to the packet's NBT, apart from its text. */
    private static final int SPAN_OVERHEAD = 30;

    public MapFrame(FontLayout layout, int[] iconAdvances, int width, int height, int offsetX, int offsetRows) {
        this.iconAdvances = iconAdvances;
        this.layout = layout;
        this.width = width;
        this.height = height;
        this.offsetRows = offsetRows;
        this.left = offsetX - width * layout.step() / 2;
    }

    /** Writes the grid as rows of coloured runs. Grid values are 0xRRGGBB. */
    public void drawGrid(int[] grid) {
        int cell = layout.step();
        for (int z = 0; z < height; z++) {
            int row = layout.rowFor(z, height, offsetRows);
            moveTo(left);
            int x = 0;
            while (x < width) {
                int color = grid[z * width + x];
                int run = 1;
                while (x + run < width && grid[z * width + x + run] == color) run++;
                setColor(color);
                for (int remaining = run; remaining > 0; ) {
                    int index = FontLayout.RUN_LENGTHS - 1;
                    while ((1 << index) > remaining) index--;
                    pending.appendCodePoint(layout.runChar(row, index));
                    // Closes the one pixel of spacing the client adds after every bitmap glyph.
                    pending.appendCodePoint(FontLayout.spaceChar(true, 0));
                    remaining -= 1 << index;
                }
                cursor += run * cell;
                x += run;
            }
        }
    }

    /** Draws an icon centred on a grid cell. */
    public void drawIcon(int icon, int cellX, int cellZ, int color) {
        int row = layout.rowFor(cellZ, height, offsetRows);
        int cell = layout.step();
        moveTo(left + cellX * cell + cell / 2 - HudIcon.SIZE / 2);
        setColor(color);
        pending.appendCodePoint(layout.iconChar(row, icon));
        cursor += iconAdvances[icon];
    }

    /** Draws text with its top-left corner at a grid position. Rows may run past the bottom of the grid. */
    public void drawText(String text, int pixelX, int gridRow, int color) {
        int row = Math.min(layout.rows() - 1, layout.rowFor(gridRow, height, offsetRows));
        moveTo(left + pixelX);
        setColor(color);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                appendMove(TinyFont.SPACE_ADVANCE);
            } else {
                int glyph = TinyFont.index(c);
                pending.appendCodePoint(layout.textChar(row, glyph));
                cursor += TinyFont.advance(glyph);
            }
        }
    }

    /**
     * Draws a dark translucent plate, so that text stays readable over bright worlds. Call it before the
     * text it sits behind. The width is rounded to a whole number of plate glyphs.
     */
    public void drawPlate(int pixelX, int width, int gridRow) {
        drawPlate(pixelX, width, gridRow, 0, 0x000000);
    }

    /** Draws a plate in one of the themes' opacities ({@code variant}) and colours. */
    public void drawPlate(int pixelX, int width, int gridRow, int variant, int color) {
        int row = Math.min(layout.rows() - 1, layout.rowFor(gridRow, height, offsetRows));
        moveTo(left + pixelX);
        setColor(color);
        for (int remaining = width; remaining > 0; ) {
            int index = FontLayout.PLATE_LENGTHS - 1;
            while ((1 << index) > remaining) index--;
            pending.appendCodePoint(layout.plateChar(row, variant, index));
            pending.appendCodePoint(FontLayout.spaceChar(true, 0));
            remaining -= 1 << index;
            cursor += 1 << index;
        }
    }

    /** Draws readout text, and returns the pixel after it. Only characters in {@link ReadoutFont} are allowed. */
    public int drawReadout(String text, int pixelX, int gridRow, int color) {
        int row = Math.min(layout.rows() - 1, layout.rowFor(gridRow, height, offsetRows));
        moveTo(left + pixelX);
        setColor(color);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                appendMove(ReadoutFont.SPACE_ADVANCE);
            } else {
                int glyph = ReadoutFont.index(c);
                pending.appendCodePoint(layout.readoutChar(row, glyph));
                cursor += ReadoutFont.advance(glyph);
            }
        }
        return cursor - left;
    }

    /** Every clock glyph advances this far: the picture plus the client's pixel of spacing. */
    public static final int CLOCK_ADVANCE = 10;

    /**
     * Draws a clock frame with its own colours, top-left one pixel above the readout line. Returns the pixel
     * after it.
     */
    public int drawClock(int clockGlyph, int pixelX, int gridRow) {
        int row = Math.min(layout.rows() - 1, layout.rowFor(gridRow, height, offsetRows));
        moveTo(left + pixelX);
        setColor(0xFFFFFF); // White leaves the picture's own colours as they are.
        pending.appendCodePoint(layout.clockChar(row, clockGlyph));
        cursor += CLOCK_ADVANCE;
        return cursor - left;
    }

    /** Draws a time such as "18:30" in fixed-width digits, so it never shifts as it changes. Returns the pixel after it. */
    public int drawTime(String time, int pixelX, int gridRow, int color) {
        int row = Math.min(layout.rows() - 1, layout.rowFor(gridRow, height, offsetRows));
        moveTo(left + pixelX);
        setColor(color);
        for (int i = 0; i < time.length(); i++) {
            char c = time.charAt(i);
            int glyph = c >= '0' && c <= '9' ? ReadoutFont.tabular(c) : ReadoutFont.index(c);
            pending.appendCodePoint(layout.readoutChar(row, glyph));
            cursor += ReadoutFont.advance(glyph);
        }
        return cursor - left;
    }

    /** Finishes the frame back at the start so its total advance is zero. */
    public Component finish() {
        moveTo(0);
        flush();
        Style root = Style.style().font(layout.font()).shadowColor(ShadowColor.none()).build();
        TextComponent.Builder builder = Component.text().style(root);
        builder.append(parts);
        return builder.build();
    }

    public long estimatedBytes() {
        return estimatedBytes;
    }

    private void moveTo(int x) {
        appendMove(x - cursor);
    }

    private void appendMove(int pixels) {
        if (pixels == 0) return;
        boolean negative = pixels < 0;
        int remaining = Math.abs(pixels);
        cursor += pixels;
        for (int power = FontLayout.MAX_SPACE_POWER; power >= 0; power--) {
            while (remaining >= 1 << power) {
                pending.appendCodePoint(FontLayout.spaceChar(negative, power));
                remaining -= 1 << power;
            }
        }
    }

    private void setColor(int color) {
        if (color == pendingColor) return;
        flush();
        pendingColor = color;
    }

    private void flush() {
        if (pending.isEmpty()) return;
        String text = pending.toString();
        parts.add(pendingColor < 0 ? Component.text(text) : Component.text(text, TextColor.color(pendingColor)));
        estimatedBytes += SPAN_OVERHEAD + text.length() * 3L;
        pending.setLength(0);
    }
}
