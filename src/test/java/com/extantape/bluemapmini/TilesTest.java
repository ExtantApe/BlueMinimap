package com.extantape.bluemapmini;

import com.extantape.bluemapmini.tiles.GridCache;
import com.extantape.bluemapmini.tiles.LowresSettings;
import com.extantape.bluemapmini.tiles.TileSource;
import com.extantape.bluemapmini.tiles.TileStore;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TilesTest {
    /** A map whose colour at any pixel encodes the pixel's own position. */
    private static final class FakeSource implements TileSource {
        final Map<String, byte[]> tiles = new HashMap<>();
        long version = 1;
        int tileSize = 10;
        int reads;

        void put(int lod, int tileX, int tileZ, int size, java.util.function.IntBinaryOperator color) throws IOException {
            BufferedImage image = new BufferedImage(size + 1, (size + 1) * 2, BufferedImage.TYPE_INT_ARGB);
            for (int z = 0; z <= size; z++) {
                for (int x = 0; x <= size; x++) image.setRGB(x, z, color.applyAsInt(x, z));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            tiles.put(lod + "/" + tileX + "/" + tileZ, out.toByteArray());
        }

        @Override public byte[] readTile(String mapId, int lod, int tileX, int tileZ) {
            reads++;
            return tiles.get(lod + "/" + tileX + "/" + tileZ);
        }
        @Override public long version(String mapId, int lod, int tileX, int tileZ) { return version; }
        @Override public String readSettings(String mapId) {
            return "{\"lowres\":{\"tileSize\":[" + tileSize + "," + tileSize + "],\"lodFactor\":5,\"lodCount\":3}}";
        }
    }

    @Test
    void tilePathMatchesBlueMapFileGridStorage() {
        assertEquals("tiles/1/x-1/2/z3.png", TileSource.tilePath(1, -12, 3));
        assertEquals("tiles/1/x0/z-1/0.png", TileSource.tilePath(1, 0, -10));
        assertEquals("tiles/3/x1/2/3/z4/5.png", TileSource.tilePath(3, 123, 45));
    }

    @Test
    void settingsParseAndFallBack() {
        LowresSettings parsed = LowresSettings.parse("{\"lowres\":{\"tileSize\":[250,250],\"lodFactor\":4,\"lodCount\":5}}");
        assertEquals(new LowresSettings(250, 4, 5), parsed);
        assertEquals(LowresSettings.DEFAULT, LowresSettings.parse("not json"));
        assertEquals(LowresSettings.DEFAULT, LowresSettings.parse(null));
    }

    @Test
    void lodChoiceNeverDropsBelowOnePixelPerCell() {
        LowresSettings layout = LowresSettings.DEFAULT;
        assertEquals(1, layout.lodFor(1));
        assertEquals(1, layout.lodFor(4));
        assertEquals(2, layout.lodFor(8));
        assertEquals(2, layout.lodFor(16));
        assertEquals(3, layout.lodFor(32));
        assertEquals(3, layout.lodFor(64));
        assertEquals(25, layout.blocksPerPixel(3));
    }

    @Test
    void wrongSizedTileIsRejected() throws IOException {
        FakeSource source = new FakeSource();
        source.put(1, 0, 0, 20, (x, z) -> 0xFFFFFFFF);
        assertThrows(IOException.class, () -> TileStore.decodeColors(source.tiles.get("1/0/0"), 10));
    }

    @Test
    void samplesTheRightPixelOnBothSidesOfZero() throws IOException {
        FakeSource source = new FakeSource();
        source.put(1, 0, 0, 10, (x, z) -> 0xFF000000 | x << 8 | z);
        source.put(1, -1, -1, 10, (x, z) -> 0xFF800000 | x << 8 | z);
        TileStore.Reader reader = new TileStore(source).reader("m", 1);
        assertEquals(0xFF000304, reader.sample(3, 4));
        // Block -1 is the last column of tile -1, not column 1 of a mirrored tile.
        assertEquals(0xFF800909, reader.sample(-1, -1));
        assertEquals(0xFF800000, reader.sample(-10, -10));
        assertEquals(0, reader.sample(100, 100)); // Not rendered yet.
    }

    @Test
    void gridAveragesCellsAndMarksVoid() throws IOException {
        FakeSource source = new FakeSource();
        source.put(1, 0, 0, 10, (x, z) -> x % 2 == 0 ? 0xFF000000 : 0xFF0000FF);
        try (GridCache cache = new GridCache(new TileStore(source), 16, 60_000, Logger.getAnonymousLogger())) {
            GridCache.Chunk chunk = cache.loadNow("m", 2, 0, 0);
            assertEquals(0x00007F, chunk.cell(0, 0)); // Half black, half blue.
            assertEquals(GridCache.VOID, chunk.cell(10, 0)); // Block 20 is on a tile that doesn't exist.
        }
    }

    @Test
    void unchangedTilesKeepTheirChunkAndChangedOnesDoNot() throws IOException {
        FakeSource source = new FakeSource();
        source.tileSize = 100; // Bigger than a chunk, as on real maps, so one tile covers the whole chunk.
        source.put(1, 0, 0, 100, (x, z) -> 0xFF112233);
        try (GridCache cache = new GridCache(new TileStore(source), 16, 60_000, Logger.getAnonymousLogger())) {
            GridCache.Chunk first = cache.loadNow("m", 1, 0, 0);
            int reads = source.reads;
            GridCache.Chunk same = cache.loadNow("m", 1, 0, 0);
            assertSame(first, same);
            assertEquals(reads, source.reads, "An unchanged tile isn't read or decoded again");

            source.put(1, 0, 0, 100, (x, z) -> 0xFF445566);
            source.version = 2;
            GridCache.Chunk changed = cache.loadNow("m", 1, 0, 0);
            assertNotSame(first, changed);
            assertEquals(0x445566, changed.cell(0, 0));
        }
    }
}
