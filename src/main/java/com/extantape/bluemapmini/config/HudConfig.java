package com.extantape.bluemapmini.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An immutable copy of config.yml. A reload builds a new one. */
public record HudConfig(
        Tiles tiles, Hud hud, Commands commands, Fullscreen fullscreen,
        Players players, Markers markers, Pack pack, Share share
) {
    public static final int[] ZOOM_LEVELS = {1, 2, 4, 8, 16, 32, 64};
    /** In-game minutes between clock changes. See docs/decisions.md for why. */
    public static final int CLOCK_STEP_DEFAULT = 5;

    public enum Channel { BOSS_BAR, ACTION_BAR }

    /** Who a share can reach before other plugins filter it. */
    public enum ShareScope {
        /** Players in the sender's world, counting its _nether and _the_end as the same world. */
        WORLD_GROUP,
        /** Everyone online, for servers whose rules plugin narrows each share through the share event. */
        ALL_ONLINE
    }

    public record Share(boolean enabled, ShareScope candidates, int cooldownSeconds, boolean log, boolean requireMayor) {}

    public record Tiles(String source, String webRoot, String httpUrl, int refreshMinutes,
                        int cacheChunks, Map<String, String> mapOverrides) {}

    public record Hud(boolean enabledByDefault, Channel channel, int updateTicks, int size, int zoom,
                      int cellPixels, int offsetX, int offsetY, int maxOffsetY, int cornerX, boolean coords, boolean coordsOnlyByDefault,
                      int colorStep, int voidColor, boolean hideWhileFullscreen, boolean hideForOtherBossBar,
                      boolean hideInSpectator, boolean quickHideGesture, String theme, boolean clock, int clockStepMinutes) {}

    public record Commands(List<String> mapNames, List<String> hudNames) {}

    public record Fullscreen(boolean enabled, boolean sneakSwapToggle, int width, int height, int cellPixels,
                             int zoom, int timeoutSeconds, double brightness) {}

    public record Players(int radius, boolean hideVanished, boolean hideSpectators, boolean hideInvisible,
                          boolean hideBlueMapHidden, boolean edgeArrows) {}

    public record Markers(boolean enabled, List<String> include, List<String> exclude, boolean edgeArrows,
                          boolean showDeath) {}

    public record Pack(boolean send, boolean required, String kickMessage, String prompt, int port, String bind, String host,
                       String publicUrl, boolean verifyPublicUrl, boolean hideBossBar, String bossBarColor, boolean bedrockExempt,
                       boolean reSendOnRemoval) {}

    public static HudConfig from(FileConfiguration c) {
        Map<String, String> overrides = new HashMap<>();
        ConfigurationSection section = c.getConfigurationSection("tiles.map-overrides");
        if (section != null) {
            for (String world : section.getKeys(false)) overrides.put(world, section.getString(world));
        }
        Tiles tiles = new Tiles(
                c.getString("tiles.source", "file").toLowerCase(Locale.ROOT),
                c.getString("tiles.web-root", ""),
                stripSlash(c.getString("tiles.http-url", "http://127.0.0.1:8100")),
                Math.max(1, c.getInt("tiles.refresh-minutes", 10)),
                Math.max(16, c.getInt("tiles.cache-chunks", 512)),
                Map.copyOf(overrides));

        Hud hud = new Hud(
                c.getBoolean("hud.enabled-by-default", true),
                "action-bar".equalsIgnoreCase(c.getString("hud.channel")) ? Channel.ACTION_BAR : Channel.BOSS_BAR,
                Math.max(5, c.getInt("hud.update-ticks", 5)),
                sizeCells(c.getString("hud.size", "medium")),
                nearestZoom(c.getInt("hud.zoom", 2)),
                clamp(c.getInt("hud.cell-pixels", 2), 1, 8),
                c.getInt("hud.offset-x", 0),
                Math.max(0, c.getInt("hud.offset-y", 0)),
                clamp(c.getInt("hud.max-offset-y", 64), 0, 256),
                c.getInt("hud.corner-x", 170),
                c.getBoolean("hud.coords", true),
                "coords".equalsIgnoreCase(c.getString("hud.default-mode", "map")),
                clamp(c.getInt("hud.color-step", 8), 1, 64),
                parseColor(c.getString("hud.void-color", "#14161a"), 0x14161a),
                c.getBoolean("hud.auto-hide.while-fullscreen", true),
                c.getBoolean("hud.auto-hide.other-boss-bar", true),
                c.getBoolean("hud.auto-hide.spectator", true),
                c.getBoolean("hud.auto-hide.quick-hide-gesture", false),
                c.getString("hud.theme", "classic").toLowerCase(Locale.ROOT),
                c.getBoolean("hud.clock.enabled", true),
                clockStep(c.getInt("hud.clock.step-minutes", CLOCK_STEP_DEFAULT)));

        Commands commands = new Commands(
                nonEmpty(c.getStringList("commands.map-names"), List.of("map", "worldmap")),
                nonEmpty(c.getStringList("commands.hud-names"), List.of("hud", "minimap")));

        Fullscreen fullscreen = new Fullscreen(
                c.getBoolean("fullscreen.enabled", true),
                c.getBoolean("fullscreen.sneak-swap-toggle", true),
                clamp(c.getInt("fullscreen.width", 96), 16, 192),
                clamp(c.getInt("fullscreen.height", 54), 16, 108),
                clamp(c.getInt("fullscreen.cell-pixels", 3), 1, 8),
                nearestZoom(c.getInt("fullscreen.zoom", 8)),
                Math.max(0, c.getInt("fullscreen.timeout-seconds", 60)),
                Math.min(1.0, Math.max(0.0, c.getDouble("fullscreen.brightness", 0.75))));

        Players players = new Players(
                Math.max(16, c.getInt("players.radius", 512)),
                c.getBoolean("players.hide-vanished", true),
                c.getBoolean("players.hide-spectators", true),
                c.getBoolean("players.hide-invisible", true),
                c.getBoolean("players.hide-bluemap-hidden", true),
                c.getBoolean("players.edge-arrows", false));

        Markers markers = new Markers(
                c.getBoolean("markers.enabled", true),
                List.copyOf(c.getStringList("markers.include")),
                List.copyOf(c.getStringList("markers.exclude")),
                c.getBoolean("markers.edge-arrows", true),
                c.getBoolean("markers.show-death", true));

        Pack pack = new Pack(
                c.getBoolean("pack.send", true),
                c.getBoolean("pack.required", true),
                c.getString("pack.kick-message", "This server needs the BlueMapMini resource pack."),
                c.getString("pack.prompt", ""),
                c.getInt("pack.port", 8164),
                c.getString("pack.bind", "").trim(),
                c.getString("pack.host", ""),
                c.getString("pack.public-url", "").trim(),
                c.getBoolean("pack.verify-public-url", true),
                c.getBoolean("pack.hide-boss-bar", true),
                c.getString("pack.boss-bar-color", "pink").toLowerCase(Locale.ROOT),
                c.getBoolean("pack.bedrock-exempt", true),
                c.getBoolean("pack.re-send-on-removal", true));

        Share share = new Share(
                c.getBoolean("share.enabled", true),
                "all-online".equalsIgnoreCase(c.getString("share.candidates", "world-group")) ? ShareScope.ALL_ONLINE : ShareScope.WORLD_GROUP,
                clamp(c.getInt("share.cooldown-seconds", 30), 0, 3600),
                c.getBoolean("share.log", true),
                c.getBoolean("share.require-mayor", true));

        return new HudConfig(tiles, hud, commands, fullscreen, players, markers, pack, share);
    }

    public static int sizeCells(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "small" -> 24;
            case "large" -> 40;
            default -> 32;
        };
    }

    public static int nearestZoom(int value) {
        int best = ZOOM_LEVELS[0];
        for (int level : ZOOM_LEVELS) {
            if (Math.abs(level - value) < Math.abs(best - value)) best = level;
        }
        return best;
    }

    /** Steps through the zoom levels. A positive direction shows more of the world. */
    public static int stepZoom(int current, int direction) {
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            if (ZOOM_LEVELS[i] == current) {
                return ZOOM_LEVELS[clamp(i + direction, 0, ZOOM_LEVELS.length - 1)];
            }
        }
        return nearestZoom(current);
    }

    private static int parseColor(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> nonEmpty(List<String> values, List<String> fallback) {
        return values.isEmpty() ? fallback : List.copyOf(values);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A step that divides the hour evenly, so the clock always shows round times such as 18:30. */
    static int clockStep(int minutes) {
        for (int step : new int[] {1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60}) {
            if (step >= minutes) return step;
        }
        return 60;
    }
}
