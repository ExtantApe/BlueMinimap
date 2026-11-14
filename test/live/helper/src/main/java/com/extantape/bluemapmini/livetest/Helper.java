package com.extantape.bluemapmini.livetest;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Boss;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Test only. Pokes the server the way other plugins would, driven by the live tests from the console with
 * {@code /bmt <action> ...}. Every reply is logged as "BMT ..." for the tests to read.
 */
public final class Helper extends JavaPlugin {
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Map<String, BossBar> adventureBars = new HashMap<>();
    private org.bukkit.boss.BossBar plainBar;

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("bmt", "Live test helper", new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack source, String[] args) {
                        run(args);
                    }

                    @Override
                    public String permission() {
                        return "bluemapmini.livetest";
                    }
                }));
    }

    private void say(String text) {
        getLogger().info("BMT " + text);
    }

    private static Player player(String name) {
        return Bukkit.getPlayerExact(name);
    }

    private void run(String[] a) {
        try {
            switch (a[0]) {
                case "vanish" -> {
                    setVanished(player(a[1]), a[2].equals("on"));
                    say("vanish " + a[2]);
                }
                case "hidefrom" -> {
                    if (a[3].equals("on")) player(a[1]).hidePlayer(this, player(a[2]));
                    else player(a[1]).showPlayer(this, player(a[2]));
                    say("hidefrom " + a[3]);
                }
                case "perm" -> {
                    Player p = player(a[1]);
                    // An attachment from an earlier login belongs to a Player object that's gone, so setting it would do nothing.
                    PermissionAttachment held = permissions.get(p.getUniqueId());
                    if (held == null || held.getPermissible() != p) { held = p.addAttachment(this); permissions.put(p.getUniqueId(), held); }
                    held.setPermission(a[2], Boolean.parseBoolean(a[3]));
                    p.recalculatePermissions();
                    p.updateCommands();
                    say("perm " + a[2] + " " + a[3]);
                }
                case "magicpack" -> { // The old one-pack method, as Magic uses, which clears every other pack.
                    sendSinglePack(player(a[1]), a[2]);
                    say("magicpack sent");
                }
                case "advbar" -> {
                    Player p = player(a[1]);
                    if (a[2].equals("on")) {
                        BossBar bar = BossBar.bossBar(Component.text("spell"), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                        adventureBars.put(a[1], bar);
                        p.showBossBar(bar);
                    } else {
                        p.hideBossBar(adventureBars.remove(a[1]));
                    }
                    say("advbar " + a[2]);
                }
                case "keyedbar" -> {
                    NamespacedKey key = new NamespacedKey(this, "keyed");
                    if (a[2].equals("on")) {
                        Bukkit.createBossBar(key, "keyed", BarColor.RED, BarStyle.SOLID).addPlayer(player(a[1]));
                    } else {
                        Bukkit.getBossBar(key).removeAll();
                        Bukkit.removeBossBar(key);
                    }
                    say("keyedbar " + a[2]);
                }
                case "plainbar" -> { // An unregistered Bukkit bar, which no API can see.
                    if (a[2].equals("on")) {
                        plainBar = Bukkit.createBossBar("plain", BarColor.RED, BarStyle.SOLID);
                        plainBar.addPlayer(player(a[1]));
                    } else {
                        plainBar.removeAll();
                    }
                    say("plainbar " + a[2]);
                }
                case "bmhide" -> {
                    BlueMapAPI.getInstance().orElseThrow().getWebApp().setPlayerVisibility(player(a[1]).getUniqueId(), !a[2].equals("on"));
                    say("bmhide " + a[2]);
                }
                case "poi" -> { // poi <map> <set> <id> <x> <y> <z> <label...>
                    BlueMapMap map = BlueMapAPI.getInstance().orElseThrow().getMap(a[1]).orElseThrow();
                    MarkerSet set = map.getMarkerSets().computeIfAbsent(a[2], id -> MarkerSet.builder().label(id).build());
                    String label = String.join(" ", Arrays.copyOfRange(a, 7, a.length));
                    Vector3d position = new Vector3d(Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]));
                    set.put(a[3], POIMarker.builder().label(label).position(position).build());
                    say("poi added");
                }
                case "poidel" -> {
                    BlueMapAPI.getInstance().orElseThrow().getMap(a[1]).orElseThrow().getMarkerSets().remove(a[2]);
                    say("poi removed");
                }
                case "maps" -> {
                    BlueMapAPI api = BlueMapAPI.getInstance().orElseThrow();
                    for (BlueMapMap map : api.getMaps()) say("map " + map.getId() + " world=" + map.getWorld().getId());
                    say("root " + api.getWebApp().getWebRoot().toAbsolutePath());
                    say("api " + api.getAPIVersion());
                }
                case "bossinfo" -> bossInfo(player(a[1]));
                case "bars" -> say("activebars " + count(player(a[1]).activeBossBars()));
                case "health" -> {
                    Runtime runtime = Runtime.getRuntime();
                    say("mspt " + Bukkit.getAverageTickTime() + " tps " + Bukkit.getTPS()[0]
                            + " usedMB " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576);
                }
                case "gc" -> {
                    System.gc();
                    Runtime runtime = Runtime.getRuntime();
                    say("afterGC usedMB " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576);
                }
                case "sharefilter" -> shareFilter(a);
                case "mkworld" -> { // A second world, in its own world group.
                    org.bukkit.World world = Bukkit.createWorld(new org.bukkit.WorldCreator(a[1]).type(org.bukkit.WorldType.FLAT));
                    say("mkworld " + (world != null ? world.getName() : "failed"));
                }
                case "execute" -> say("perform " + player(a[1]).performCommand(String.join(" ", Arrays.copyOfRange(a, 2, a.length))));
                default -> say("unknown " + a[0]);
            }
        } catch (Exception e) {
            say("FAILED " + a[0] + ": " + e);
        }
    }

    private final org.bukkit.event.Listener shareListener = new org.bukkit.event.Listener() {};
    private String shareMode = "off";

    /**
     * sharefilter off | log | cancel | remove <name>: listens to CoordinateShareEvent like a chat rules plugin.
     * Every share it sees is logged as "BMT share ...".
     */
    private void shareFilter(String[] a) {
        org.bukkit.event.HandlerList.unregisterAll(shareListener);
        shareMode = a.length > 2 ? a[1] + " " + a[2] : a[1];
        if (!a[1].equals("off")) {
            getServer().getPluginManager().registerEvent(com.extantape.bluemapmini.api.CoordinateShareEvent.class, shareListener,
                    org.bukkit.event.EventPriority.NORMAL, (listener, event) -> {
                        var share = (com.extantape.bluemapmini.api.CoordinateShareEvent) event;
                        say("share from " + share.getSender().getName() + " at " + share.getX() + " " + share.getY() + " " + share.getZ()
                                + " to " + share.getRecipients().stream().map(Player::getName).sorted().toList());
                        if (shareMode.equals("cancel")) share.setCancelled(true);
                        if (shareMode.startsWith("remove ")) share.getRecipients().removeIf(p -> p.getName().equals(shareMode.substring(7)));
                    }, this);
        }
        say("sharefilter " + shareMode);
    }

    @SuppressWarnings("deprecation") // Deliberately the metadata vanish plugins still set.
    private void setVanished(Player player, boolean vanished) {
        if (vanished) player.setMetadata("vanished", new FixedMetadataValue(this, true));
        else player.removeMetadata("vanished", this);
    }

    @SuppressWarnings("deprecation") // Deliberately the old method, to copy what older plugins do.
    private static void sendSinglePack(Player player, String url) {
        player.setResourcePack(url);
    }

    private void bossInfo(Player p) {
        say("bossinfo active " + count(p.activeBossBars()));
        for (Iterator<KeyedBossBar> bars = Bukkit.getBossBars(); bars.hasNext(); ) {
            KeyedBossBar bar = bars.next();
            if (bar.getPlayers().contains(p)) say("bossinfo keyed " + bar.getKey());
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClasses(Wither.class, EnderDragon.class)) {
                org.bukkit.boss.BossBar bar = ((Boss) entity).getBossBar();
                say("bossinfo mob " + entity.getType() + " in " + world.getName() + " bar " + (bar != null)
                        + " hasPlayer " + (bar != null && bar.getPlayers().contains(p)));
            }
        }
        say("bossinfo player world " + p.getWorld().getName());
    }

    private static int count(Iterable<?> items) {
        int n = 0;
        for (Object ignored : items) n++;
        return n;
    }
}
