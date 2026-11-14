package com.extantape.bluemapmini.tiles;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An LRU of shrunk colour grids. The world is cut into 64x64 cell chunks per map and zoom. Chunks load
 * on two background threads and are never waited on: a missing chunk reads as void until it arrives.
 */
public final class GridCache implements AutoCloseable {
    public static final int CHUNK_BITS = 6;
    public static final int CHUNK_CELLS = 1 << CHUNK_BITS;
    public static final int CHUNK_MASK = CHUNK_CELLS - 1;
    /** A cell with no rendered colour. */
    public static final int VOID = -1;

    private static final int MAX_SAMPLES_PER_AXIS = 3;
    /** How soon an unrendered chunk is tried again. */
    private static final long MISSING_RETRY_MS = 60_000;

    public record ChunkKey(String mapId, int blocksPerCell, int chunkX, int chunkZ) {}

    /** Identity matters: a new instance means the colours changed, so HUDs can tell they must redraw. */
    public static final class Chunk {
        final int[] cells;
        final boolean complete;
        volatile long loadedAt;

        Chunk(int[] cells, boolean complete, long loadedAt) {
            this.cells = cells;
            this.complete = complete;
            this.loadedAt = loadedAt;
        }

        public int cell(int localX, int localZ) {
            return cells[localZ << CHUNK_BITS | localX];
        }
    }

    private final TileStore store;
    private final long refreshMs;
    private final Logger logger;
    private final Set<ChunkKey> loading = ConcurrentHashMap.newKeySet();
    private final ExecutorService loaders = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BlueMapMini-tiles");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Map<ChunkKey, Chunk> chunks;

    public GridCache(TileStore store, int maxChunks, long refreshMs, Logger logger) {
        this.store = store;
        this.refreshMs = refreshMs;
        this.logger = logger;
        this.chunks = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkKey, Chunk> eldest) {
                return size() > maxChunks;
            }
        };
    }

    /** The chunk if it is ready, or null after queueing a load. Stale chunks are returned and refreshed. */
    public Chunk chunk(String mapId, int blocksPerCell, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(mapId, blocksPerCell, chunkX, chunkZ);
        Chunk chunk;
        synchronized (chunks) {
            chunk = chunks.get(key);
        }
        long now = System.currentTimeMillis();
        if (chunk == null || now - chunk.loadedAt > (chunk.complete ? refreshMs : MISSING_RETRY_MS)) {
            queue(key);
        }
        return chunk;
    }

    /** Loads a chunk on the calling thread. Meant for debug dumps and tests. */
    public Chunk loadNow(String mapId, int blocksPerCell, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(mapId, blocksPerCell, chunkX, chunkZ);
        load(key);
        synchronized (chunks) {
            return chunks.get(key);
        }
    }

    public int cachedChunks() {
        synchronized (chunks) {
            return chunks.size();
        }
    }

    public int cachedTiles() {
        return store.cachedTiles();
    }

    private void queue(ChunkKey key) {
        if (!loading.add(key)) return;
        try {
            loaders.execute(() -> {
                try {
                    load(key);
                } finally {
                    loading.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            loading.remove(key); // Shutting down.
        }
    }

    private void load(ChunkKey key) {
        try {
            LoadedCells loaded = readCells(key);
            synchronized (chunks) {
                Chunk old = chunks.get(key);
                long now = System.currentTimeMillis();
                if (old != null && Arrays.equals(old.cells, loaded.cells) && old.complete == loaded.complete) {
                    old.loadedAt = now; // Unchanged, so HUDs have nothing to redraw.
                } else {
                    chunks.put(key, new Chunk(loaded.cells, loaded.complete, now));
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not load map tiles for " + key, e);
            synchronized (chunks) {
                // Remember the failure so it is retried later instead of on every update.
                if (!chunks.containsKey(key)) {
                    int[] empty = new int[CHUNK_CELLS * CHUNK_CELLS];
                    Arrays.fill(empty, VOID);
                    chunks.put(key, new Chunk(empty, false, System.currentTimeMillis()));
                }
            }
        }
    }

    private record LoadedCells(int[] cells, boolean complete) {}

    private LoadedCells readCells(ChunkKey key) throws IOException {
        int bpc = key.blocksPerCell;
        LowresSettings layout = store.settings(key.mapId);
        int lod = layout.lodFor(bpc);
        TileStore.Reader reader = store.reader(key.mapId, lod);
        int pixelBlocks = layout.blocksPerPixel(lod);
        int samples = Math.max(1, Math.min(MAX_SAMPLES_PER_AXIS, bpc / pixelBlocks));
        int spacing = Math.max(1, bpc / samples);

        int[] cells = new int[CHUNK_CELLS * CHUNK_CELLS];
        boolean complete = true;
        int originX = key.chunkX * CHUNK_CELLS * bpc;
        int originZ = key.chunkZ * CHUNK_CELLS * bpc;
        for (int z = 0; z < CHUNK_CELLS; z++) {
            for (int x = 0; x < CHUNK_CELLS; x++) {
                int red = 0, green = 0, blue = 0, count = 0;
                for (int sz = 0; sz < samples; sz++) {
                    for (int sx = 0; sx < samples; sx++) {
                        int argb = reader.sample(originX + x * bpc + sx * spacing, originZ + z * bpc + sz * spacing);
                        if (argb >>> 24 == 0) continue;
                        red += argb >> 16 & 0xFF;
                        green += argb >> 8 & 0xFF;
                        blue += argb & 0xFF;
                        count++;
                    }
                }
                if (count == 0) {
                    cells[z << CHUNK_BITS | x] = VOID;
                    complete = false;
                } else {
                    cells[z << CHUNK_BITS | x] = (red / count) << 16 | (green / count) << 8 | (blue / count);
                }
            }
        }
        return new LoadedCells(cells, complete);
    }

    @Override
    public void close() {
        loaders.shutdownNow();
    }
}
