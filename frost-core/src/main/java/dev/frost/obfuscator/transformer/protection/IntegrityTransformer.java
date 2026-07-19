package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public class IntegrityTransformer extends Transformer {

    @Override
    public String getName() {
        return "integrity";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public void transform(Context context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder index = new StringBuilder();

            for (Map.Entry<String, byte[]> entry : new TreeMap<>(context.jar().getOriginalClassBytes()).entrySet()) {
                String hash = hash(entry.getValue());
                index.append("class ").append(entry.getKey()).append(' ').append(hash).append('\n');
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue());
            }

            for (Map.Entry<String, byte[]> entry : new TreeMap<>(context.resources()).entrySet()) {
                String name = entry.getKey();
                if (name.startsWith("META-INF/frostfuscator/")) {
                    continue;
                }
                String hash = hash(entry.getValue());
                index.append("resource ").append(name).append(' ').append(hash).append('\n');
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue());
            }

            index.append("digest ").append(HexFormat.of().formatHex(digest.digest())).append('\n');
            context.jar().putResource("META-INF/frostfuscator/integrity.sha256", index.toString().getBytes(StandardCharsets.UTF_8));
            context.stats().add("integrityEntries", index.toString().lines().count());
            log("Wrote integrity index");
        } catch (Exception e) {
            throw new RuntimeException("Failed to write integrity metadata", e);
        }
    }

    private String hash(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
