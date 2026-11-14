package com.extantape.bluemapmini;

import com.extantape.bluemapmini.config.HudConfig;
import com.extantape.bluemapmini.hud.FrameBuilder;
import com.extantape.bluemapmini.hud.Snapshot;
import com.extantape.bluemapmini.markers.MarkerService;
import com.extantape.bluemapmini.pack.PackBuilder;
import com.extantape.bluemapmini.render.FontLayout;
import com.extantape.bluemapmini.render.HudFonts;
import com.extantape.bluemapmini.render.HudIcon;
import com.extantape.bluemapmini.render.IconSet;
import com.extantape.bluemapmini.render.MapComposer;
import com.extantape.bluemapmini.render.MapFrame;
import com.extantape.bluemapmini.render.TinyFont;
import com.extantape.bluemapmini.tiles.GridCache;
import com.extantape.bluemapmini.tiles.TileSource;
import com.extantape.bluemapmini.tiles.TileStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderTest {
    private static final int SCREEN_W = 480;
    private static final int SCREEN_H = 270;
    /** A boss bar's text starts here: the first bar is at y 12 and its title 9 above that. */
    private static final int BOSS_TEXT_Y = 3;

    private static YamlConfiguration yaml() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                RenderTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static HudConfig config() {
        return HudConfig.from(yaml());
    }

    private static SimulatedClient client(HudConfig config, HudFonts fonts) throws IOException {
        return new SimulatedClient(PackBuilder.build(config, fonts, new IconSet(null, Logger.getAnonymousLogger())).zip());
    }

    private static int[] advances() {
        return new IconSet(null, Logger.getAnonymousLogger()).advances();
    }

    /** Terrain-like colours: smooth noise with a few bands, like a real top-down map. */
    private static int terrain(int x, int z) {
        double v = Math.sin(x * 0.13) + Math.sin(z * 0.17) + 0.5 * Math.sin((x + z) * 0.31);
        if (v < -0.7) return 0x3F76E4 - (int) (-v * 8) * 0x010101; // Water.
        if (v > 1.3) return 0x8A8A8A + ((x * 31 + z * 17) & 7) * 0x010101; // Stone.
        return 0x5C9E36 + ((x * 13 ^ z * 7) & 15) * 0x010000 + ((x ^ z) & 7) * 0x000100; // Grass.
    }

    private static int round8(int rgb) {
        int r = Math.min(255, ((rgb >> 16 & 255) + 4) / 8 * 8);
        int g = Math.min(255, ((rgb >> 8 & 255) + 4) / 8 * 8);
        int b = Math.min(255, ((rgb & 255) + 4) / 8 * 8);
        return r << 16 | g << 8 | b;
    }

    @Test
    void defaultConfigParses() {
        HudConfig config = config();
        assertEquals(HudConfig.Channel.BOSS_BAR, config.hud().channel());
        assertEquals(32, config.hud().size());
        assertEquals(List.of("map", "worldmap"), config.commands().mapNames());
        assertEquals(0x14161a, config.hud().voidColor());
        assertEquals("pink", config.pack().bossBarColor());
    }

    @Test
    void packUsesItsOwnNamespaceAndNoShaders() throws IOException {
        HudConfig config = config();
        SimulatedClient client = client(config, new HudFonts(config));
        for (String path : client.files.keySet()) {
            assertFalse(path.contains("shaders"), path);
            assertTrue(path.equals("pack.mcmeta")
                    || path.startsWith("assets/bluemapmini/")
                    || path.startsWith("assets/minecraft/textures/gui/sprites/boss_bar/pink_"), path);
        }
        JsonObject pack = JsonParser.parseString(new String(client.files.get("pack.mcmeta"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("pack");
        assertEquals(84, pack.get("min_format").getAsInt());
        assertEquals(999, pack.get("max_format").getAsInt());
    }

    @Test
    void bossBarTexturesAreFullyTransparentAndCanBeSwitchedOff() throws IOException {
        HudConfig config = config();
        SimulatedClient client = client(config, new HudFonts(config));
        for (String part : List.of("background", "progress")) {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(
                    client.files.get("assets/minecraft/textures/gui/sprites/boss_bar/pink_" + part + ".png")));
            assertEquals(182, image.getWidth());
            assertEquals(5, image.getHeight());
            for (int x = 0; x < 182; x++) {
                for (int y = 0; y < 5; y++) assertEquals(0, image.getRGB(x, y) >>> 24);
            }
        }
        YamlConfiguration off = yaml();
        off.set("pack.hide-boss-bar", false);
        HudConfig noBar = HudConfig.from(off);
        assertTrue(client(noBar, new HudFonts(noBar)).files.keySet().stream().noneMatch(p -> p.startsWith("assets/minecraft/")));
    }

    @Test
    void packHashIsStable() throws IOException {
        HudConfig config = config();
        IconSet icons = new IconSet(null, Logger.getAnonymousLogger());
        assertEquals(PackBuilder.build(config, new HudFonts(config), icons).sha1Hex(),
                PackBuilder.build(config, new HudFonts(config), icons).sha1Hex());
    }

    /** Every cell lands on the right pixels, with no gaps and no drift, at each supported cell size. */
    @Test
    void mapIsPixelPerfectInEveryConfiguration() throws IOException {
        for (int cellPixels : new int[] {1, 2, 3, 4}) {
            for (String channel : List.of("boss-bar", "action-bar")) {
                YamlConfiguration yaml = yaml();
                yaml.set("hud.cell-pixels", cellPixels);
                yaml.set("hud.channel", channel);
                HudConfig config = HudConfig.from(yaml);
                HudFonts fonts = new HudFonts(config);
                SimulatedClient client = client(config, fonts);
                FontLayout layout = fonts.minimap;
                for (int size : new int[] {24, 32, 40}) {
                    int[] grid = new int[size * size];
                    for (int z = 0; z < size; z++) {
                        for (int x = 0; x < size; x++) grid[z * size + x] = terrain(x, z);
                    }
                    int offsetX = cellPixels == 2 ? 37 : 0;
                    // The tallest maps nearly fill a small screen, so only offset ones that have room.
                    int offsetRows = size * cellPixels <= 128 ? Math.min(3, fonts.maxOffsetRows) : 0;
                    MapFrame frame = new MapFrame(layout, advances(), size, size, offsetX, offsetRows);
                    frame.drawGrid(grid);
                    Component component = frame.finish();
                    assertEquals(0, client.totalAdvance(component), "A frame must net to zero to stay centred");

                    int textY = channel.equals("boss-bar") ? BOSS_TEXT_Y : SCREEN_H - 72;
                    BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
                    client.draw(component, image, textY);

                    int left = SCREEN_W / 2 + offsetX - size * cellPixels / 2;
                    int top = textY + layout.rowTop(layout.rowFor(0, size, offsetRows));
                    assertTrue(top >= 0, "Map is off the top of the screen at " + top);
                    for (int z = 0; z < size * cellPixels; z++) {
                        for (int x = 0; x < size * cellPixels; x++) {
                            int expected = 0xFF000000 | grid[z / cellPixels * size + x / cellPixels];
                            assertEquals(expected, image.getRGB(left + x, top + z),
                                    "cell " + cellPixels + "px " + channel + " size " + size + " at " + x + "," + z);
                        }
                    }
                    // Nothing leaks outside the map's rectangle.
                    for (int y = 0; y < SCREEN_H; y++) {
                        for (int x = 0; x < SCREEN_W; x++) {
                            boolean inside = x >= left && x < left + size * cellPixels && y >= top && y < top + size * cellPixels;
                            if (!inside) assertEquals(0, image.getRGB(x, y) >>> 24, "Stray pixel at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    @Test
    void framesUseOneFontAndNoShadow() {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        MapFrame frame = new MapFrame(fonts.minimap, advances(), 4, 4, 0, 0);
        frame.drawGrid(new int[16]);
        Component component = frame.finish();
        assertEquals("hud", component.style().font().value());
        assertEquals("bluemapmini", component.style().font().namespace());
        assertEquals(ShadowColor.none(), component.style().shadowColor());
    }

    @Test
    void iconsAndTextStayAlignedAndNetToZero() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        MapFrame frame = new MapFrame(fonts.minimap, advances(), 32, 32, 0, 0);
        frame.drawGrid(new int[32 * 32]);
        for (int icon = 0; icon < HudIcon.COUNT; icon++) frame.drawIcon(icon, icon, icon, 0xFFFFFF);
        frame.drawText("-1234 5678 NE", 2, 33, 0xFFFFFF);
        Component component = frame.finish();
        assertEquals(0, client.totalAdvance(component));
    }

    @Test
    void fullscreenFitsOnCommonScreens() {
        HudConfig config = config();
        int width = config.fullscreen().width() * config.fullscreen().cellPixels();
        int height = config.fullscreen().height() * config.fullscreen().cellPixels();
        // 1280x720 at GUI scale 3 is the tightest common setup.
        assertTrue(width <= 426, "Too wide: " + width);
        assertTrue(height + 20 <= 240, "Too tall: " + height);
    }

    @Test
    void packetSizesStayInBudget() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        int[] grid = new int[32 * 32];
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) grid[z * 32 + x] = round8(terrain(x, z));
        }
        MapFrame mini = new MapFrame(fonts.minimap, advances(), 32, 32, 0, 0);
        mini.drawGrid(grid);
        for (int i = 0; i < 20; i++) mini.drawIcon(HudIcon.PLAYER, i, i, 0x66D9FF);
        mini.drawText("-1234 5678 NE", 20, 33, 0xFFFFFF);
        int[] miniBytes = SimulatedClient.packetBytes(mini.finish());

        int width = config.fullscreen().width(), height = config.fullscreen().height();
        int[] fullGrid = new int[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) fullGrid[z * width + x] = round8(terrain(x, z));
        }
        MapFrame full = new MapFrame(fonts.fullscreen, advances(), width, height, 0, 0);
        full.drawGrid(fullGrid);
        int[] fullBytes = SimulatedClient.packetBytes(full.finish());

        System.out.println("Minimap packet: " + miniBytes[0] + " raw, " + miniBytes[1] + " compressed");
        System.out.println("Fullscreen packet: " + fullBytes[0] + " raw, " + fullBytes[1] + " compressed");
        assertTrue(miniBytes[1] < 5 * 1024, "Minimap update is " + miniBytes[1] + " bytes compressed");
        // The client reads a single packet of up to 8 MiB, compressed or not.
        assertTrue(fullBytes[0] < 2 * 1024 * 1024, "Fullscreen update is " + fullBytes[0] + " bytes");
    }

    @Test
    void headingMatchesMinecraftYaw() {
        assertEquals(0, HudIcon.headingForYaw(180f), "North");
        assertEquals(0, HudIcon.headingForYaw(-180f), "North");
        assertEquals(4, HudIcon.headingForYaw(-90f), "East");
        assertEquals(8, HudIcon.headingForYaw(0f), "South");
        assertEquals(12, HudIcon.headingForYaw(90f), "West");
        assertEquals(0, HudIcon.headingForVector(0, -10), "Up the map is north");
        assertEquals(4, HudIcon.headingForVector(10, 0), "Right on the map is east");
    }

    @Test
    void readoutShowsXyzAndFacingOnAPlateUnderTheMap() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        try (GridCache cache = new GridCache(new TileStore(new SolidSource()), 64, 600_000, Logger.getAnonymousLogger())) {
            FrameBuilder builder = new FrameBuilder(config, fonts, advances(),
                    new MapComposer(cache, config.hud().voidColor(), config.hud().colorStep()), new MarkerService(config.markers()));
            Snapshot with = new Snapshot("world", -1234, -60, 5678, 180f, false, 2, 32, 0, 0, true, false, new int[0], null, false, false);
            Component frame = builder.build(with, new int[32 * 32]).component();
            assertEquals(0, client.totalAdvance(frame), "Plates and readout must leave the frame centred");

            BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SCREEN_H; y++) {
                for (int x = 0; x < SCREEN_W; x++) image.setRGB(x, y, 0xFFECF0F5);
            }
            client.draw(frame, image, BOSS_TEXT_Y);
            int plateY = BOSS_TEXT_Y + fonts.minimap.rowTop(32 + HudFonts.readoutRowOffset(fonts.minimap)) - 1;
            assertEquals(0xFF4A4B4C, image.getRGB(SCREEN_W / 2, plateY), "Dark plate over snow");
            int mapBottom = BOSS_TEXT_Y + fonts.minimap.rowTop(32);
            assertEquals(0xFFECF0F5, image.getRGB(SCREEN_W / 2, mapBottom), "A gap between the map and the plate");

            Snapshot without = new Snapshot("world", -1234, -60, 5678, 180f, false, 2, 32, 0, 0, false, false, new int[0], null, false, false);
            int plain = client.totalAdvance(builder.build(without, new int[32 * 32]).component());
            assertEquals(0, plain);

            Snapshot higher = new Snapshot("world", -1234, -59, 5678, 180f, false, 2, 32, 0, 0, true, false, new int[0], null, false, false);
            assertNotEquals(builder.stateKey(with), builder.stateKey(higher), "Climbing changes the readout");
            Snapshot higherHidden = new Snapshot("world", -1234, -59, 5678, 180f, false, 2, 32, 0, 0, false, false, new int[0], null, false, false);
            assertEquals(builder.stateKey(without), builder.stateKey(higherHidden), "With coordinates hidden, climbing sends nothing");
        }
    }

    @Test
    void readoutFontAdvancesMatchTheClientFormula() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient.Font font = client(config, fonts).fonts.get("hud");
        for (int glyph = 0; glyph < com.extantape.bluemapmini.render.ReadoutFont.glyphCount(); glyph++) {
            assertEquals(font.glyphs().get(fonts.minimap.readoutChar(0, glyph)).advance(), com.extantape.bluemapmini.render.ReadoutFont.advance(glyph), "glyph " + glyph);
        }
    }

    @Test
    void tinyFontAdvancesMatchTheClientFormula() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        SimulatedClient.Font font = client.fonts.get("hud");
        for (int glyph = 0; glyph < TinyFont.glyphCount(); glyph++) {
            assertEquals(font.glyphs().get(fonts.minimap.textChar(0, glyph)).advance(), TinyFont.advance(glyph), "glyph " + glyph);
        }
    }

    // ---- The state key, which decides whether anything is sent ----

    private static final class SolidSource implements TileSource {
        @Override public byte[] readTile(String mapId, int lod, int tileX, int tileZ) throws IOException {
            BufferedImage image = new BufferedImage(501, 1002, BufferedImage.TYPE_INT_ARGB);
            for (int z = 0; z <= 500; z++) {
                for (int x = 0; x <= 500; x++) image.setRGB(x, z, 0xFF000000 | terrain(x + tileX * 500, z + tileZ * 500));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        @Override public long version(String mapId, int lod, int tileX, int tileZ) { return 1; }
        @Override public String readSettings(String mapId) { return null; }
    }

    private static Snapshot snap(int x, int z, float yaw, int[] others) {
        return new Snapshot("world", x, 70, z, yaw, false, 2, 32, 0, 0, true, false, others, null, false, false);
    }

    @Test
    void stateKeyChangesOnlyWhenTheFrameWould() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        try (GridCache cache = new GridCache(new TileStore(new SolidSource()), 64, 600_000, Logger.getAnonymousLogger())) {
            cache.loadNow("world", 2, 0, 0);
            cache.loadNow("world", 2, -1, -1);
            cache.loadNow("world", 2, -1, 0);
            cache.loadNow("world", 2, 0, -1);
            FrameBuilder builder = new FrameBuilder(config, fonts, advances(),
                    new MapComposer(cache, config.hud().voidColor(), config.hud().colorStep()), new MarkerService(config.markers()));

            long base = builder.stateKey(snap(10, 10, 0f, new int[0]));
            assertEquals(base, builder.stateKey(snap(10, 10, 0f, new int[0])));
            assertEquals(base, builder.stateKey(snap(10, 10, 5f, new int[0])), "Small turns stay in one heading bucket");
            assertNotEquals(base, builder.stateKey(snap(10, 10, 90f, new int[0])), "Heading");
            assertNotEquals(base, builder.stateKey(snap(14, 10, 0f, new int[0])), "Moved a cell");
            assertNotEquals(base, builder.stateKey(snap(10, 10, 0f, new int[] {20, 20})), "Another player");

            // Without coordinates, moving inside one cell draws the same picture.
            Snapshot a = new Snapshot("world", 10, 70, 10, 0f, false, 2, 32, 0, 0, false, false, new int[0], null, false, false);
            Snapshot b = new Snapshot("world", 11, 70, 11, 0f, false, 2, 32, 0, 0, false, false, new int[0], null, false, false);
            assertEquals(builder.stateKey(a), builder.stateKey(b));
        }
    }

    @Test
    void coordsOnlyDrawsJustTheReadoutAndNeedsNoMap() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        // No composer and no map: this must work in a dimension BlueMap doesn't map, or with BlueMap away.
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        Snapshot only = coordsOnly(-1234, -60, 5678, 180f, 0, 0);
        FrameBuilder.Built built = builder.build(only, new int[0]);
        Component frame = built.component();
        assertEquals(0, client.totalAdvance(frame), "It must stay centred");

        BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SCREEN_H; y++) {
            for (int x = 0; x < SCREEN_W; x++) image.setRGB(x, y, 0xFFECF0F5);
        }
        client.draw(frame, image, BOSS_TEXT_Y);
        int minX = SCREEN_W, maxX = -1, minY = SCREEN_H, maxY = -1;
        for (int y = 0; y < SCREEN_H; y++) {
            for (int x = 0; x < SCREEN_W; x++) {
                if (image.getRGB(x, y) == 0xFFECF0F5) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        assertEquals(com.extantape.bluemapmini.render.FontLayout.PLATE_HEIGHT, maxY - minY + 1, "Just one plate high");
        assertTrue(Math.abs((minX + maxX) / 2 - SCREEN_W / 2) <= 2, "Centred at the top: " + minX + " to " + maxX);
        assertTrue(maxX - minX < 100, "Only a short line: " + (maxX - minX));
        ImageIO.write(image.getSubimage(SCREEN_W / 2 - 80, 0, 160, 40), "png", previewFile("coords-only.png"));

        long base = builder.stateKey(only);
        assertEquals(base, builder.stateKey(coordsOnly(-1234, -60, 5678, 185f, 0, 0)), "A small turn stays the same");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1234, -60, 5678, 90f, 0, 0)), "Facing");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1233, -60, 5678, 180f, 0, 0)), "X");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1234, -59, 5678, 180f, 0, 0)), "Y");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1234, -60, 5679, 180f, 0, 0)), "Z");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1234, -60, 5678, 180f, 10, 0)), "Offset X");
        assertNotEquals(base, builder.stateKey(coordsOnly(-1234, -60, 5678, 180f, 0, 16)), "Offset Y");

        int[] others = {10, 10};
        Snapshot withOthers = new Snapshot("world", -1234, -60, 5678, 180f, false, 16, 40, 0, 0, false, true, others, new int[] {1, 2}, false, true);
        assertEquals(base, builder.stateKey(withOthers), "Map settings, other players and markers send nothing");
    }

    /** WCAG contrast: every readout colour must reach 4.5:1 against its plate, whatever is behind the plate. */
    @Test
    void readoutStaysReadableOnAnyBackground() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        Component frame = builder.build(coordsOnly(-1234, -60, 5678, 180f, 0, 0), new int[0]).component();
        int[] backgrounds = {0xFFFFFF, 0xECF0F5, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x00FF00, 0xFF0000, 0x0000FF, 0x808080, 0x000000};
        double worst = Double.MAX_VALUE;
        int plateX = -1, plateY = -1;
        for (int background : backgrounds) {
            BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SCREEN_H; y++) {
                for (int x = 0; x < SCREEN_W; x++) image.setRGB(x, y, 0xFF000000 | background);
            }
            client.draw(frame, image, BOSS_TEXT_Y);
            if (plateX < 0) {
                // Found over white, the first backgrounds: the plate's top-left pixel is margin, never text.
                outer:
                for (int y = 0; y < SCREEN_H; y++) {
                    for (int x = 0; x < SCREEN_W; x++) {
                        if ((image.getRGB(x, y) & 0xFFFFFF) != background) {
                            plateX = x;
                            plateY = y;
                            break outer;
                        }
                    }
                }
            }
            int plate = image.getRGB(plateX, plateY) & 0xFFFFFF;
            for (int text : new int[] {0xFFFFFF, 0xBEC6D2}) {
                double ratio = contrast(text, plate);
                worst = Math.min(worst, ratio);
                assertTrue(ratio >= 4.5, String.format("%06X text on the plate over %06X is only %.2f:1", text, background, ratio));
            }
        }
        System.out.printf("Lowest readout contrast: %.2f:1%n", worst);
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        double sum = 0;
        double[] weights = {0.0722, 0.7152, 0.2126};
        for (int i = 0; i < 3; i++) {
            double c = (rgb >> (i * 8) & 255) / 255.0;
            sum += weights[i] * (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        return sum;
    }

    private static Snapshot coordsOnly(int x, int y, int z, float yaw, int offsetX, int offsetY) {
        return new Snapshot("", x, y, z, yaw, false, 2, 32, offsetX, offsetY, true, true, new int[0], null, false, true);
    }

    private static File previewFile(String name) {
        File folder = new File("build/preview");
        folder.mkdirs();
        return new File(folder, name);
    }

    /** Writes previews to build/preview for checking the look by eye, and times frame building. */
    @Test
    void writesPreviewsAndMeetsTheFrameBudget() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        File folder = new File("build/preview");
        folder.mkdirs();
        try (GridCache cache = new GridCache(new TileStore(new SolidSource()), 64, 600_000, Logger.getAnonymousLogger())) {
            for (int zoom : new int[] {2, 8}) {
                for (int z = -1; z <= 0; z++) {
                    for (int x = -1; x <= 0; x++) cache.loadNow("world", zoom, x, z);
                }
            }
            FrameBuilder builder = new FrameBuilder(config, fonts, advances(),
                    new MapComposer(cache, config.hud().voidColor(), config.hud().colorStep()), new MarkerService(config.markers()));

            int[] others = {30, 12, -24, 40, 8, -50};
            int[] grid = new int[config.fullscreen().width() * config.fullscreen().height()];
            Snapshot mini = new Snapshot("world", 4, 70, 4, -135f, false, 2, 32, 0, 0, true, false, others, new int[] {-20, -20}, false, false);
            preview(client, builder.build(mini, grid).component(), new File(folder, "minimap.png"));
            previewOn(client, builder.build(mini, grid).component(), new File(folder, "minimap-snow.png"), 0xFFECF0F5);

            Snapshot full = new Snapshot("world", 4, 70, 4, -135f, true, 8, 32, 0, 0, true, false, others, new int[] {-20, -20}, false, false);
            preview(client, builder.build(full, grid).component(), new File(folder, "fullscreen.png"));
            previewOn(client, builder.build(full, grid).component(), new File(folder, "fullscreen-snow.png"), 0xFFECF0F5);

            Snapshot pattern = new Snapshot("world", 4, 70, 4, 180f, false, 2, 32, 0, 0, true, false, new int[0], null, true, false);
            preview(client, builder.build(pattern, grid).component(), new File(folder, "pattern.png"));

            // Warm up, then time the real work on one background thread.
            for (int i = 0; i < 2000; i++) builder.build(mini, grid);
            long start = System.nanoTime();
            int runs = 2000;
            for (int i = 0; i < runs; i++) builder.build(mini, grid);
            double micros = (System.nanoTime() - start) / 1000.0 / runs;
            System.out.println("Minimap frame build: " + micros + " us");
            assertTrue(micros < 1000, "Frame build took " + micros + " us");
        }
    }

    private static void preview(SimulatedClient client, Component component, File file) throws IOException {
        previewOn(client, component, file, 0xFF6A8CAF);
    }

    private static void previewOn(SimulatedClient client, Component component, File file, int background) throws IOException {
        BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SCREEN_H; y++) {
            for (int x = 0; x < SCREEN_W; x++) image.setRGB(x, y, background);
        }
        client.draw(component, image, BOSS_TEXT_Y);
        BufferedImage scaled = new BufferedImage(SCREEN_W * 2, SCREEN_H * 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SCREEN_H * 2; y++) {
            for (int x = 0; x < SCREEN_W * 2; x++) scaled.setRGB(x, y, image.getRGB(x / 2, y / 2));
        }
        ImageIO.write(scaled, "png", file);
    }

    @Test
    void randomGridsNeverDrift() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = client(config, fonts);
        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            int width = 16 + random.nextInt(81), height = 16 + random.nextInt(39);
            int[] grid = new int[width * height];
            int palette = 1 + random.nextInt(8);
            int[] colors = new int[palette];
            Arrays.setAll(colors, c -> random.nextInt(0x1000000));
            for (int j = 0; j < grid.length; ) {
                int run = 1 + random.nextInt(100);
                int color = colors[random.nextInt(palette)];
                for (int k = 0; k < run && j < grid.length; k++) grid[j++] = color;
            }
            MapFrame frame = new MapFrame(fonts.fullscreen, advances(), width, height, 0, 0);
            frame.drawGrid(grid);
            assertEquals(0, client.totalAdvance(frame.finish()));
        }
    }
}
