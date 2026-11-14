package com.extantape.bluemapmini.theme;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The themes the pack is built with: the built-in ones from the jar's {@code themes/} folder, plus or
 * replaced by any in the server's {@code plugins/BlueMapMini/themes/}. The pack holds every theme, so a
 * player can switch with {@code /hud theme} without a new download.
 */
public final class ThemeSet {
    public static final String FALLBACK = "classic";
    /** Keeps the pack small and the glyph tables within their code point range. */
    public static final int MAX_THEMES = 8;

    private final List<Theme> themes;
    private final int defaultIndex;
    private final List<Integer> plateOpacities;
    private final int[] plateVariant;
    private final int[] clockOffset;
    private final int clockGlyphs;

    private ThemeSet(List<Theme> themes, int defaultIndex) {
        this.themes = List.copyOf(themes);
        this.defaultIndex = defaultIndex;
        List<Integer> opacities = new ArrayList<>();
        plateVariant = new int[themes.size()];
        clockOffset = new int[themes.size()];
        int glyphs = 0;
        for (int i = 0; i < themes.size(); i++) {
            Theme theme = themes.get(i);
            if (!opacities.contains(theme.plateOpacity())) opacities.add(theme.plateOpacity());
            plateVariant[i] = opacities.indexOf(theme.plateOpacity());
            clockOffset[i] = glyphs;
            glyphs += theme.clockFrames().size();
        }
        plateOpacities = List.copyOf(opacities);
        clockGlyphs = glyphs;
    }

    /**
     * Builds the set from theme files by id. A file that can't be read is reported and left out. The default
     * falls back to {@value #FALLBACK} when it's missing.
     */
    public static ThemeSet of(Map<String, String> sources, String defaultId, Consumer<String> warn) {
        List<Theme> themes = new ArrayList<>();
        for (Map.Entry<String, String> source : new TreeMap<>(sources).entrySet()) {
            if (themes.size() == MAX_THEMES) {
                warn.accept("Only " + MAX_THEMES + " themes fit in the pack, so " + source.getKey() + " was left out.");
                continue;
            }
            try {
                Theme theme = Theme.parse(source.getKey(), source.getValue());
                List<String> problems = ThemeContrast.problems(theme);
                if (!problems.isEmpty()) {
                    warn.accept("Theme " + theme.id() + " is hard to read in places: " + problems.getFirst()
                            + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : ""));
                }
                themes.add(theme);
            } catch (IllegalArgumentException e) {
                warn.accept("Theme " + source.getKey() + " couldn't be loaded: " + e.getMessage());
            }
        }
        if (themes.isEmpty()) throw new IllegalStateException("No themes could be loaded");
        String wanted = defaultId == null ? FALLBACK : defaultId.toLowerCase(Locale.ROOT);
        int index = indexOf(themes, wanted);
        if (index < 0) {
            warn.accept("There's no theme called " + wanted + ", so the default is " + FALLBACK + ".");
            index = Math.max(0, indexOf(themes, FALLBACK));
        }
        return new ThemeSet(themes, index);
    }

    /** The built-in themes, with any from {@code folder} added or replacing them. */
    public static ThemeSet load(Path folder, String defaultId, Consumer<String> warn) {
        Map<String, String> sources = new LinkedHashMap<>(builtIn());
        if (folder != null && Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    try {
                        sources.put(name.substring(0, name.length() - 5), Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        warn.accept("Couldn't read " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                warn.accept("Couldn't list " + folder + ": " + e.getMessage());
            }
        }
        return of(sources, defaultId, warn);
    }

    /** Every {@code themes/*.json} that ships in the jar, by id. Adding a file there adds a theme. */
    public static Map<String, String> builtIn() {
        Map<String, String> sources = new TreeMap<>();
        try {
            for (URL url : Collections.list(ThemeSet.class.getClassLoader().getResources("themes/"))) {
                if (url.getProtocol().equals("jar")) {
                    JarURLConnection connection = (JarURLConnection) url.openConnection();
                    // Other plugins' jars can be on the classpath too. Only read this plugin's own.
                    if (!sameFile(connection.getJarFileURL(), ThemeSet.class.getProtectionDomain().getCodeSource().getLocation())) continue;
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) {
                        for (var entry : Collections.list(jar.entries())) {
                            String name = entry.getName();
                            if (name.startsWith("themes/") && name.endsWith(".json") && name.indexOf('/', 7) < 0) {
                                try (InputStream in = jar.getInputStream(entry)) {
                                    sources.put(name.substring(7, name.length() - 5), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                } else if (url.getProtocol().equals("file")) {
                    try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                        for (Path file : files.toList()) {
                            String name = file.getFileName().toString();
                            if (name.endsWith(".json")) sources.put(name.substring(0, name.length() - 5), Files.readString(file));
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Couldn't read the built-in themes", e);
        }
        return sources;
    }

    private static boolean sameFile(URL a, URL b) {
        try {
            return Path.of(a.toURI()).toRealPath().equals(Path.of(b.toURI()).toRealPath());
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return false;
        }
    }

    private static int indexOf(List<Theme> themes, String id) {
        for (int i = 0; i < themes.size(); i++) if (themes.get(i).id().equals(id)) return i;
        return -1;
    }

    public List<Theme> themes() { return themes; }
    public int size() { return themes.size(); }
    public Theme get(int index) { return themes.get(index); }
    public int defaultIndex() { return defaultIndex; }
    public Theme defaultTheme() { return themes.get(defaultIndex); }

    /** The index of a theme by id, or -1. */
    public int indexOf(String id) {
        return id == null ? -1 : indexOf(themes, id.toLowerCase(Locale.ROOT));
    }

    /** A player's theme, or the server's default when they haven't chosen one or theirs is gone. */
    public int resolve(String id) {
        int index = indexOf(id);
        return index < 0 ? defaultIndex : index;
    }

    /** The distinct plate opacities, in order. The pack has one set of plate glyphs for each. */
    public List<Integer> plateOpacities() { return plateOpacities; }
    public int plateVariant(int themeIndex) { return plateVariant[themeIndex]; }

    /** The first clock glyph of a theme, counting every theme's frames in order. */
    public int clockOffset(int themeIndex) { return clockOffset[themeIndex]; }
    public int clockGlyphs() { return clockGlyphs; }
}
