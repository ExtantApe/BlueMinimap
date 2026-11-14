package com.extantape.bluemapmini.tiles;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Fetches tiles from BlueMap's webserver, which also serves SQL storage. */
public final class HttpTileSource implements TileSource {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpTileSource(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public byte[] readTile(String mapId, int lod, int tileX, int tileZ) throws IOException {
        return get(url(mapId, TileSource.tilePath(lod, tileX, tileZ)));
    }

    /** BlueMap's map handler sends no validators, so the store re-reads and compares instead. */
    @Override
    public long version(String mapId, int lod, int tileX, int tileZ) {
        return -1;
    }

    @Override
    public String readSettings(String mapId) throws IOException {
        byte[] body = get(url(mapId, "settings.json"));
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private String url(String mapId, String path) {
        return baseUrl + "/maps/" + URLEncoder.encode(mapId, StandardCharsets.UTF_8) + "/" + path;
    }

    private byte[] get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            // BlueMap answers 204 for a tile that has not been rendered yet.
            if (status == 204 || status == 404) return null;
            if (status != 200) throw new IOException("HTTP " + status + " from " + url);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }
}
