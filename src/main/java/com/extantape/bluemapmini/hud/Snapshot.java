package com.extantape.bluemapmini.hud;

/**
 * Everything a frame needs, copied on the main thread so that background threads never touch Bukkit.
 *
 * @param otherPlayers flat x, z pairs of the players this viewer may see
 * @param death        the viewer's last death position on this map as {x, z}, or null
 * @param coordsOnly   draw only the coordinate line, which needs no map, so {@code mapId} may be empty
 * @param theme        the index of the viewer's theme in the theme set
 * @param clockMinutes the time to show, in minutes after midnight and already rounded to the clock's step,
 *                     or -1 for no clock
 */
public record Snapshot(
        String mapId, int blockX, int blockY, int blockZ, float yaw, boolean fullscreen,
        int zoom, int size, int offsetX, int offsetY,
        boolean showCoords, boolean showMarkers,
        int[] otherPlayers, int[] death, boolean pattern, boolean coordsOnly,
        int theme, int clockMinutes
) {
    /** The first theme and no clock. */
    public Snapshot(String mapId, int blockX, int blockY, int blockZ, float yaw, boolean fullscreen,
                    int zoom, int size, int offsetX, int offsetY, boolean showCoords, boolean showMarkers,
                    int[] otherPlayers, int[] death, boolean pattern, boolean coordsOnly) {
        this(mapId, blockX, blockY, blockZ, yaw, fullscreen, zoom, size, offsetX, offsetY, showCoords, showMarkers,
                otherPlayers, death, pattern, coordsOnly, 0, -1);
    }
}
