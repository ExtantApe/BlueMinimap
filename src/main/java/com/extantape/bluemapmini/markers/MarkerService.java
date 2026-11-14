package com.extantape.bluemapmini.markers;

import com.extantape.bluemapmini.config.HudConfig;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A short-lived copy of each map's POI markers, read from BlueMap's API. */
public final class MarkerService {
    private static final long REFRESH_MS = 5_000;

    public record Poi(int x, int z, String label) {}
    private record Cached(List<Poi> pois, long loadedAt) {}

    private final HudConfig.Markers config;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MarkerService(HudConfig.Markers config) {
        this.config = config;
    }

    public List<Poi> pois(String mapId) {
        if (!config.enabled()) return List.of();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(mapId);
        if (cached != null && now - cached.loadedAt < REFRESH_MS) return cached.pois;
        List<Poi> pois = read(mapId);
        cache.put(mapId, new Cached(pois, now));
        return pois;
    }

    private List<Poi> read(String mapId) {
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) return List.of();
        BlueMapMap map = api.getMap(mapId).orElse(null);
        if (map == null) return List.of();
        List<Poi> pois = new ArrayList<>();
        for (Map.Entry<String, MarkerSet> set : map.getMarkerSets().entrySet()) {
            if (!included(set.getKey())) continue;
            for (Marker marker : set.getValue().getMarkers().values()) {
                if (marker instanceof POIMarker poi) {
                    pois.add(new Poi(poi.getPosition().getFloorX(), poi.getPosition().getFloorZ(), poi.getLabel()));
                }
            }
        }
        return List.copyOf(pois);
    }

    private boolean included(String setId) {
        if (config.exclude().contains(setId)) return false;
        return config.include().isEmpty() || config.include().contains(setId);
    }
}
