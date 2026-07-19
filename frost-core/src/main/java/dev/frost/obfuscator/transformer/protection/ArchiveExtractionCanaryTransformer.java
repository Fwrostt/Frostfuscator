package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

public final class ArchiveExtractionCanaryTransformer extends Transformer {
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;

    @Override
    public String getName() {
        return "archive-extraction-canary";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        int count = intOption(context, "count", 2, 0, 32);
        int expandedSize = intOption(context, "expanded-size", 1024 * 1024, 0, 8 * 1024 * 1024);
        int total = Math.min(MAX_TOTAL_BYTES, count * expandedSize);
        int perFile = count == 0 ? 0 : total / count;
        long seed = longOption(context, "seed", 0L);
        Random random = seed == 0L ? new Random() : new Random(seed);
        StringBuilder manifest = new StringBuilder();

        for (int i = 0; i < count && perFile > 0; i++) {
            byte[] data = canaryData(perFile, i, random);
            String name = "META-INF/frostfuscator/canary/" + i + "-" + shortHash(data) + ".dat";
            context.jar().putResource(name, data);
            manifest.append(name).append('\t').append(data.length).append('\t').append(hash(data)).append('\n');
        }
        if (!manifest.isEmpty()) {
            context.jar().putResource("META-INF/frostfuscator/canary/index.tsv",
                    manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        context.stats().add("archiveCanaryResources", count);
        context.stats().add("archiveCanaryExpandedBytes", perFile * count);
        log("Added {} bounded extraction canary resources ({} bytes before jar compression)", count, perFile * count);
    }

    private byte[] canaryData(int size, int index, Random random) {
        byte[] data = new byte[size];
        byte[] pattern = ("FROST-CANARY-" + index + '-' + random.nextInt()).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < data.length; i++) {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    private String shortHash(byte[] data) {
        return hash(data).substring(0, 16);
    }

    private String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
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

    private long longOption(Context context, String key, long fallback) {
        try {
            Object value = context.config().getOptions().get(key);
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
