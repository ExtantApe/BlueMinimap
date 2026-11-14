package com.extantape.bluemapmini.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** The icon artwork and the advance the client will compute for each icon. */
public final class IconSet {
    private final BufferedImage[] images = new BufferedImage[HudIcon.COUNT];
    private final int[] advances = new int[HudIcon.COUNT];

    /** Loads icons, preferring a same-named 9x9 PNG from the override folder when there is one. */
    public IconSet(Path overrideFolder, Logger logger) {
        for (int icon = 0; icon < HudIcon.COUNT; icon++) {
            BufferedImage image = overrideFolder == null ? null : readOverride(overrideFolder, icon, logger);
            images[icon] = image != null ? image : HudIcon.draw(icon);
            advances[icon] = litWidth(images[icon]) + 1;
        }
    }

    public BufferedImage image(int icon) {
        return images[icon];
    }

    public int[] advances() {
        return advances;
    }

    private static BufferedImage readOverride(Path folder, int icon, Logger logger) {
        Path file = folder.resolve(HudIcon.fileName(icon));
        if (!Files.isRegularFile(file)) return null;
        try {
            BufferedImage source = ImageIO.read(file.toFile());
            if (source == null || source.getWidth() != HudIcon.SIZE || source.getHeight() != HudIcon.SIZE) {
                logger.warning("Ignoring " + file.getFileName() + ": icons must be " + HudIcon.SIZE + "x" + HudIcon.SIZE + " PNGs.");
                return null;
            }
            // Copied by pixel: Graphics2D needs AWT's font libraries, which many server JREs don't have.
            BufferedImage image = new BufferedImage(HudIcon.SIZE, HudIcon.SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < HudIcon.SIZE; y++) {
                for (int x = 0; x < HudIcon.SIZE; x++) image.setRGB(x, y, source.getRGB(x, y));
            }
            return image;
        } catch (IOException e) {
            logger.warning("Ignoring " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /** The client measures a glyph from its left edge to its rightmost non-transparent pixel. */
    private static int litWidth(BufferedImage image) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) >>> 24 != 0) return x + 1;
            }
        }
        return 0;
    }
}
