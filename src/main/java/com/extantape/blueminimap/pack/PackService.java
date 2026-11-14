package com.extantape.blueminimap.pack;

import com.extantape.blueminimap.config.HudConfig;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Saves, hosts and sends the pack, and keeps track of which players have it loaded. */
public final class PackService {
    /** Fixed, so that re-sending replaces this pack and never touches anyone else's. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("bluemaphud:pack".getBytes(StandardCharsets.UTF_8));
    private static final long RESEND_DELAY_TICKS = 40;

    private final Plugin plugin;
    private final Path packFolder;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Set<UUID> resent = ConcurrentHashMap.newKeySet();
    /** Players whose download failed this session, who get the pack offered again on a world change. */
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();
    /** Admins hear about failures at most this often, however many players are affected. */
    private static final long ADMIN_WARNING_GAP_MILLIS = 5 * 60 * 1000L;
    private long lastAdminWarning;
    private HudConfig.Pack config;
    private volatile PackBuilder.Built pack;
    private HttpServer server;
    private String serverBind = "";
    private int serverPort = -1;
    private volatile String servedPath = "";
    private volatile byte[] servedBytes = new byte[0];
    private volatile String publicUrlStatus = "not checked";

    public PackService(Plugin plugin, Path packFolder) {
        this.plugin = plugin;
        this.packFolder = packFolder;
    }

    /** Installs a built pack. Returns true when its contents differ from the previous one. */
    public boolean install(HudConfig.Pack config, PackBuilder.Built built) throws IOException {
        boolean changed = pack == null || !pack.sha1Hex().equals(built.sha1Hex());
        this.config = config;
        this.pack = built;
        Files.createDirectories(packFolder);
        Files.write(exportPath(), built.zip());
        servedPath = "/bluemapmini-" + built.sha1Hex() + ".zip";
        servedBytes = built.zip();
        if (config.send() && config.publicUrl().isBlank()) startServer(config.port(), config.bind());
        else stopServer();
        if (!config.send()) checkMergedPackAsync();
        else if (!config.publicUrl().isBlank()) checkPublicUrlAsync();
        else publicUrlStatus = "not used";
        return changed;
    }

    /** Where players download the pack from, or null when it's hosted by the built-in server. */
    public String publicUrl() {
        if (config == null || config.publicUrl().isBlank() || pack == null) return null;
        return PublicUrlCheck.fill(config.publicUrl(), pack.sha1Hex());
    }

    /** The last public-url check's result, for /bluemapmini debug. */
    public String publicUrlStatus() {
        return publicUrlStatus;
    }

    public Path exportPath() {
        return packFolder.resolve("bluemapmini-pack.zip");
    }

    public String sha1() {
        return pack == null ? "" : pack.sha1Hex();
    }

    /** True when this player's client has the pack, or when the server's own merged pack provides it. */
    public boolean ready(Player player) {
        return !config.send() || loaded.contains(player.getUniqueId());
    }

    public void forget(Player player) {
        loaded.remove(player.getUniqueId());
        resent.remove(player.getUniqueId());
        failed.remove(player.getUniqueId());
    }

    /** Offers the pack again after a failed download. Called when the player changes world; joining sends it anyway. */
    public void retryIfFailed(Player player) {
        if (failed.remove(player.getUniqueId()) && player.isOnline()) send(player);
    }

    private void reportFailure(Player player, PlayerResourcePackStatusEvent.Status status) {
        if (!player.isOnline()) return;
        player.sendMessage(Component.text("The map's resource pack couldn't be downloaded, so the map is off for now. "
                + "It'll try again when you change worlds or rejoin.", NamedTextColor.YELLOW));
        String reason = status == PlayerResourcePackStatusEvent.Status.INVALID_URL ? "the address was invalid"
                : status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD ? "it didn't load" : "the download failed";
        plugin.getLogger().warning(player.getName() + " didn't get the BlueMapMini pack: " + reason + ". They're playing without the map."
                + " Check the pack's hosting; /bluemapmini pack export shows the address players are sent.");
        long now = System.currentTimeMillis();
        if (now - lastAdminWarning < ADMIN_WARNING_GAP_MILLIS) return;
        lastAdminWarning = now;
        Component warning = Component.text("[BlueMapMini] " + player.getName() + " couldn't download the map's pack (" + reason
                + "). Check the pack's hosting. /bluemapmini pack export shows the address.", NamedTextColor.GOLD);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (com.extantape.blueminimap.Perms.has(admin, "bluemapmini.admin")) admin.sendMessage(warning);
        }
    }

    public void send(Player player) {
        if (!config.send() || pack == null) return;
        if (config.publicUrl().isBlank() && serverPort < 0) return; // Hosting failed, and that's already logged.
        if ("invalid address".equals(publicUrlStatus)) return; // Already logged; a bad URI would throw on every join.
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, packUri(player), pack.sha1Hex());
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .replace(false) // Stack with the player's other server packs.
                .required(config.required());
        if (!config.prompt().isBlank()) request.prompt(Component.text(config.prompt()));
        player.sendResourcePacks(request.build());
    }

    /** Reacts to the client's report on our pack. Returns true when the player's HUD state may have changed. */
    public boolean onStatus(PlayerResourcePackStatusEvent event, boolean bedrock) {
        if (!PACK_ID.equals(event.getID())) return false;
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                loaded.add(player.getUniqueId());
                failed.remove(player.getUniqueId());
            }
            case DECLINED -> {
                // The player said no. That alone follows pack.required.
                loaded.remove(player.getUniqueId());
                if (config.required() && !bedrock) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(config.kickMessage())));
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                // A hosting or network problem is never the player's fault, so they stay, with the HUD off.
                loaded.remove(player.getUniqueId());
                if (!bedrock && failed.add(player.getUniqueId())) {
                    Bukkit.getScheduler().runTask(plugin, () -> reportFailure(player, event.getStatus()));
                }
            }
            case DISCARDED -> {
                // Usually another plugin clearing all packs with the old one-pack method. Re-send once, never in a loop.
                loaded.remove(player.getUniqueId());
                if (config.reSendOnRemoval() && resent.add(player.getUniqueId())) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) send(player);
                    }, RESEND_DELAY_TICKS);
                }
            }
            default -> { }
        }
        return true;
    }

    public void close() {
        stopServer();
    }

    private URI packUri(Player player) {
        String publicUrl = publicUrl();
        if (publicUrl != null) return URI.create(publicUrl);
        String host = config.host();
        if (host.isBlank() && player.getVirtualHost() != null) host = player.getVirtualHost().getHostString();
        if (host == null || host.isBlank()) host = Bukkit.getIp();
        if (host.isBlank()) host = "localhost";
        return URI.create("http://" + host + ":" + serverPort + servedPath);
    }

    private void startServer(int port, String bind) throws IOException {
        if (server != null && serverPort == port && bind.equals(serverBind)) return;
        stopServer();
        InetSocketAddress address = listenAddress(port, bind);
        if (address == null) {
            // A typo must never fall back to listening on every address.
            plugin.getLogger().severe("pack.bind is \"" + bind + "\", which isn't an IP address, so the resource pack isn't hosted and the map stays hidden."
                    + " Use an address such as 127.0.0.1, or leave it empty to listen on every address.");
            return;
        }
        try {
            server = HttpServer.create(address, 16);
        } catch (BindException e) {
            // Sending a dead URL would kick everyone when the pack is required, so send nothing instead.
            plugin.getLogger().severe("Port " + port + " is already in use, so the resource pack can't be hosted and the map stays hidden."
                    + " Set pack.port to a free port, or set pack.public-url.");
            return;
        }
        serverBind = bind;
        if (!bind.isEmpty() && address.getAddress().isLoopbackAddress()) {
            plugin.getLogger().info("The resource pack is served on " + bind + ":" + port + " only, so only players on this machine can download it from here."
                    + " Players elsewhere need pack.public-url (or pack.send: false with the files merged into your own pack).");
        }
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "BlueMapMini-pack");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            try (exchange) {
                boolean match = "GET".equals(exchange.getRequestMethod()) && servedPath.equals(exchange.getRequestURI().getPath());
                if (!match) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = servedBytes;
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
        serverPort = port;
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
            serverPort = -1;
        }
    }

    /**
     * Downloads public-url once and warns if it isn't the pack just built, because the client would reject
     * it. Warns only: the address may be reachable by players but not by this server.
     */
    private void checkPublicUrlAsync() {
        String url = publicUrl();
        String expected = pack.sha1Hex();
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || !uri.getScheme().toLowerCase(java.util.Locale.ROOT).matches("https?")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            publicUrlStatus = "invalid address";
            plugin.getLogger().warning("pack.public-url isn't a valid http or https address: " + url);
            return;
        }
        if (!config.verifyPublicUrl()) {
            publicUrlStatus = "check turned off";
            return;
        }
        publicUrlStatus = "checking";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PublicUrlCheck.Result result = PublicUrlCheck.check(url, expected, Duration.ofSeconds(30));
            if (pack == null || !expected.equals(pack.sha1Hex())) return; // A newer pack was built; its own check reports.
            publicUrlStatus = result.matches() ? "matches" : "MISMATCH or unreachable";
            if (result.matches()) plugin.getLogger().info(result.message());
            else plugin.getLogger().warning(result.message());
        });
    }

    /** In merged-pack mode, warns if the server's pack doesn't contain our files. */
    private void checkMergedPackAsync() {
        String url = Bukkit.getResourcePack();
        if (url == null || url.isBlank()) {
            plugin.getLogger().warning("pack.send is false, but server.properties has no resource pack. Players will see boxes instead of a map.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                boolean found = false;
                try (ZipInputStream zip = new ZipInputStream(response.body())) {
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                        if (entry.getName().equals("assets/bluemapmini/font/hud.json")) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    plugin.getLogger().warning("The server's resource pack doesn't contain BlueMapMini's files. Merge " + exportPath() + " into it.");
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not check the server's resource pack: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Where the pack server listens: every address when {@code bind} is empty, or that IP address. Null if it isn't one. */
    static InetSocketAddress listenAddress(int port, String bind) {
        if (bind.isEmpty()) return new InetSocketAddress(port);
        try {
            return new InetSocketAddress(java.net.InetAddress.ofLiteral(bind), port);
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }
}
