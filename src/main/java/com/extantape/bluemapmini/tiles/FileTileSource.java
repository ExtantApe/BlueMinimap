package com.extantape.bluemapmini.tiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads tiles straight from BlueMap's file storage. */
public final class FileTileSource implements TileSource {
    private final Path mapsRoot;

    public FileTileSource(Path mapsRoot) {
        this.mapsRoot = mapsRoot;
    }

    @Override
    public byte[] readTile(String mapId, int lod, int tileX, int tileZ) throws IOException {
        try {
            return Files.readAllBytes(path(mapId, lod, tileX, tileZ));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    @Override
    public long version(String mapId, int lod, int tileX, int tileZ) throws IOException {
        try {
            return Files.getLastModifiedTime(path(mapId, lod, tileX, tileZ)).toMillis();
        } catch (NoSuchFileException e) {
            return 0;
        }
    }

    @Override
    public String readSettings(String mapId) throws IOException {
        try {
            return Files.readString(mapsRoot.resolve(mapId).resolve("settings.json"), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private Path path(String mapId, int lod, int tileX, int tileZ) {
        return mapsRoot.resolve(mapId).resolve(TileSource.tilePath(lod, tileX, tileZ));
    }
}
