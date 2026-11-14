package com.extantape.blueminimap.hud;

import com.extantape.blueminimap.config.HudConfig;
import com.extantape.blueminimap.pack.PackService;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The update loop. Each cycle snapshots players on the main thread, then background threads compare
 * state keys and build frames only when something changed. Finished frames return to the main thread.
 */
public final class HudManager {
    private static final int ACTION_BAR_RESEND_TICKS = 30;
    private static final int BOSS_SCAN_EVERY_CYCLES = 4;

    private final Plugin plugin;
    private final PackService packs;
    private final Map<UUID, HudSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> worldMaps = new HashMap<>();
    private final Map<UUID, Boolean> bedrock = new ConcurrentHashMap<>();
    private final ExecutorService builders = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BlueMapMini-build");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private final AtomicLong framesBuilt = new AtomicLong();
    private final AtomicLong buildNanos = new AtomicLong();
    private final AtomicLong frameBytes = new AtomicLong();

    private HudConfig config;
    private FrameBuilder frames;
    private volatile boolean rendering;
    private BukkitTask task;
    private int cycle;
    private final Map<UUID, Boolean> bossBarBlocked = new HashMap<>();

    public HudManager(Plugin plugin, PackService packs) {
        this.plugin = plugin;
        this.packs = packs;
    }

    /**
     * Starts or restarts the loop with new settings.
     *
     * @param rendering false while BlueMap is unavailable, when only the test pattern can be drawn
     */
    public void configure(HudConfig config, FrameBuilder frames, boolean rendering) {
        this.config = config;
        this.frames = frames;
        this.rendering = rendering;
        worldMaps.clear();
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, config.hud().updateTicks());
        for (HudSession session : sessions.values()) {
            session.lastKey = 0;
            session.bar.color(barColor()); // The configured colour may have changed.
        }
    }

    public void clearWorldMaps() {
        worldMaps.clear();
    }

    public void close() {
        if (task != null) task.cancel();
        builders.shutdownNow();
        for (Player player : Bukkit.getOnlinePlayers()) {
            HudSession session = sessions.remove(player.getUniqueId());
            if (session != null) hide(player, session);
        }
    }

    public HudSession join(Player player, boolean isBedrock) {
        bedrock.put(player.getUniqueId(), isBedrock);
        return sessions.computeIfAbsent(player.getUniqueId(), id -> newSession(player));
    }

    public void quit(Player player) {
        bedrock.remove(player.getUniqueId());
        bossBarBlocked.remove(player.getUniqueId());
        HudSession session = sessions.remove(player.getUniqueId());
        if (session != null) hide(player, session);
    }

    public HudSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> newSession(player));
    }

    public boolean isBedrock(Player player) {
        return bedrock.getOrDefault(player.getUniqueId(), false);
    }

    public void saveSettings(Player player, HudSession session) {
        session.settings.save(player.getPersistentDataContainer());
        session.lastKey = 0;
    }

    public void setFullscreen(Player player, HudSession session, boolean open) {
        session.fullscreen = open;
        session.fullscreenOpenedAt = System.currentTimeMillis();
        session.lastKey = 0;
    }

    public void togglePattern(HudSession session) {
        session.pattern = !session.pattern;
        session.lastKey = 0;
    }

    public String debugLine() {
        long built = framesBuilt.get();
        long averageMicros = built == 0 ? 0 : buildNanos.get() / built / 1000;
        long averageBytes = built == 0 ? 0 : frameBytes.get() / built;
        return "frames " + built + ", average build " + averageMicros + " us, about " + averageBytes
                + " bytes per update before compression, " + sessions.size() + " sessions";
    }

    /** The map a world's HUD draws: a configured override, or else its first map by ID. */
    public String mapFor(World world) {
        if (!rendering) return "";
        return worldMaps.computeIfAbsent(world.getName(), name -> {
            String override = config.tiles().mapOverrides().get(name);
            if (override != null) return override;
            BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
            if (api == null) return "";
            BlueMapWorld blueMapWorld = api.getWorld(world).orElse(null);
            if (blueMapWorld == null) return "";
            return blueMapWorld.getMaps().stream().map(BlueMapMap::getId).min(Comparator.naturalOrder()).orElse("");
        });
    }

    private HudSession newSession(Player player) {
        BossBar bar = BossBar.bossBar(Component.empty(), 0f, barColor(), BossBar.Overlay.PROGRESS);
        return new HudSession(player.getUniqueId(), bar, PlayerSettings.load(player.getPersistentDataContainer(), config));
    }

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(config.pack().bossBarColor().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.PINK;
        }
    }

    private void tick() {
        cycle++;
        FrameBuilder builder = frames;
        boolean scanBosses = cycle % BOSS_SCAN_EVERY_CYCLES == 0 && config.hud().hideForOtherBossBar();
        Map<World, List<Boss>> worldBosses = scanBosses ? new HashMap<>() : null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            HudSession session = session(player);
            if (scanBosses) bossBarBlocked.put(player.getUniqueId(), hasOtherBossBar(player, session, worldBosses));
            closeFullscreenIfExpired(session);

            String mapId = mapFor(player.getWorld());
            boolean hasMap = !mapId.isEmpty() || session.pattern;
            boolean canFull = hasMap && session.fullscreen && com.extantape.blueminimap.Perms.has(player, "bluemapmini.fullscreen") && config.fullscreen().enabled();
            if (!shouldShow(player, session, hasMap, canFull)) {
                hide(player, session);
                continue;
            }
            resendActionBar(player, session, canFull);
            if (session.building.compareAndSet(false, true)) {
                Snapshot snapshot = snapshot(player, session, mapId, canFull);
                submit(builder, session, snapshot);
            }
        }
    }

    private boolean shouldShow(Player player, HudSession session, boolean hasMap, boolean canFull) {
        boolean coordsOnly = session.settings.coordsOnly && !session.pattern;
        if (!hasMap && !coordsOnly) return false; // A dimension BlueMap doesn't map. Coordinates don't need one.
        if (isBedrock(player) || !packs.ready(player)) return false;
        if (!com.extantape.blueminimap.Perms.has(player, "bluemapmini.use")) return false;
        if (canFull) return true; // Fullscreen is an explicit request, and it works while the minimap is off.
        if (!session.settings.enabled) return false;
        if (session.fullscreen && config.hud().hideWhileFullscreen() && !coordsOnly) return false;
        if (config.hud().hideInSpectator() && player.getGameMode() == GameMode.SPECTATOR) return false;
        return !(config.hud().hideForOtherBossBar() && bossBarBlocked.getOrDefault(player.getUniqueId(), false));
    }

    private void closeFullscreenIfExpired(HudSession session) {
        int timeout = config.fullscreen().timeoutSeconds();
        if (session.fullscreen && timeout > 0 && System.currentTimeMillis() - session.fullscreenOpenedAt > timeout * 1000L) {
            session.fullscreen = false;
            session.lastKey = 0;
        }
    }

    private void resendActionBar(Player player, HudSession session, boolean full) {
        if (full || config.hud().channel() != HudConfig.Channel.ACTION_BAR || session.actionBarFrame == null) return;
        session.actionBarAge += config.hud().updateTicks();
        if (session.actionBarAge >= ACTION_BAR_RESEND_TICKS) {
            player.sendActionBar(session.actionBarFrame);
            session.actionBarAge = 0;
        }
    }

    private Snapshot snapshot(Player player, HudSession session, String mapId, boolean full) {
        PlayerSettings settings = session.settings;
        Location location = player.getLocation();
        int[] death = null;
        Location lastDeath = player.getLastDeathLocation();
        if (config.markers().showDeath() && lastDeath != null && lastDeath.getWorld() == player.getWorld()) {
            death = new int[] {lastDeath.getBlockX(), lastDeath.getBlockZ()};
        }
        return new Snapshot(mapId, location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw(), full,
                full ? settings.fullscreenZoom : settings.zoom, settings.size, settings.offsetX, settings.offsetY,
                settings.showCoords, settings.showMarkers, visiblePlayers(player, session, location), death, session.pattern,
                settings.coordsOnly && !full && !session.pattern, frames.themes().resolve(settings.theme),
                clockMinutes(player.getWorld(), settings));
    }

    /**
     * The time to show, rounded down to the clock's step, or -1 when the clock is off or time stands still,
     * as in the Nether and the End. Tick 0 is 06:00.
     */
    private int clockMinutes(World world, PlayerSettings settings) {
        if (!config.hud().clock() || !settings.showClock || world.isFixedTime()) return -1;
        long ticks = Math.floorMod(world.getTime() + 6000, 24000L);
        int minute = (int) (ticks * 1440 / 24000);
        int step = config.hud().clockStepMinutes();
        return minute / step * step;
    }

    private int[] visiblePlayers(Player viewer, HudSession session, Location at) {
        if (!session.settings.showPlayers || !com.extantape.blueminimap.Perms.has(viewer, "bluemapmini.seeplayers")) return new int[0];
        HudConfig.Players rules = config.players();
        BlueMapAPI api = rules.hideBlueMapHidden() ? BlueMapAPI.getInstance().orElse(null) : null;
        double radiusSquared = (double) rules.radius() * rules.radius();
        List<Player> others = viewer.getWorld().getPlayers();
        int[] positions = new int[others.size() * 2];
        int count = 0;
        for (Player other : others) {
            if (other == viewer) continue;
            if (rules.hideVanished() && (isVanished(other) || !viewer.canSee(other))) continue;
            if (rules.hideSpectators() && other.getGameMode() == GameMode.SPECTATOR) continue;
            if (rules.hideInvisible() && other.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
            if (api != null && !api.getWebApp().getPlayerVisibility(other.getUniqueId())) continue;
            Location position = other.getLocation();
            if (position.distanceSquared(at) > radiusSquared) continue;
            positions[count * 2] = position.getBlockX();
            positions[count * 2 + 1] = position.getBlockZ();
            count++;
        }
        return count * 2 == positions.length ? positions : java.util.Arrays.copyOf(positions, count * 2);
    }

    /**
     * Vanish plugins mark players with "vanished" metadata. Paper deprecates the metadata API but offers
     * no replacement for reading another plugin's flag, so this stays until the vanish plugins move.
     */
    @SuppressWarnings("deprecation")
    private static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    /**
     * Detects the boss bars the server can see: Adventure bars, registered Bukkit bars and boss mobs.
     * Unregistered Bukkit bars can't be seen from the API, and that is noted in the README.
     */
    private boolean hasOtherBossBar(Player player, HudSession session, Map<World, List<Boss>> worldBosses) {
        for (BossBar bar : player.activeBossBars()) {
            if (bar != session.bar) return true;
        }
        for (java.util.Iterator<KeyedBossBar> bars = Bukkit.getBossBars(); bars.hasNext(); ) {
            if (bars.next().getPlayers().contains(player)) return true;
        }
        List<Boss> bosses = worldBosses.computeIfAbsent(player.getWorld(), world -> {
            List<Boss> found = new ArrayList<>();
            for (Entity entity : world.getEntitiesByClasses(org.bukkit.entity.Wither.class, org.bukkit.entity.EnderDragon.class)) {
                found.add((Boss) entity);
            }
            return found;
        });
        for (Boss boss : bosses) {
            if (boss.getBossBar() != null && boss.getBossBar().getPlayers().contains(player)) return true;
        }
        return false;
    }

    private void submit(FrameBuilder builder, HudSession session, Snapshot snapshot) {
        try {
            builders.execute(() -> {
                try {
                    long key = builder.stateKey(snapshot);
                    if (key == session.lastKey) {
                        session.building.set(false);
                        return;
                    }
                    long start = System.nanoTime();
                    int cells = snapshot.coordsOnly() ? 0 : snapshot.fullscreen() ? config.fullscreen().width() * config.fullscreen().height() : snapshot.size() * snapshot.size();
                    if (session.grid.length < cells) session.grid = new int[cells];
                    FrameBuilder.Built built = builder.build(snapshot, session.grid);
                    buildNanos.addAndGet(System.nanoTime() - start);
                    frameBytes.addAndGet(built.estimatedBytes());
                    framesBuilt.incrementAndGet();
                    // A frame can finish just after the plugin is disabled, and it can't be scheduled then.
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> apply(session, key, built.component(), snapshot.fullscreen()));
                    }
                } catch (RuntimeException e) {
                    session.building.set(false);
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not build a map frame", e);
                }
            });
        } catch (RejectedExecutionException e) {
            session.building.set(false);
        }
    }

    private void apply(HudSession session, long key, Component frame, boolean full) {
        session.building.set(false);
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !sessions.containsKey(session.playerId)) return;
        session.lastKey = key;
        if (!full && config.hud().channel() == HudConfig.Channel.ACTION_BAR) {
            if (session.barShown) hideBar(player, session);
            session.actionBarFrame = frame;
            session.actionBarAge = 0;
            player.sendActionBar(frame);
            return;
        }
        session.actionBarFrame = null;
        session.bar.name(frame);
        if (!session.barShown) {
            player.showBossBar(session.bar);
            session.barShown = true;
        }
    }

    private void hide(Player player, HudSession session) {
        hideBar(player, session);
        if (session.actionBarFrame != null) {
            session.actionBarFrame = null;
            player.sendActionBar(Component.empty());
        }
        session.lastKey = 0;
    }

    private static void hideBar(Player player, HudSession session) {
        if (session.barShown) {
            player.hideBossBar(session.bar);
            session.barShown = false;
        }
    }
}
