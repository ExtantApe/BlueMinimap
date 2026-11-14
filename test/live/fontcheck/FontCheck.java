import com.google.gson.*;
import com.mojang.blaze3d.font.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Feeds a resource pack through the game's own metadata and font loading code, without starting the game,
 * and writes each glyph's advance as JSON. Run by the live suite when BMH_CLIENT_CP is set:
 * {@code java -cp <client jar and libraries> FontCheck.java <pack.zip> <out.json>}
 */
public class FontCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, byte[]> files = new HashMap<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            for (ZipEntry e : Collections.list(zip.entries())) files.put(e.getName(), zip.getInputStream(e).readAllBytes());
        }
        JsonObject meta = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        PackMetadataSection section = PackMetadataSection.CLIENT_TYPE.codec().parse(JsonOps.INSTANCE, meta.get("pack")).getOrThrow();
        System.out.println("PACK_META supported=" + section.supportedFormats());

        ResourceManager manager = (ResourceManager) Proxy.newProxyInstance(FontCheck.class.getClassLoader(), new Class<?>[] {ResourceManager.class}, (proxy, method, a) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, a);
            if (method.getName().equals("getResource")) {
                Identifier id = (Identifier) a[0];
                byte[] data = files.get("assets/" + id.getNamespace() + "/" + id.getPath());
                return data == null ? Optional.empty() : Optional.of(new Resource(null, () -> new ByteArrayInputStream(data)));
            }
            throw new UnsupportedOperationException(method.getName());
        });

        StringBuilder out = new StringBuilder("{");
        boolean firstFont = true;
        for (String path : new TreeSet<>(files.keySet())) {
            if (!path.startsWith("assets/bluemapmini/font/")) continue;
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            JsonArray providers = JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("providers");
            int glyphs = 0;
            out.append(firstFont ? "" : ",").append('"').append(name).append("\":{");
            firstFont = false;
            boolean first = true;
            for (JsonElement element : providers) {
                GlyphProviderDefinition definition = GlyphProviderDefinition.MAP_CODEC.codec().parse(JsonOps.INSTANCE, element).getOrThrow();
                GlyphProvider provider = definition.unpack().left().orElseThrow().load(manager);
                for (int codepoint : provider.getSupportedGlyphs()) {
                    float advance = provider.getGlyph(codepoint).info().getAdvance();
                    out.append(first ? "" : ",").append('"').append(codepoint).append("\":").append(advance);
                    first = false;
                    glyphs++;
                }
            }
            out.append('}');
            System.out.println("FONT " + name + " providers=" + providers.size() + " glyphs=" + glyphs + " loaded by the game's code");
        }
        out.append('}');
        Files.writeString(Path.of(args[1]), out.toString());
        System.out.println("OK");
        System.exit(0);
    }
}
