package com.extantape.bluemapmini.render;

/**
 * The 4x7 pixel font for the coordinate readout. It only has the characters coordinates need, and it is
 * drawn into the pack so its exact advances are known, like {@link TinyFont}.
 */
public final class ReadoutFont {
    public static final int WIDTH = 4;
    public static final int HEIGHT = 7;
    public static final int SPACE_ADVANCE = 4;

    private static final String CHARS = "0123456789-NESW:";
    /** Digits for the clock, after {@link #CHARS}. They all advance the full width, so the time never shifts. */
    private static final int TABULAR = 16;
    private static final String[] ROWS = {
            "0110 1001 1001 1001 1001 1001 0110",
            "0010 0110 0010 0010 0010 0010 0111",
            "0110 1001 0001 0010 0100 1000 1111",
            "0110 1001 0001 0110 0001 1001 0110",
            "1001 1001 1001 1111 0001 0001 0001",
            "1111 1000 1110 0001 0001 1001 0110",
            "0110 1000 1000 1110 1001 1001 0110",
            "1111 0001 0001 0010 0010 0100 0100",
            "0110 1001 1001 0110 1001 1001 0110",
            "0110 1001 1001 0111 0001 0001 0110",
            "0000 0000 0000 1111 0000 0000 0000",
            "1001 1101 1101 1011 1011 1001 1001",
            "1111 1000 1000 1110 1000 1000 1111",
            "0111 1000 1000 0110 0001 0001 1110",
            "1001 1001 1001 1001 1111 1111 1001",
            "0000 0100 0100 0000 0100 0100 0000",
    };

    private ReadoutFont() {}

    public static int glyphCount() {
        return CHARS.length() + 10;
    }

    /** The fixed-width version of a digit, for the clock. */
    public static int tabular(char digit) {
        if (digit < '0' || digit > '9') throw new IllegalArgumentException("Not a digit: " + digit);
        return TABULAR + digit - '0';
    }

    public static boolean isTabular(int glyph) {
        return glyph >= TABULAR;
    }

    /**
     * Where a tabular digit gets a nearly invisible pixel, so the client measures it at the full width: the
     * first unlit row of the last column, or -1 if the digit already reaches it. The text shader drops
     * anything under 10% alpha, so the pixel is never drawn.
     */
    public static int widthMarkerRow(int glyph) {
        if (!isTabular(glyph)) return -1;
        for (int y = 0; y < HEIGHT; y++) {
            if (!pixel(glyph, WIDTH - 1, y)) return y;
        }
        return -1;
    }

    public static int index(char c) {
        int index = CHARS.indexOf(c);
        if (index < 0) throw new IllegalArgumentException("Not in the readout font: " + c);
        return index;
    }

    public static boolean pixel(int glyph, int x, int y) {
        int source = isTabular(glyph) ? glyph - TABULAR : glyph;
        return ROWS[source].charAt(y * (WIDTH + 1) + x) == '1';
    }

    /** The advance the client computes: the rightmost lit column plus the font's one pixel of spacing. */
    public static int advance(int glyph) {
        if (isTabular(glyph)) return WIDTH + 1;
        for (int x = WIDTH - 1; x >= 0; x--) {
            for (int y = 0; y < HEIGHT; y++) {
                if (pixel(glyph, x, y)) return x + 2;
            }
        }
        return 1;
    }

    /** The width of a time drawn with {@link #tabular} digits, such as "18:30". */
    public static int timeWidth(String time) {
        int width = 0;
        for (int i = 0; i < time.length(); i++) {
            char c = time.charAt(i);
            width += c >= '0' && c <= '9' ? advance(tabular(c)) : advance(index(c));
        }
        return width;
    }

    public static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) == ' ' ? SPACE_ADVANCE : advance(index(text.charAt(i)));
        }
        return width;
    }
}
