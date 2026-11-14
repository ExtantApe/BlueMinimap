package com.extantape.bluemapmini.tiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A small cache of decoded tile colours, shared by the background loaders. */
public final class TileStore {
    private static final int MAX_TILES = 16;

    private record Key(String mapId, int lod, int x, int z) {}

    /** A decoded tile. Colours are null when BlueMap has not rendered it. */
    private record CachedTile(int[] argb, long sourceVersion) {}

    private final TileSource source;
    private final Map<String, LowresSettings> settings = new ConcurrentHashMap<>();
    private final Map<Key, CachedTile> tiles = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CachedTile> eldest) {
            return size() > MAX_TILES;
        }
    };

    public TileStore(TileSource source) {
        this.source = source;
    }

    public LowresSettings settings(String mapId) {
        return settings.computeIfAbsent(mapId, id -> {
            try {
                return LowresSettings.parse(source.readSettings(id));
            } catch (IOException e) {
                return LowresSettings.DEFAULT;
            }
        });
    }

    public int cachedTiles() {
        synchronized (tiles) {
            return tiles.size();
        }
    }

    /** Decodes the colour half of a tile. The height half below it is unused until hill shading. */
    public static int[] decodeColors(byte[] png, int tileSize) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("Unreadable tile image");
        int side = tileSize + 1; // BlueMap adds one pixel for seamless edges.
        if (image.getWidth() != side || image.getHeight() != side * 2) {
            throw new IOException("Unexpected tile size " + image.getWidth() + "x" + image.getHeight());
        }
        return image.getRGB(0, 0, side, side, null, 0, side);
    }

    /**
     * Starts one chunk's worth of sampling. Each tile is checked against its source once per reader,
     * and the reader remembers the last tile because neighbouring samples nearly always share it.
     */
    public Reader reader(String mapId, int lod) {
        return new Reader(mapId, lod, settings(mapId));
    }

    public final class Reader {
        private final String mapId;
        private final int lod;
        private final int pixelBlocks;
        private final int tileSize;
        private final Map<Long, int[]> checked = new java.util.HashMap<>();
        private long lastKey = Long.MIN_VALUE;
        private int[] lastTile;

        private Reader(String mapId, int lod, LowresSettings layout) {
            this.mapId = mapId;
            this.lod = lod;
            this.pixelBlocks = layout.blocksPerPixel(lod);
            this.tileSize = layout.tileSize();
        }

        /** The ARGB colour at a block position. Alpha is zero where nothing is rendered. */
        public int sample(int blockX, int blockZ) throws IOException {
            int pixelX = Math.floorDiv(blockX, pixelBlocks);
            int pixelZ = Math.floorDiv(blockZ, pixelBlocks);
            int tileX = Math.floorDiv(pixelX, tileSize);
            int tileZ = Math.floorDiv(pixelZ, tileSize);
            long key = ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
            if (key != lastKey) {
                lastKey = key;
                lastTile = checked.containsKey(key) ? checked.get(key) : load(key, tileX, tileZ);
            }
            if (lastTile == null) return 0;
            return lastTile[Math.floorMod(pixelZ, tileSize) * (tileSize + 1) + Math.floorMod(pixelX, tileSize)];
        }

        private int[] load(long key, int tileX, int tileZ) throws IOException {
            int[] colors = fetch(new Key(mapId, lod, tileX, tileZ), tileSize);
            checked.put(key, colors);
            return colors;
        }
    }

    private int[] fetch(Key key, int tileSize) throws IOException {
        CachedTile cached;
        synchronized (tiles) {
            cached = tiles.get(key);
        }
        long version = source.version(key.mapId, key.lod, key.x, key.z);
        if (cached != null && version > 0 && cached.sourceVersion == version) return cached.argb;

        byte[] png = source.readTile(key.mapId, key.lod, key.x, key.z);
        int[] colors = png == null ? null : decodeColors(png, tileSize);
        synchronized (tiles) {
            tiles.put(key, new CachedTile(colors, version));
        }
        return colors;
    }
}
