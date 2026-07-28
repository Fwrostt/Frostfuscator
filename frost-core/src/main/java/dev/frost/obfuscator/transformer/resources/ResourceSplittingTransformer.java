package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ResourceSplittingTransformer extends Transformer {
    private static final String INDEX = "META-INF/frostfuscator/splits/index.tsv";
    private static final String RUNTIME_CLASS = "dev/frost/runtime/SplitResourceLoader";

    @Override
    public String getName() {
        return "resource-splitting";
    }

    @Override
    public String getCategory() {
        return "Resources";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int partSize = intOption(context, "part-size", 32768, 256, 4 * 1024 * 1024);
        int threshold = intOption(context, "minimum-size", 65536, 1, Integer.MAX_VALUE);
        boolean removeOriginals = booleanOption(context, "remove-originals", true);
        List<String> index = new ArrayList<>();
        Set<String> preservedPackages = ResourceCompatibility.preservedPackages(context);
        int resources = 0;
        int parts = 0;
        for (Map.Entry<String, byte[]> entry : new ArrayList<>(context.resources().entrySet())) {
            String name = entry.getKey();
            byte[] data = entry.getValue();
            if (!eligible(name) || data.length < threshold
                    || ResourceCompatibility.isPreservedLibraryResource(name, preservedPackages)) continue;
            String id = shortHash(name.getBytes(StandardCharsets.UTF_8));
            String prefix = "META-INF/frostfuscator/splits/" + id + "/";
            int count = (data.length + partSize - 1) / partSize;
            for (int i = 0; i < count; i++) {
                int start = i * partSize;
                context.jar().putResource(prefix + i + ".bin",
                        Arrays.copyOfRange(data, start, Math.min(data.length, start + partSize)));
            }
            index.add(encoded(name) + "\t" + prefix + "\t" + count + "\t" + hash(data));
            if (removeOriginals) context.jar().removeResource(name);
            resources++;
            parts += count;
        }
        if (!index.isEmpty()) {
            context.jar().putResource(INDEX, String.join("\n", index).getBytes(StandardCharsets.UTF_8));
            injectRuntime(context, RUNTIME_CLASS);
        }
        context.stats().add("splitResources", resources);
        context.stats().add("resourceParts", parts);
        log("Split {} resources into {} parts", resources, parts);
    }

    private boolean eligible(String name) {
        return !name.startsWith("META-INF/frostfuscator/")
                && !name.equals("META-INF/MANIFEST.MF")
                && !name.endsWith("/") && !name.endsWith(".class");
    }

    static void injectRuntime(Context context, String internalName) {
        if (context.pool().contains(internalName)) return;
        try (InputStream input = ResourceSplittingTransformer.class.getResourceAsStream("/" + internalName + ".class")) {
            if (input == null) throw new IllegalStateException("Missing embedded runtime " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, ClassReader.EXPAND_FRAMES);
            context.pool().addClass(node.name, node);
            context.pool().markDirty(node.name);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not inject resource runtime", exception);
        }
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String shortHash(byte[] data) {
        return hash(data).substring(0, 16);
    }

    private String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        try {
            Object value = context.config().getOptions().get(key);
            int parsed = value == null ? fallback : Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
