package com.extantape.bluemapmini.tiles;

import java.io.IOException;

/** Reads BlueMap low-res tiles and map settings. Implementations run on background threads. */
public interface TileSource {

    /** The tile's PNG bytes, or null if BlueMap has not rendered it yet. */
    byte[] readTile(String mapId, int lod, int tileX, int tileZ) throws IOException;

    /** A value that changes when the tile changes, or -1 if that can't be known cheaply. */
    long version(String mapId, int lod, int tileX, int tileZ) throws IOException;

    /** The map's settings.json text, or null if it isn't available. */
    String readSettings(String mapId) throws IOException;

    /**
     * The path of a tile below the map folder, matching BlueMap's FileGridStorage: the encoded
     * position "x-12z3" is split after every digit, so it becomes "x-1/2/z3.png".
     */
    static String tilePath(int lod, int tileX, int tileZ) {
        String encoded = "x" + tileX + "z" + tileZ;
        StringBuilder path = new StringBuilder("tiles/").append(lod).append('/');
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            path.append(c);
            if (c >= '0' && c <= '9' && i < encoded.length() - 1) path.append('/');
        }
        return path.append(".png").toString();
    }
}
