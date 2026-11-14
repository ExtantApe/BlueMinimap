package com.extantape.bluemapmini.tiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A map's low-res tile layout, from the "lowres" block of its settings.json. */
public record LowresSettings(int tileSize, int lodFactor, int lodCount) {
    public static final LowresSettings DEFAULT = new LowresSettings(500, 5, 3);

    public static LowresSettings parse(String json) {
        if (json == null) return DEFAULT;
        try {
            JsonObject lowres = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("lowres");
            JsonArray size = lowres.getAsJsonArray("tileSize");
            return new LowresSettings(size.get(0).getAsInt(), lowres.get("lodFactor").getAsInt(),
                    lowres.get("lodCount").getAsInt());
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /** Blocks covered by one pixel at this level of detail. Level 1 is one block. */
    public int blocksPerPixel(int lod) {
        int blocks = 1;
        for (int i = 1; i < lod; i++) blocks *= lodFactor;
        return blocks;
    }

    /** The most zoomed-out level that still has at least one pixel per cell. */
    public int lodFor(int blocksPerCell) {
        int lod = 1;
        while (lod < lodCount && blocksPerPixel(lod + 1) <= blocksPerCell) lod++;
        return lod;
    }
}
