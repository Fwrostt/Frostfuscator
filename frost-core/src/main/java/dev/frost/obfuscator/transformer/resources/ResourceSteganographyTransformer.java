package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public final class ResourceSteganographyTransformer extends Transformer {
    private static final int MAGIC = 0x46525A53;
    private static final String INDEX = "META-INF/frostfuscator/stego/index.tsv";
    private static final String RUNTIME_CLASS = "dev/frost/runtime/StegoResourceLoader";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "resource-steganography";
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
        String password = context.config().getOption("password", "change-me");
        boolean removeOriginals = booleanOption(context, "remove-originals", true);
        Set<String> extensions = extensions(context.config().getOption(
                "extensions", "json,yml,yaml,properties,xml,conf,cfg,ini,key,pem"));
        List<String> index = new ArrayList<>();
        int hidden = 0;
        long carrierBytes = 0;

        for (Map.Entry<String, byte[]> entry : new ArrayList<>(context.resources().entrySet())) {
            String name = entry.getKey();
            if (!eligible(name, extensions)) continue;
            try {
                byte[] carrier = carrier(entry.getValue(), password);
                String path = "META-INF/frostfuscator/stego/" + shortHash(name) + ".png";
                context.jar().putResource(path, carrier);
                index.add(encoded(name) + "\t" + path);
                if (removeOriginals) context.jar().removeResource(name);
                hidden++;
                carrierBytes += carrier.length;
            } catch (Exception exception) {
                throw new IllegalStateException("Could not hide resource " + name, exception);
            }
        }
        if (!index.isEmpty()) {
            context.jar().putResource(INDEX, String.join("\n", index).getBytes(StandardCharsets.UTF_8));
            ResourceSplittingTransformer.injectRuntime(context, RUNTIME_CLASS);
        }
        context.stats().add("steganographicResources", hidden);
        context.stats().add("steganographicCarrierBytes", carrierBytes);
        log("Hid {} encrypted resources inside PNG pixel data", hidden);
    }

    private byte[] carrier(byte[] plaintext, String password) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(password.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plaintext);
        int payloadLength = nonce.length + encrypted.length;
        ByteBuffer payload = ByteBuffer.allocate(8 + payloadLength);
        payload.putInt(MAGIC).putInt(payloadLength).put(nonce).put(encrypted);

        int pixels = (payload.array().length * 8 + 2) / 3;
        int width = Math.max(16, (int) Math.ceil(Math.sqrt(pixels)));
        int height = Math.max(16, (pixels + width - 1) / width);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        byte[] data = payload.array();
        int bit = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = RANDOM.nextInt(0x1000000);
                for (int shift : new int[]{16, 8, 0}) {
                    rgb &= ~(1 << shift);
                    if (bit < data.length * 8) {
                        rgb |= ((data[bit >>> 3] >>> (7 - (bit & 7))) & 1) << shift;
                        bit++;
                    } else {
                        rgb |= RANDOM.nextInt(2) << shift;
                    }
                }
                image.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", output)) throw new IllegalStateException("PNG encoder unavailable");
        return output.toByteArray();
    }

    private boolean eligible(String name, Set<String> extensions) {
        if (name.startsWith("META-INF/frostfuscator/")
                || name.equals("META-INF/MANIFEST.MF")
                || name.endsWith("/") || name.endsWith(".class")) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private Set<String> extensions(String configured) {
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,;\\s]+")) {
            if (!value.isBlank()) result.add(value.replace(".", "").toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
