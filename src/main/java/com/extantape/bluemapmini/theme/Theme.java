package com.extantape.bluemapmini.theme;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One HUD theme: its colours, its plate, and the clock's frames. Each theme is one JSON file in
 * {@code themes/}; see the README for the format. Everything else, the pack included, is generated from it.
 *
 * @param clockFrames each frame is {@value #CLOCK_SIZE} x {@value #CLOCK_SIZE} ARGB pixels, row by row, and
 *                    the frames share the day evenly from 00:00
 */
public record Theme(String id, String name, Colors colors, int plateOpacity, List<int[]> clockFrames) {
    public static final int CLOCK_SIZE = 9;
    public static final int MAX_CLOCK_FRAMES = 24;
    public static final int MINUTES_PER_DAY = 1440;

    /** The colours a theme sets. {@code plate} is the colour behind text; the rest are text and icon colours. */
    public record Colors(int text, int facing, int time, int legend, int plate,
                         int self, int player, int poi, int death, int north) {}

    /** The clock frame for a time of day, in minutes after midnight. */
    public int clockFrame(int minuteOfDay) {
        return Math.floorMod(minuteOfDay, MINUTES_PER_DAY) * clockFrames.size() / MINUTES_PER_DAY;
    }

    /** Reads a theme file. Throws {@link IllegalArgumentException} with a reason a server owner can act on. */
    public static Theme parse(String id, String json) {
        if (!id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Theme file names must be lowercase letters, digits and hyphens");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getMessage());
        }
        String name = string(root, "name");
        JsonObject c = object(root, "colors");
        Colors colors = new Colors(color(c, "text"), color(c, "facing"), color(c, "time"), color(c, "legend"), color(c, "plate"),
                color(c, "self"), color(c, "player"), color(c, "poi"), color(c, "death"), color(c, "north"));
        int opacity = root.has("plate-opacity") ? root.get("plate-opacity").getAsInt() : 176;
        if (opacity < 26 || opacity > 255) throw new IllegalArgumentException("plate-opacity must be from 26 to 255");

        JsonObject clock = object(root, "clock");
        Map<Character, Integer> palette = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(clock, "palette").entrySet()) {
            if (entry.getKey().length() != 1 || entry.getKey().equals(".")) {
                throw new IllegalArgumentException("Palette keys must be single characters other than '.'");
            }
            palette.put(entry.getKey().charAt(0), 0xFF000000 | parseColor(entry.getValue().getAsString(), "clock.palette." + entry.getKey()));
        }
        JsonArray framesJson = clock.getAsJsonArray("frames");
        if (framesJson == null || framesJson.isEmpty() || framesJson.size() > MAX_CLOCK_FRAMES) {
            throw new IllegalArgumentException("clock.frames needs 1 to " + MAX_CLOCK_FRAMES + " frames");
        }
        List<int[]> frames = new ArrayList<>();
        for (int f = 0; f < framesJson.size(); f++) {
            JsonArray rows = framesJson.get(f).getAsJsonArray();
            if (rows.size() != CLOCK_SIZE) throw new IllegalArgumentException("Clock frame " + f + " needs " + CLOCK_SIZE + " rows");
            int[] pixels = new int[CLOCK_SIZE * CLOCK_SIZE];
            boolean lit = false;
            for (int y = 0; y < CLOCK_SIZE; y++) {
                String row = rows.get(y).getAsString();
                if (row.length() != CLOCK_SIZE) {
                    throw new IllegalArgumentException("Clock frame " + f + ", row " + y + " needs " + CLOCK_SIZE + " characters");
                }
                for (int x = 0; x < CLOCK_SIZE; x++) {
                    char ch = row.charAt(x);
                    if (ch == '.') continue;
                    Integer argb = palette.get(ch);
                    if (argb == null) throw new IllegalArgumentException("Clock frame " + f + " uses '" + ch + "', which isn't in the palette");
                    pixels[y * CLOCK_SIZE + x] = argb;
                    lit = true;
                }
            }
            if (!lit) throw new IllegalArgumentException("Clock frame " + f + " is empty");
            frames.add(pixels);
        }
        return new Theme(id, name, colors, opacity, List.copyOf(frames));
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key);
        return value.getAsString();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Missing " + key);
        return value.getAsJsonObject();
    }

    private static int color(JsonObject colors, String key) {
        return parseColor(string(colors, key), "colors." + key);
    }

    private static int parseColor(String text, String where) {
        if (!text.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException(where + " must look like #RRGGBB");
        return Integer.parseInt(text.substring(1), 16);
    }
}
