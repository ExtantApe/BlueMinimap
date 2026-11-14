package com.extantape.blueminimap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Carries a server's files over from the old plugin folder after a rename: from BlueMinimap (D194, 2026-11-14) or from BlueMapHUD (2026-11-09). */
final class Migration {
    private Migration() {}

    /**
     * Copies {@code old} to {@code now} if {@code now} doesn't exist and {@code old} is a folder. Copies into a
     * temporary sibling first and renames it into place, so a crash halfway leaves no half-filled folder.
     *
     * @return a line for the log saying what happened, or null if there was nothing to do
     */
    static String copyIfMissing(Path old, Path now) {
        if (Files.exists(now) || !Files.isDirectory(old)) return null;
        Path tmp = now.resolveSibling(now.getFileName() + ".migrating");
        try {
            if (Files.exists(tmp)) deleteTree(tmp);
            try (Stream<Path> all = Files.walk(old)) {
                all.forEach(p -> {
                    Path to = tmp.resolve(old.relativize(p).toString());
                    try {
                        if (Files.isDirectory(p)) Files.createDirectories(to);
                        else Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
            Files.move(tmp, now, StandardCopyOption.ATOMIC_MOVE);
            return "Copied plugins/" + old.getFileName() + " to plugins/" + now.getFileName()
                    + " (" + old.getFileName() + " is now called BlueMapMini). The old folder was left untouched; delete it once you're happy.";
        } catch (IOException | UncheckedIOException e) {
            return "Couldn't copy plugins/" + old.getFileName() + " to plugins/" + now.getFileName() + ": " + e.getMessage()
                    + ". Copy it by hand while the server is stopped, or BlueMapMini starts with a fresh config.";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
