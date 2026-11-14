package com.extantape.bluemapmini.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * The readability rule every theme must pass, using WCAG 2 contrast. Behind the plate can be anything, so
 * it's checked over ten backgrounds from white to black, including the brightest pure colours.
 *
 * <ul>
 *   <li>Text (numbers, facing, time and the fullscreen legend) needs 4.5:1 against the plate.</li>
 *   <li>The clock is a picture, so it follows WCAG's 3:1 for graphics: in every frame, at least three
 *       quarters of its pixels need 3:1 against the plate.</li>
 * </ul>
 */
public final class ThemeContrast {
    public static final int[] BACKGROUNDS = {0xFFFFFF, 0xECF0F5, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x00FF00, 0xFF0000, 0x0000FF, 0x808080, 0x000000};
    public static final double TEXT = 4.5;
    public static final double GRAPHIC = 3.0;
    public static final double GRAPHIC_SHARE = 0.75;

    private ThemeContrast() {}

    /** Everything that fails, as sentences. Empty means the theme passes. */
    public static List<String> problems(Theme theme) {
        List<String> problems = new ArrayList<>();
        Theme.Colors c = theme.colors();
        for (int background : BACKGROUNDS) {
            int plate = plateOver(theme, background);
            check(problems, "text", c.text(), plate, background);
            check(problems, "facing", c.facing(), plate, background);
            check(problems, "time", c.time(), plate, background);
            check(problems, "legend", c.legend(), plate, background);
            for (int f = 0; f < theme.clockFrames().size(); f++) {
                int[] pixels = theme.clockFrames().get(f);
                int lit = 0, clear = 0;
                for (int argb : pixels) {
                    if (argb >>> 24 == 0) continue;
                    lit++;
                    if (ratio(argb & 0xFFFFFF, plate) >= GRAPHIC) clear++;
                }
                if (clear < lit * GRAPHIC_SHARE) {
                    problems.add(String.format("Clock frame %d: only %d of %d pixels reach %.0f:1 on the plate over #%06X",
                            f, clear, lit, GRAPHIC, background));
                }
            }
        }
        return problems;
    }

    /** The lowest text contrast over all backgrounds, for reporting. */
    public static double lowestText(Theme theme) {
        double lowest = Double.MAX_VALUE;
        Theme.Colors c = theme.colors();
        for (int background : BACKGROUNDS) {
            int plate = plateOver(theme, background);
            for (int text : new int[] {c.text(), c.facing(), c.time(), c.legend()}) lowest = Math.min(lowest, ratio(text, plate));
        }
        return lowest;
    }

    /** The colour the plate shows over a background: the plate colour at the theme's opacity. */
    public static int plateOver(Theme theme, int background) {
        int alpha = theme.plateOpacity(), plate = theme.colors().plate(), result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int under = background >> shift & 255, over = plate >> shift & 255;
            result |= (under + (over - under) * alpha / 255) << shift;
        }
        return result;
    }

    public static double ratio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static void check(List<String> problems, String what, int color, int plate, int background) {
        double ratio = ratio(color, plate);
        if (ratio < TEXT) {
            problems.add(String.format("%s #%06X is only %.2f:1 on the plate over #%06X (needs %.1f:1)", what, color, ratio, background, TEXT));
        }
    }

    private static double luminance(int rgb) {
        double sum = 0;
        double[] weights = {0.0722, 0.7152, 0.2126};
        for (int i = 0; i < 3; i++) {
            double c = (rgb >> (i * 8) & 255) / 255.0;
            sum += weights[i] * (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        return sum;
    }
}
