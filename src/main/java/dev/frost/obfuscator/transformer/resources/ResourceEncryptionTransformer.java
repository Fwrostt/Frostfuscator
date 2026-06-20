package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ResourceEncryptionTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "resource-encryption";
    }

    @Override
    public String getCategory() {
        return "Resources";
    }

    @Override
    public void transform(Context context) {
        boolean removeOriginals = getBooleanOption(context, "remove-originals", false);
        String prefix = context.config().getOption("output-prefix", "META-INF/frostfuscator/encrypted/");
        int seed = getIntOption(context, "seed", 0);
        if (seed == 0) {
            seed = SECURE_RANDOM.nextInt();
        }
        List<String> encrypted = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : new ArrayList<>(context.resources().entrySet())) {
            String name = entry.getKey();
            if (!shouldEncrypt(name, prefix)) {
                continue;
            }

            int key = seed ^ name.hashCode();
            byte[] data = xor(entry.getValue(), key);
            String encryptedName = prefix + name + ".frz";
            context.jar().putResource(encryptedName, data);
            encrypted.add(name + "=" + encryptedName + ":" + Integer.toHexString(key));

            if (removeOriginals) {
                context.jar().removeResource(name);
            }
        }

        if (!encrypted.isEmpty()) {
            context.jar().putResource("META-INF/frostfuscator/resource-encryption-index.txt",
                    String.join("\n", encrypted).getBytes(StandardCharsets.UTF_8));
        }
        context.stats().add("encryptedResources", encrypted.size());
        log("Encrypted {} resources", encrypted.size());
    }

    private boolean shouldEncrypt(String name, String prefix) {
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

    private byte[] xor(byte[] input, int key) {
        byte[] output = input.clone();
        byte[] streamKey = new byte[32];
        new Random(key).nextBytes(streamKey);
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) (output[i] ^ streamKey[i % streamKey.length] ^ (i * 31));
        }
        return output;
    }

    private boolean getBooleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private int getIntOption(Context context, String key, int fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
