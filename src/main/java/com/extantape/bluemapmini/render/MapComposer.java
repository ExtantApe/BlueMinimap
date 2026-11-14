package com.extantape.bluemapmini.render;

import com.extantape.bluemapmini.tiles.GridCache;

/** Fills a colour grid from cached chunks, and fingerprints those chunks to detect tile changes. */
public final class MapComposer {
    private final GridCache cache;
    private final int voidColor;
    private final int colorStep;

    public MapComposer(GridCache cache, int voidColor, int colorStep) {
        this.cache = cache;
        this.voidColor = voidColor;
        this.colorStep = colorStep;
    }

    /**
     * A number that changes when any chunk in view changes. Chunks are replaced rather than edited, so
     * their identities are enough. This also queues whatever is missing.
     */
    public long chunkFingerprint(String mapId, int bpc, int firstCellX, int firstCellZ, int width, int height) {
        long hash = 17;
        int startX = firstCellX >> GridCache.CHUNK_BITS, endX = (firstCellX + width - 1) >> GridCache.CHUNK_BITS;
        int startZ = firstCellZ >> GridCache.CHUNK_BITS, endZ = (firstCellZ + height - 1) >> GridCache.CHUNK_BITS;
        for (int z = startZ; z <= endZ; z++) {
            for (int x = startX; x <= endX; x++) {
                hash = hash * 31 + System.identityHashCode(cache.chunk(mapId, bpc, x, z));
            }
        }
        return hash;
    }

    /** Fills {@code grid} from the cache. Cells that aren't loaded yet use the void colour. */
    public void fill(int[] grid, String mapId, int bpc, int firstCellX, int firstCellZ, int width, int height, double brightness) {
        int currentX = Integer.MIN_VALUE, currentZ = Integer.MIN_VALUE;
        GridCache.Chunk chunk = null;
        for (int z = 0; z < height; z++) {
            int cellZ = firstCellZ + z;
            int chunkZ = cellZ >> GridCache.CHUNK_BITS;
            for (int x = 0; x < width; x++) {
                int cellX = firstCellX + x;
                int chunkX = cellX >> GridCache.CHUNK_BITS;
                if (chunkX != currentX || chunkZ != currentZ) {
                    chunk = cache.chunk(mapId, bpc, chunkX, chunkZ);
                    currentX = chunkX;
                    currentZ = chunkZ;
                }
                int color = chunk == null ? GridCache.VOID : chunk.cell(cellX & GridCache.CHUNK_MASK, cellZ & GridCache.CHUNK_MASK);
                grid[z * width + x] = shade(color == GridCache.VOID ? voidColor : color, brightness);
            }
        }
    }

    /** Applies brightness, then rounds so that neighbouring cells more often share a colour. */
    private int shade(int rgb, double brightness) {
        return round((int) ((rgb >> 16 & 0xFF) * brightness)) << 16
                | round((int) ((rgb >> 8 & 0xFF) * brightness)) << 8
                | round((int) ((rgb & 0xFF) * brightness));
    }

    private int round(int channel) {
        return Math.min(255, (channel + colorStep / 2) / colorStep * colorStep);
    }
}
