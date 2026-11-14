package com.extantape.bluemapmini;

import com.extantape.bluemapmini.config.HudConfig;
import com.extantape.bluemapmini.hud.PlayerSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's default mode, the clock and theme choices, and settings saved by older versions. */
class PlayerSettingsTest {
    private static HudConfig config(String defaultMode) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                PlayerSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        if (defaultMode != null) yaml.set("hud.default-mode", defaultMode);
        return HudConfig.from(yaml);
    }

    /** A stand-in for a player's data container that only keeps what get and set need. */
    private static PersistentDataContainer container() {
        Map<Object, Object> values = new HashMap<>();
        return (PersistentDataContainer) Proxy.newProxyInstance(PlayerSettingsTest.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get(args[0]);
                    case "set" -> values.put(args[0], args[2]);
                    case "remove" -> values.remove(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void newPlayersGetTheConfiguredMode() {
        assertFalse(PlayerSettings.load(container(), config(null)).coordsOnly, "The shipped config defaults to the map");
        assertFalse(PlayerSettings.load(container(), config("map")).coordsOnly);
        assertTrue(PlayerSettings.load(container(), config("coords")).coordsOnly);
        assertTrue(PlayerSettings.load(container(), config("COORDS")).coordsOnly);
        assertFalse(PlayerSettings.load(container(), config("nonsense")).coordsOnly, "Anything else means the map");
    }

    @Test
    void aSavedChoiceBeatsTheDefault() {
        PersistentDataContainer data = container();
        PlayerSettings chosen = PlayerSettings.load(data, config("coords"));
        chosen.coordsOnly = false;
        chosen.zoom = 8;
        chosen.save(data);
        PlayerSettings loaded = PlayerSettings.load(data, config("coords"));
        assertFalse(loaded.coordsOnly, "The player picked the map");
        assertEquals(8, loaded.zoom);

        chosen.coordsOnly = true;
        chosen.save(data);
        assertTrue(PlayerSettings.load(data, config("map")).coordsOnly, "The player picked coordinates");
    }

    @Test
    void settingsSavedBeforeModesStillLoad() {
        PersistentDataContainer data = container();
        // The first format: format, enabled, zoom, fullscreen zoom, size, offset x, offset y, coords, players, markers.
        data.set(new org.bukkit.NamespacedKey("bluemapmini", "settings"),
                org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY, new int[] {1, 0, 16, 32, 24, 40, 8, 0, 1, 0});
        PlayerSettings old = PlayerSettings.load(data, config("map"));
        assertFalse(old.enabled);
        assertEquals(16, old.zoom);
        assertEquals(32, old.fullscreenZoom);
        assertEquals(24, old.size);
        assertEquals(40, old.offsetX);
        assertEquals(8, old.offsetY);
        assertFalse(old.showCoords);
        assertTrue(old.showPlayers);
        assertFalse(old.showMarkers);
        assertFalse(old.coordsOnly);
        assertTrue(PlayerSettings.load(data, config("coords")).coordsOnly, "They never chose a mode, so they get the default");
    }

    @Test
    void theClockAndThemeAreSaved() {
        PersistentDataContainer data = container();
        PlayerSettings fresh = PlayerSettings.load(data, config(null));
        assertTrue(fresh.showClock, "The clock shows by default");
        assertEquals(null, fresh.theme, "No theme chosen means the server's");
        fresh.showClock = false;
        fresh.theme = "desert";
        fresh.save(data);
        PlayerSettings loaded = PlayerSettings.load(data, config(null));
        assertFalse(loaded.showClock);
        assertEquals("desert", loaded.theme);
        loaded.theme = null;
        loaded.save(data);
        assertEquals(null, PlayerSettings.load(data, config(null)).theme, "Going back to the default forgets the choice");
    }

    @Test
    void settingsSavedBeforeTheClockStillLoad() {
        PersistentDataContainer data = container();
        // The second format added the mode at the end.
        data.set(new org.bukkit.NamespacedKey("bluemapmini", "settings"),
                org.bukkit.persistence.PersistentDataType.INTEGER_ARRAY, new int[] {2, 1, 4, 16, 40, 0, 0, 1, 1, 1, 1});
        PlayerSettings old = PlayerSettings.load(data, config("map"));
        assertTrue(old.coordsOnly);
        assertEquals(4, old.zoom);
        assertTrue(old.showClock, "They never hid the clock");
    }
}
