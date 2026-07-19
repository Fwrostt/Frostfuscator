package dev.frost.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Runtime API injected into outputs using resource-steganography.
 */
public final class StegoResourceLoader {
    private static final String INDEX = "META-INF/frostfuscator/stego/index.tsv";
    private static final int MAGIC = 0x46525A53;

    private StegoResourceLoader() {
    }

    public static byte[] read(String resourceName, String password) throws IOException {
        ClassLoader loader = effectiveLoader();
        String carrier = findCarrier(loader, resourceName);
        try (InputStream input = loader.getResourceAsStream(carrier)) {
            if (input == null) throw new IOException("Steganographic carrier is missing");
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IOException("Invalid PNG carrier");
            byte[] header = extract(image, 8);
            ByteBuffer headerBuffer = ByteBuffer.wrap(header);
            if (headerBuffer.getInt() != MAGIC) throw new IOException("Invalid steganographic payload");
            int length = headerBuffer.getInt();
            byte[] payload = extract(image, 8 + length);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.position(8);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[length - nonce.length];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            return cipher.doFinal(encrypted);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not decrypt steganographic resource", exception);
        }
    }

    public static InputStream open(String resourceName, String password) throws IOException {
        return new ByteArrayInputStream(read(resourceName, password));
    }

    private static String findCarrier(ClassLoader loader, String resourceName) throws IOException {
        try (InputStream index = loader.getResourceAsStream(INDEX)) {
            if (index == null) throw new IOException("Steganographic resource index is missing");
            String encodedName = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(resourceName.getBytes(StandardCharsets.UTF_8));
            for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String[] columns = line.split("\\t");
                if (columns.length == 2 && columns[0].equals(encodedName)) return columns[1];
            }
        }
        throw new IOException("Unknown steganographic resource: " + resourceName);
    }

    private static byte[] extract(BufferedImage image, int byteCount) throws IOException {
        if ((long) byteCount * 8 > (long) image.getWidth() * image.getHeight() * 3) {
            throw new IOException("Truncated steganographic payload");
        }
        byte[] result = new byte[byteCount];
        int bit = 0;
        for (int y = 0; y < image.getHeight() && bit < byteCount * 8; y++) {
            for (int x = 0; x < image.getWidth() && bit < byteCount * 8; x++) {
                int rgb = image.getRGB(x, y);
                for (int shift : new int[]{16, 8, 0}) {
                    if (bit >= byteCount * 8) break;
                    result[bit >>> 3] |= (byte) (((rgb >>> shift) & 1) << (7 - (bit & 7)));
                    bit++;
                }
            }
        }
        return result;
    }

    private static ClassLoader effectiveLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : StegoResourceLoader.class.getClassLoader();
    }
}
