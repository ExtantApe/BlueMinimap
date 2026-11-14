package com.extantape.bluemapmini.render;

import java.awt.image.BufferedImage;

/** The icons on the map's icon sheet. Heading arrows come first, one per 22.5 degrees. */
public final class HudIcon {
    public static final int SIZE = 9;
    public static final int HEADINGS = 16;

    public static final int PLAYER = HEADINGS;
    public static final int POI = HEADINGS + 1;
    public static final int DEATH = HEADINGS + 2;
    public static final int NORTH = HEADINGS + 3;
    public static final int COUNT = HEADINGS + 4;

    private HudIcon() {}

    /** The heading arrow for a Minecraft yaw. Yaw 180 faces north, the top of the map. */
    public static int headingForYaw(float yaw) {
        double clockwiseFromNorth = yaw + 180.0;
        return Math.floorMod((int) Math.round(clockwiseFromNorth / 22.5), HEADINGS);
    }

    /** The arrow pointing along a map direction, where +z is down the screen. */
    public static int headingForVector(double dx, double dz) {
        double clockwiseFromNorth = Math.toDegrees(Math.atan2(dx, -dz));
        return Math.floorMod((int) Math.round(clockwiseFromNorth / 22.5), HEADINGS);
    }

    public static String fileName(int icon) {
        if (icon < HEADINGS) return "arrow_" + (icon < 10 ? "0" : "") + icon + ".png";
        return switch (icon) {
            case PLAYER -> "player.png";
            case POI -> "poi.png";
            case DEATH -> "death.png";
            default -> "north.png";
        };
    }

    /** Draws the built-in version of an icon. Server owners can replace any of them with a PNG. */
    public static BufferedImage draw(int icon) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        if (icon < HEADINGS) drawArrow(image, icon * 22.5);
        else if (icon == PLAYER) drawPattern(image, ".........", ".........", "...###...", "..#####..", "..#####..", "..#####..", "...###...", ".........", ".........");
        else if (icon == POI) drawPattern(image, "...###...", "..#####..", "..##.##..", "..#####..", "...###...", "...###...", "....#....", "....#....", ".........");
        else if (icon == DEATH) drawPattern(image, ".........", ".##...##.", "..##.##..", "...###...", "....#....", "...###...", "..##.##..", ".##...##.", ".........");
        else drawPattern(image, ".........", ".##...##.", ".###..##.", ".####.##.", ".##.####.", ".##..###.", ".##...##.", ".........", ".........");
        return image;
    }

    private static void drawArrow(BufferedImage image, double degrees) {
        double radians = Math.toRadians(degrees);
        double sin = Math.sin(radians), cos = Math.cos(radians);
        double center = (SIZE - 1) / 2.0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // Rotate back into the upright arrow's space, then test against its outline.
                double dx = x - center, dy = y - center;
                double ux = dx * cos + dy * sin;
                double uy = -dx * sin + dy * cos;
                boolean inHead = uy >= -4.4 && uy <= 3.2 && Math.abs(ux) <= (uy + 4.4) * 0.5;
                boolean inNotch = uy > 1.4 && Math.abs(ux) < (uy - 1.4) * 0.9;
                if (inHead && !inNotch) image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
    }

    private static void drawPattern(BufferedImage image, String... rows) {
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                if (rows[y].charAt(x) == '#') image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
    }
}
