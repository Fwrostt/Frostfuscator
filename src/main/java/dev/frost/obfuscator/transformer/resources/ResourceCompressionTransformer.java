package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class ResourceCompressionTransformer extends Transformer {

    @Override
    public String getName() {
        return "resource-compression";
    }

    @Override
    public String getCategory() {
        return "Resources";
    }

    @Override
    public void transform(Context context) {
        boolean removeOriginals = getBooleanOption(context, "remove-originals", true);
        String prefix = context.config().getOption("output-prefix", "META-INF/frostfuscator/resources/");
        List<String> compressed = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : new ArrayList<>(context.resources().entrySet())) {
            String name = entry.getKey();
            if (!shouldCompress(name, prefix)) {
                continue;
            }

            byte[] gz = gzip(entry.getValue());
            String compressedName = prefix + name + ".gz";
            context.jar().putResource(compressedName, gz);
            compressed.add(name + "=" + compressedName);

            if (removeOriginals) {
                context.jar().removeResource(name);
            }
        }

        if (!compressed.isEmpty()) {
            context.jar().putResource("META-INF/frostfuscator/resource-index.txt",
                    String.join("\n", compressed).getBytes(StandardCharsets.UTF_8));
        }
        context.stats().add("compressedResources", compressed.size());
        log("Compressed {} resources", compressed.size());
    }

    private boolean shouldCompress(String name, String prefix) {
        String lower = name.toLowerCase();
        if (name.startsWith(prefix)
                || name.startsWith("META-INF/frostfuscator/")
                || name.equals("META-INF/MANIFEST.MF")
                || name.endsWith("/")
                || lower.endsWith(".class")) {
            return false;
        }
        return lower.endsWith(".json")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".txt")
                || lower.endsWith(".properties")
                || lower.endsWith(".xml")
                || lower.endsWith(".png");
    }

    private byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(data);
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress resource", e);
        }
    }

    private boolean getBooleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
