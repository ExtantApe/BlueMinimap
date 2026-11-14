package com.extantape.blueminimap;

import com.extantape.blueminimap.command.HudCommands;
import com.extantape.blueminimap.config.HudConfig;
import com.extantape.blueminimap.hud.FrameBuilder;
import com.extantape.blueminimap.hud.HudManager;
import com.extantape.blueminimap.hud.HudSession;
import com.extantape.blueminimap.markers.MarkerService;
import com.extantape.blueminimap.pack.PackBuilder;
import com.extantape.blueminimap.pack.PackService;
import com.extantape.blueminimap.render.HudFonts;
import com.extantape.blueminimap.render.IconSet;
import com.extantape.blueminimap.render.MapComposer;
import com.extantape.blueminimap.share.ShareMenu;
import com.extantape.blueminimap.share.ShareService;
import com.extantape.blueminimap.theme.Theme;
import com.extantape.blueminimap.theme.ThemeSet;
import com.extantape.blueminimap.tiles.FileTileSource;
import com.extantape.blueminimap.tiles.GridCache;
import com.extantape.blueminimap.tiles.HttpTileSource;
import com.extantape.blueminimap.tiles.TileSource;
import com.extantape.blueminimap.tiles.TileStore;
import de.bluecolored.bluemap.api.BlueMapAPI;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BlueMapMiniPlugin extends JavaPlugin implements Listener {
    /** Runs after other plugins, so the command name chains can see what they took. */
    private static final int COMMANDS_PRIORITY = 1000;
    private static final long PACK_SEND_DELAY_TICKS = 20;
    private static final int LOOK_DOWN_PITCH = 80;

    private HudConfig config;
    private PackService packs;
    private HudManager hud;
    private GridCache grid;
    private HudFonts fonts;
    private IconSet icons;
    private ShareService shares;
    private ShareMenu shareMenu;
    private final Consumer<BlueMapAPI> onBlueMapEnable = api -> Bukkit.getScheduler().runTask(this, () -> startRendering(api));
    private final Consumer<BlueMapAPI> onBlueMapDisable = api -> Bukkit.getScheduler().runTask(this, this::stopRendering);

    @Override
    public void onEnable() {
        Perms.logTo(line -> getLogger().warning(line));
        java.nio.file.Path folder = getDataFolder().toPath();
        // Renamed from BlueMinimap (D194, 0.3.0), and before that from BlueMapHUD: bring the newest old folder across once.
        String moved = Migration.copyIfMissing(folder.resolveSibling("BlueMinimap"), folder);
        if (moved == null) moved = Migration.copyIfMissing(folder.resolveSibling("BlueMapHUD"), folder);
        if (moved != null) {
            getLogger().warning(moved);
            try { // The old pack files would only confuse whoever hosts the pack; the new one is built below.
                java.nio.file.Files.deleteIfExists(folder.resolve("pack/blueminimap-pack.zip"));
                java.nio.file.Files.deleteIfExists(folder.resolve("pack/bluemaphud-pack.zip"));
            } catch (java.io.IOException e) {
                getLogger().warning("Couldn't remove the copied old pack file: " + e.getMessage());
            }
        }
        saveDefaultConfig();
        config = HudConfig.from(getConfig());
        packs = new PackService(this, getDataFolder().toPath().resolve("pack"));
        hud = new HudManager(this, packs);
        shares = new ShareService(this);
        shareMenu = new ShareMenu(this, shares);
        rebuildPack();
        stopRendering();

        getServer().getPluginManager().registerEvents(this, this);
        HudCommands commands = new HudCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event ->
                commands.register(event.registrar(), config)).priority(COMMANDS_PRIORITY));

        BlueMapAPI.onEnable(onBlueMapEnable);
        BlueMapAPI.onDisable(onBlueMapDisable);

        // Covers /reload and plugin managers: players already online need a session and the pack.
        for (Player player : Bukkit.getOnlinePlayers()) setUpPlayer(player);
    }

    @Override
    public void onDisable() {
        BlueMapAPI.unregisterListener(onBlueMapEnable);
        BlueMapAPI.unregisterListener(onBlueMapDisable);
        if (hud != null) hud.close();
        if (grid != null) grid.close();
        if (packs != null) packs.close();
    }

    public HudConfig config() { return config; }
    public HudManager hud() { return hud; }
    public ShareService shares() { return shares; }
    public ShareMenu shareMenu() { return shareMenu; }
    public PackService packs() { return packs; }

    /** Re-reads config.yml. Players get the pack again only if its contents changed. */
    public void reload() {
        reloadConfig();
        config = HudConfig.from(getConfig());
        boolean changed = rebuildPack();
        stopRendering();
        BlueMapAPI.getInstance().ifPresent(this::startRendering);
        if (changed) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!hud.isBedrock(player)) packs.send(player);
            }
        }
    }

    /** The themes in the current pack. */
    public ThemeSet themes() {
        return fonts.themes;
    }

        public String debugLine() {
        String tiles = grid == null ? "BlueMap unavailable" : grid.cachedChunks() + " chunks and " + grid.cachedTiles() + " tiles cached";
        return tiles + "; " + hud.debugLine() + "; pack " + packs.sha1() + ", public-url " + packs.publicUrlStatus();
    }

    public void toggleFullscreen(Player player) {
        if (!config.fullscreen().enabled()) {
            player.sendMessage(Component.text("The fullscreen map is turned off on this server.", NamedTextColor.RED));
            return;
        }
        HudSession session = hud.session(player);
        boolean open = !session.fullscreen();
        hud.setFullscreen(player, session, open);
        if (open) explainIfNoMap(player);
    }

    /** Says why nothing appeared, because otherwise a missing map looks like a broken command. */
    public void explainIfNoMap(Player player) {
        if (hud.mapFor(player.getWorld()).isEmpty()) {
            player.sendMessage(Component.text(BlueMapAPI.getInstance().isPresent()
                    ? "BlueMap has no map for this world, so there's nothing to show here."
                    : "BlueMap isn't ready yet, so there's no map to show.", NamedTextColor.YELLOW));
        } else {
            explainIfNoPack(player);
        }
    }

    /** Coordinates need the pack but not a map, so this is all that can stop them appearing. */
    public void explainIfNoPack(Player player) {
        if (!packs.ready(player)) {
            player.sendMessage(Component.text("Your client hasn't loaded the BlueMapMini resource pack yet.", NamedTextColor.YELLOW));
        }
    }

    /** Writes the player's view to a PNG, to compare with BlueMap's top-down view. */
    public void dumpView(Player player) {
        String mapId = hud.mapFor(player.getWorld());
        GridCache cache = grid;
        if (mapId.isEmpty() || cache == null) {
            player.sendMessage(Component.text("No BlueMap map for this world.", NamedTextColor.RED));
            return;
        }
        int bpc = hud.session(player).settings().zoom;
        int blockX = player.getLocation().getBlockX(), blockZ = player.getLocation().getBlockZ();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File file = new File(getDataFolder(), "debug-" + mapId + ".png");
            try {
                ImageIO.write(renderDump(cache, mapId, bpc, blockX, blockZ), "png", file);
                // A slow dump can finish after the plugin is disabled, and nothing can be scheduled then.
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> player.sendMessage(Component.text("Wrote " + file.getPath(), NamedTextColor.GRAY)));
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not write debug image", e);
            }
        });
    }

    private BufferedImage renderDump(GridCache cache, String mapId, int bpc, int blockX, int blockZ) {
        int side = 2 * GridCache.CHUNK_CELLS;
        int firstX = Math.floorDiv(blockX, bpc) - side / 2, firstZ = Math.floorDiv(blockZ, bpc) - side / 2;
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int cellX = firstX + x, cellZ = firstZ + z;
                GridCache.Chunk chunk = cache.loadNow(mapId, bpc, cellX >> GridCache.CHUNK_BITS, cellZ >> GridCache.CHUNK_BITS);
                int color = chunk == null ? GridCache.VOID : chunk.cell(cellX & GridCache.CHUNK_MASK, cellZ & GridCache.CHUNK_MASK);
                image.setRGB(x, z, color == GridCache.VOID ? 0 : 0xFF000000 | color);
            }
        }
        return image;
    }

    private boolean rebuildPack() {
        ThemeSet themes = ThemeSet.load(getDataFolder().toPath().resolve("themes"), config.hud().theme(), getLogger()::warning);
        fonts = new HudFonts(config, themes);
        getLogger().info("Themes: " + String.join(", ", themes.themes().stream().map(Theme::id).toList())
                + ". New players get " + themes.defaultTheme().id() + ".");
        Path iconFolder = getDataFolder().toPath().resolve("icons");
        try {
            Files.createDirectories(iconFolder);
            icons = new IconSet(iconFolder, getLogger());
            return packs.install(config.pack(), PackBuilder.build(config, fonts, icons));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not build or host the resource pack", e);
            return false;
        }
    }

    private void startRendering(BlueMapAPI api) {
        if (grid != null) grid.close();
        grid = new GridCache(new TileStore(tileSource(api)), config.tiles().cacheChunks(),
                config.tiles().refreshMinutes() * 60_000L, getLogger());
        MapComposer composer = new MapComposer(grid, config.hud().voidColor(), config.hud().colorStep());
        MarkerService markers = new MarkerService(config.markers());
        hud.configure(config, new FrameBuilder(config, fonts, icons.advances(), composer, markers), true);
        getLogger().info("Reading tiles from BlueMap " + api.getBlueMapVersion() + " using the " + config.tiles().source() + " source.");
    }

    /** Without BlueMap there are no tiles or markers, but the test pattern still works. */
    private void stopRendering() {
        hud.configure(config, new FrameBuilder(config, fonts, icons.advances(), null, new MarkerService(config.markers())), false);
        if (grid != null) {
            grid.close();
            grid = null;
        }
    }

    private TileSource tileSource(BlueMapAPI api) {
        if ("http".equals(config.tiles().source())) return new HttpTileSource(config.tiles().httpUrl());
        Path root = config.tiles().webRoot().isBlank()
                ? api.getWebApp().getWebRoot().resolve("maps")
                : Path.of(config.tiles().webRoot());
        return new FileTileSource(root);
    }

    private void setUpPlayer(Player player) {
        boolean bedrock = config.pack().bedrockExempt() && isBedrock(player);
        hud.join(player, bedrock);
        if (!bedrock) {
            // Sent after the server's own pack, so ours sits above it and its boss bar texture wins.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) packs.send(player);
            }, PACK_SEND_DELAY_TICKS);
        }
    }

    /** Floodgate is optional, so it is reached by reflection rather than a compile-time dependency. */
    private boolean isBedrock(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return false;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isFloodgatePlayer", java.util.UUID.class).invoke(instance, player.getUniqueId());
        } catch (ReflectiveOperationException e) {
            getLogger().log(Level.FINE, "Could not query Floodgate", e);
            return false;
        }
    }

    /** Once every plugin has started, says if all-online sharing has nothing to filter it. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (shares.allOnlineWithoutFilter()) {
            getLogger().warning("share.candidates is all-online, but no plugin filters shares, so they stay within each sender's world group.");
        }
    }

    /** A failed pack download gets another try when the player changes world. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (!hud.isBedrock(event.getPlayer())) packs.retryIfFailed(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        setUpPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hud.quit(event.getPlayer());
        packs.forget(event.getPlayer());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        packs.onStatus(event, hud.isBedrock(event.getPlayer()));
    }

    /** Sneak + F. The hand swap is cancelled only while sneaking, so normal F still works. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || hud.isBedrock(player)) return;
        HudSession session = hud.session(player);

        if (config.hud().quickHideGesture() && player.getLocation().getPitch() >= LOOK_DOWN_PITCH && com.extantape.blueminimap.Perms.has(player, "bluemapmini.use")) {
            event.setCancelled(true);
            session.settings().enabled = !session.settings().enabled;
            hud.saveSettings(player, session);
            return;
        }
        if (config.fullscreen().sneakSwapToggle() && config.fullscreen().enabled() && com.extantape.blueminimap.Perms.has(player, "bluemapmini.fullscreen")) {
            event.setCancelled(true);
            toggleFullscreen(player);
        }
    }
}
