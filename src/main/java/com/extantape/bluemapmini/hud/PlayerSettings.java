package com.extantape.bluemapmini.hud;

import com.extantape.bluemapmini.config.HudConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** One player's HUD choices, saved in their PersistentDataContainer. */
public final class PlayerSettings {
    private static final NamespacedKey KEY = new NamespacedKey("bluemapmini", "settings");
    /** The theme is a name, so it survives themes being added or removed. */
    private static final NamespacedKey THEME_KEY = new NamespacedKey("bluemapmini", "theme");
    private static final int FORMAT = 3;
    /** Saved before the clock and shares existed. */
    private static final int FORMAT_WITHOUT_CLOCK = 2;
    /** Saved before coordinates-only mode existed. */
    private static final int FORMAT_WITHOUT_MODE = 1;

    public boolean enabled;
    public int zoom;
    public int fullscreenZoom;
    public int size;
    public int offsetX;
    public int offsetY;
    public boolean showCoords;
    public boolean showPlayers = true;
    public boolean showMarkers = true;
    /** Shows only the coordinate line, in place of the minimap. */
    public boolean coordsOnly;
    public boolean showClock = true;
    /** Whether coordinates other players share reach this player. */
    public boolean receiveShares = true;
    /** The player's own theme, or null for the server's default. */
    public String theme;

    public static PlayerSettings load(PersistentDataContainer data, HudConfig config) {
        PlayerSettings settings = new PlayerSettings();
        settings.enabled = config.hud().enabledByDefault();
        settings.zoom = config.hud().zoom();
        settings.fullscreenZoom = config.fullscreen().zoom();
        settings.size = config.hud().size();
        settings.offsetX = config.hud().offsetX();
        settings.offsetY = config.hud().offsetY();
        settings.showCoords = config.hud().coords();
        settings.coordsOnly = config.hud().coordsOnlyByDefault();

        int[] saved = data.get(KEY, PersistentDataType.INTEGER_ARRAY);
        boolean known = saved != null && (saved.length == 10 && saved[0] == FORMAT_WITHOUT_MODE
                || saved.length == 11 && saved[0] == FORMAT_WITHOUT_CLOCK
                || saved.length == 13 && saved[0] == FORMAT);
        if (known) {
            settings.enabled = saved[1] != 0;
            settings.zoom = HudConfig.nearestZoom(saved[2]);
            settings.fullscreenZoom = HudConfig.nearestZoom(saved[3]);
            settings.size = saved[4];
            settings.offsetX = saved[5];
            settings.offsetY = saved[6];
            settings.showCoords = saved[7] != 0;
            settings.showPlayers = saved[8] != 0;
            settings.showMarkers = saved[9] != 0;
            // Players saved before modes existed never chose one, so they get the server's default.
            if (saved.length >= 11) settings.coordsOnly = saved[10] != 0;
            // Likewise the clock: on, unless they hid it.
            if (saved.length >= 13) {
                settings.showClock = saved[11] != 0;
                settings.receiveShares = saved[12] != 0;
            }
        }
        settings.theme = data.get(THEME_KEY, PersistentDataType.STRING);
        return settings;
    }

    public void save(PersistentDataContainer data) {
        data.set(KEY, PersistentDataType.INTEGER_ARRAY, new int[] {
                FORMAT, enabled ? 1 : 0, zoom, fullscreenZoom, size, offsetX, offsetY,
                showCoords ? 1 : 0, showPlayers ? 1 : 0, showMarkers ? 1 : 0, coordsOnly ? 1 : 0, showClock ? 1 : 0,
                receiveShares ? 1 : 0
        });
        if (theme == null) data.remove(THEME_KEY);
        else data.set(THEME_KEY, PersistentDataType.STRING, theme);
    }
}
