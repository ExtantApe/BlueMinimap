package com.extantape.bluemapmini.render;

/**
 * A 3x5 pixel font drawn into the pack. The HUD must know every glyph's exact advance to keep the map
 * aligned, which it can't for vanilla text because other resource packs may restyle that.
 */
public final class TinyFont {
    public static final int WIDTH = 3;
    public static final int HEIGHT = 5;
    public static final int SPACE_ADVANCE = 4;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.,:/+'()_?";
    private static final int[] BITS = {
            0b010101111101101, 0b110101110101110, 0b011100100100011, 0b110101101101110, 0b111100110100111,
            0b111100110100100, 0b011100101101011, 0b101101111101101, 0b111010010010111, 0b001001001101010,
            0b101101110101101, 0b100100100100111, 0b101111111101101, 0b110101101101101, 0b010101101101010,
            0b110101110100100, 0b010101101110011, 0b110101110101101, 0b011100010001110, 0b111010010010010,
            0b101101101101111, 0b101101101101010, 0b101101111111101, 0b101101010101101, 0b101101010010010,
            0b111001010100111,
            0b111101101101111, 0b010110010010111, 0b110001010100111, 0b110001010001110, 0b101101111001001,
            0b111100110001110, 0b011100111101111, 0b111001010010010, 0b111101111101111, 0b111101111001110,
            0b000000111000000, 0b000000000000010, 0b000000000010100, 0b000010000010000, 0b001001010100100,
            0b000010111010000, 0b010010000000000, 0b001010010010001, 0b100010010010100, 0b000000000000111,
            0b110001010000010,
    };

    private TinyFont() {}

    public static int glyphCount() {
        return CHARS.length();
    }

    /** The index of a character, with lower case folded to upper and unknowns shown as a question mark. */
    public static int index(char c) {
        int index = CHARS.indexOf(Character.toUpperCase(c));
        return index >= 0 ? index : CHARS.indexOf('?');
    }

    public static boolean pixel(int glyph, int x, int y) {
        int bit = (HEIGHT - 1 - y) * WIDTH + (WIDTH - 1 - x);
        return (BITS[glyph] >> bit & 1) != 0;
    }

    /** The advance the client computes: the rightmost lit column plus the font's one pixel of spacing. */
    public static int advance(int glyph) {
        for (int x = WIDTH - 1; x >= 0; x--) {
            for (int y = 0; y < HEIGHT; y++) {
                if (pixel(glyph, x, y)) return x + 2;
            }
        }
        return 1;
    }

    public static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) == ' ' ? SPACE_ADVANCE : advance(index(text.charAt(i)));
        }
        return width;
    }
}
