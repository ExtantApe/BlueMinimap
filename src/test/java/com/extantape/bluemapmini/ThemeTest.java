package com.extantape.bluemapmini;

import com.extantape.bluemapmini.config.HudConfig;
import com.extantape.bluemapmini.hud.FrameBuilder;
import com.extantape.bluemapmini.hud.Snapshot;
import com.extantape.bluemapmini.markers.MarkerService;
import com.extantape.bluemapmini.pack.PackBuilder;
import com.extantape.bluemapmini.render.HudFonts;
import com.extantape.bluemapmini.render.IconSet;
import com.extantape.bluemapmini.theme.Theme;
import com.extantape.bluemapmini.theme.ThemeContrast;
import com.extantape.bluemapmini.theme.ThemeSet;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The themes, their readability, and the clock. */
class ThemeTest {
    private static final int SCREEN_W = 480;
    private static final int SCREEN_H = 120;
    private static final int TEXT_Y = 3;

    private static HudConfig config() {
        return HudConfig.from(YamlConfiguration.loadConfiguration(new InputStreamReader(
                ThemeTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
    }

    private static int[] advances() {
        return new IconSet(null, Logger.getAnonymousLogger()).advances();
    }

    private static Snapshot readout(int theme, int clockMinutes, int x) {
        return new Snapshot("", x, 64, -120, 135f, false, 2, 32, 0, 0, true, true, new int[0], null, false, true, theme, clockMinutes);
    }

    private static BufferedImage draw(SimulatedClient client, Component frame, int background) {
        BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SCREEN_H; y++) for (int x = 0; x < SCREEN_W; x++) image.setRGB(x, y, 0xFF000000 | background);
        client.draw(frame, image, TEXT_Y);
        return image;
    }

    /** Columns that differ from the background: the plate's left and right edges. */
    private static int[] extent(BufferedImage image, int background) {
        int left = Integer.MAX_VALUE, right = -1;
        for (int y = 0; y < SCREEN_H; y++) {
            for (int x = 0; x < SCREEN_W; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != background) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        return new int[] {left, right};
    }

    @Test
    void theFourBuiltInThemesLoad() {
        List<String> warnings = new ArrayList<>();
        ThemeSet themes = ThemeSet.of(ThemeSet.builtIn(), "classic", warnings::add);
        assertEquals(List.of("classic", "desert", "galaxy", "high-contrast"), themes.themes().stream().map(Theme::id).toList());
        assertEquals(List.of(), warnings, "The built-in themes load without warnings");
        assertEquals("classic", themes.defaultTheme().id());
        for (Theme theme : themes.themes()) assertEquals(8, theme.clockFrames().size(), theme.id() + " has a frame every three hours");
    }

    /** The rule from ThemeContrast, for every theme that ships. A new theme that fails it fails this test. */
    @Test
    void everyThemePassesTheContrastRule() {
        for (Theme theme : ThemeSet.of(ThemeSet.builtIn(), "classic", w -> {}).themes()) {
            List<String> problems = ThemeContrast.problems(theme);
            assertEquals(List.of(), problems, theme.id());
            System.out.printf("%s: lowest text contrast %.2f:1%n", theme.id(), ThemeContrast.lowestText(theme));
        }
    }

    /** Checks the rule's maths against real drawing: the plate the client draws is the colour the rule assumed. */
    @Test
    void theDrawnPlateMatchesTheContrastMaths() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = new SimulatedClient(PackBuilder.build(config, fonts, new IconSet(null, Logger.getAnonymousLogger())).zip());
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        for (int t = 0; t < fonts.themes.size(); t++) {
            Theme theme = fonts.themes.get(t);
            Component frame = builder.build(readout(t, 12 * 60, -1234), new int[0]).component();
            for (int background : ThemeContrast.BACKGROUNDS) {
                BufferedImage image = draw(client, frame, background);
                int[] edge = extent(image, background);
                // The plate's left edge is padding, never text, so its middle row is plain plate.
                int plate = -1;
                for (int y = 0; y < SCREEN_H && plate < 0; y++) {
                    if ((image.getRGB(edge[0], y) & 0xFFFFFF) != background) plate = image.getRGB(edge[0], y + 3) & 0xFFFFFF;
                }
                int expected = ThemeContrast.plateOver(theme, background);
                for (int shift = 0; shift <= 16; shift += 8) {
                    assertTrue(Math.abs((plate >> shift & 255) - (expected >> shift & 255)) <= 1,
                            String.format("%s plate over %06X: drew %06X, expected %06X", theme.id(), background, plate, expected));
                }
            }
        }
    }

    @Test
    void theClockShowsTheRightFrameForTheTime() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = new SimulatedClient(PackBuilder.build(config, fonts, new IconSet(null, Logger.getAnonymousLogger())).zip());
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        int desert = fonts.themes.indexOf("desert");
        Theme theme = fonts.themes.get(desert);
        int sun = 0xFFD54A, moon = 0xF4F1E4;
        BufferedImage noon = draw(client, builder.build(readout(desert, 12 * 60, 5), new int[0]).component(), 0x000000);
        BufferedImage midnight = draw(client, builder.build(readout(desert, 0, 5), new int[0]).component(), 0x000000);
        assertTrue(count(noon, sun) > 0 && count(noon, moon) == 0, "The sun at noon, and no moon");
        assertTrue(count(midnight, moon) > 0 && count(midnight, sun) == 0, "The moon at midnight, and no sun");
        assertEquals(4, theme.clockFrame(12 * 60), "Frame 4 covers 12:00 to 15:00");
        assertEquals(7, theme.clockFrame(23 * 60 + 59));
        BufferedImage none = draw(client, builder.build(readout(desert, -1, 5), new int[0]).component(), 0x000000);
        assertEquals(0, count(none, sun) + count(none, moon), "No clock when it's hidden");
    }

    private static int count(BufferedImage image, int rgb) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) n++;
        return n;
    }

    /** Fixed-width digits: as the time changes, the line and its plate stay exactly where they are. */
    @Test
    void theTimeNeverMovesTheLine() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = new SimulatedClient(PackBuilder.build(config, fonts, new IconSet(null, Logger.getAnonymousLogger())).zip());
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        Set<String> extents = new HashSet<>();
        for (int minutes : new int[] {0, 11 * 60 + 11, 8 * 60 + 8, 20 * 60, 23 * 60 + 55, 1 * 60 + 10}) {
            Component frame = builder.build(readout(0, minutes, -1234), new int[0]).component();
            assertEquals(0, client.totalAdvance(frame), "Every frame ends where it started");
            int[] edge = extent(draw(client, frame, 0xFFFFFF), 0xFFFFFF);
            extents.add(edge[0] + ".." + edge[1]);
        }
        assertEquals(1, extents.size(), "The plate never moves: " + extents);
        assertEquals("07:05", FrameBuilder.time(7 * 60 + 5));
        assertEquals("00:00", FrameBuilder.time(24 * 60));
    }

    /**
     * The pack only gives the fullscreen map's clock and plate styles to the row its coordinate line uses. Every
     * theme's fullscreen frame, and every minimap offset, must still find all its glyphs: the simulated client
     * fails on a missing one, as the real client would draw a box.
     */
    @Test
    void everyThemeFindsItsGlyphsInEveryPlace() throws IOException {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        SimulatedClient client = new SimulatedClient(PackBuilder.build(config, fonts, new IconSet(null, Logger.getAnonymousLogger())).zip());
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        for (int t = 0; t < fonts.themes.size(); t++) {
            for (int minutes : new int[] {0, 6 * 60, 12 * 60, 18 * 60 + 30}) {
                Snapshot full = new Snapshot("", 10, 64, -10, 0f, true, 8, 32, 0, 0, true, false, new int[0], null, true, false, t, minutes);
                assertEquals(0, client.totalAdvance(builder.build(full, new int[96 * 54]).component()), "fullscreen, theme " + t);
                for (int size : new int[] {24, 32, 40}) {
                    for (int offsetY : new int[] {0, 16, 64}) {
                        Snapshot mini = new Snapshot("", 10, 64, -10, 0f, false, 2, size, 0, offsetY, true, false, new int[0], null, true, false, t, minutes);
                        assertEquals(0, client.totalAdvance(builder.build(mini, new int[size * size]).component()), "minimap, theme " + t);
                        Snapshot coords = new Snapshot("", 10, 64, -10, 0f, false, 2, size, 0, offsetY, true, false, new int[0], null, false, true, t, minutes);
                        assertEquals(0, client.totalAdvance(builder.build(coords, new int[0]).component()), "coordinates only, theme " + t);
                    }
                }
            }
        }
    }

    @Test
    void theClockAndThemeChangeTheStateKey() {
        HudConfig config = config();
        HudFonts fonts = new HudFonts(config);
        FrameBuilder builder = new FrameBuilder(config, fonts, advances(), null, new MarkerService(config.markers()));
        long base = builder.stateKey(readout(0, 600, 5));
        assertNotEquals(base, builder.stateKey(readout(0, 605, 5)), "The clock moving");
        assertNotEquals(base, builder.stateKey(readout(1, 600, 5)), "Another theme");
        assertNotEquals(base, builder.stateKey(readout(0, -1, 5)), "Hiding the clock");
        assertEquals(base, builder.stateKey(readout(0, 600, 5)));
    }

    @Test
    void aBrokenOrUnreadableThemeIsReportedNotFatal() {
        String good = ThemeSet.builtIn().get("classic");
        String grey = good.replace("\"text\": \"#FFFFFF\"", "\"text\": \"#555555\"");
        List<String> warnings = new ArrayList<>();
        ThemeSet themes = ThemeSet.of(Map.of("classic", good, "grey", grey, "broken", "{ not json", "Bad Name", good), "nope", warnings::add);
        assertEquals(List.of("classic", "grey"), themes.themes().stream().map(Theme::id).toList(), "The readable and the merely hard-to-read load");
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Theme grey is hard to read")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Theme broken couldn't be loaded")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Theme Bad Name couldn't be loaded")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no theme called nope")), String.valueOf(warnings));
        assertEquals("classic", themes.defaultTheme().id(), "An unknown default falls back to classic");
        assertFalse(ThemeContrast.problems(themes.get(1)).isEmpty());
    }

    @Test
    void clockStepsDivideTheHour() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                ThemeTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        for (int[] pair : new int[][] {{1, 1}, {5, 5}, {7, 10}, {25, 30}, {0, 1}, {200, 60}}) {
            yaml.set("hud.clock.step-minutes", pair[0]);
            assertEquals(pair[1], HudConfig.from(yaml).hud().clockStepMinutes(), "step " + pair[0]);
        }
    }
}
