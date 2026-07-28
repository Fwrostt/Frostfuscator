package dev.frost.obfuscator.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Password-based authenticated file encryption used by encrypted mapping
 * exports and the desktop Encryptor workspace.
 *
 * <p>The container is versioned and self-describing. Payloads are encrypted
 * with AES-256-GCM. The password is expanded with PBKDF2-HMAC-SHA256 using a
 * fresh random salt for every file. Header bytes are authenticated as AAD, so
 * metadata tampering is detected together with ciphertext tampering.</p>
 */
public final class PasswordFileCipher {
    public static final String FILE_EXTENSION = ".frost";
    public static final int DEFAULT_ITERATIONS = 310_000;

    private static final byte[] MAGIC = "FROSTENC".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordFileCipher() {
    }

    public static void encrypt(Path input, Path output, char[] password) throws IOException {
        encrypt(input, output, password, ProgressListener.NOOP);
    }

    public static void encrypt(Path input, Path output, char[] password, ProgressListener progress) throws IOException {
        requirePassword(password);
        Path source = requireReadableInput(input);
        Path destination = requireDistinctOutput(source, output);
        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        long originalSize = Files.size(source);
        byte[] header = header(DEFAULT_ITERATIONS, originalSize, salt, nonce);

        writeAtomically(destination, temporary -> {
            try (InputStream plain = new BufferedInputStream(Files.newInputStream(source));
                 OutputStream file = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                encryptStream(plain, file, password, salt, nonce, header, originalSize, listener);
            }
        });
    }

    public static void encrypt(byte[] plaintext, Path output, char[] password) throws IOException {
        requirePassword(password);
        if (plaintext == null) throw new IllegalArgumentException("Plaintext is required");
        Path destination = requireOutput(output);
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] header = header(DEFAULT_ITERATIONS, plaintext.length, salt, nonce);

        writeAtomically(destination, temporary -> {
            try (InputStream plain = new ByteArrayInputStream(plaintext);
                 OutputStream file = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                encryptStream(plain, file, password, salt, nonce, header, plaintext.length, ProgressListener.NOOP);
            }
        });
    }

    public static void decrypt(Path input, Path output, char[] password) throws IOException {
        decrypt(input, output, password, ProgressListener.NOOP);
    }

    public static void decrypt(Path input, Path output, char[] password, ProgressListener progress) throws IOException {
        requirePassword(password);
        Path source = requireReadableInput(input);
        Path destination = requireDistinctOutput(source, output);
        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;

        writeAtomically(destination, temporary -> {
            try (InputStream file = new BufferedInputStream(Files.newInputStream(source));
                 OutputStream plain = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                decryptStream(file, plain, password, listener);
            }
        });
    }

    public static byte[] decryptToBytes(Path input, char[] password) throws IOException {
        requirePassword(password);
        Path source = requireReadableInput(input);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source));
             ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
            decryptStream(file, plain, password, ProgressListener.NOOP);
            return plain.toByteArray();
        }
    }

    public static boolean isEncrypted(Path input) throws IOException {
        Path source = requireReadableInput(input);
        if (Files.size(source) < MAGIC.length) return false;
        byte[] prefix = new byte[MAGIC.length];
        try (InputStream stream = Files.newInputStream(source)) {
            if (stream.readNBytes(prefix, 0, prefix.length) != prefix.length) return false;
        }
        return Arrays.equals(prefix, MAGIC);
    }

    private static void encryptStream(InputStream plain, OutputStream file, char[] password,
                                      byte[] salt, byte[] nonce, byte[] header, long originalSize,
                                      ProgressListener progress) throws IOException {
        SecretKeySpec key = null;
        try {
            key = deriveKey(password, salt, DEFAULT_ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            file.write(header);
            try (CipherOutputStream encrypted = new CipherOutputStream(file, cipher)) {
                copyWithProgress(plain, encrypted, originalSize, progress);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not initialize AES-256 encryption", exception);
        } finally {
            destroy(key);
        }
    }

    private static void decryptStream(InputStream file, OutputStream plain, char[] password,
                                      ProgressListener progress) throws IOException {
        ContainerHeader container = readHeader(file);
        SecretKeySpec key = null;
        try {
            key = deriveKey(password, container.salt(), container.iterations());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, container.nonce()));
            cipher.updateAAD(container.encoded());

            try (CipherInputStream decrypted = new CipherInputStream(file, cipher)) {
                copyWithProgress(decrypted, plain, container.originalSize(), progress);
            } catch (IOException exception) {
                if (hasBadTag(exception)) {
                    throw new IncorrectPasswordException("Incorrect password or encrypted file has been modified", exception);
                }
                throw exception;
            }
        } catch (IncorrectPasswordException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            if (exception instanceof AEADBadTagException) {
                throw new IncorrectPasswordException("Incorrect password or encrypted file has been modified", exception);
            }
            throw new IOException("Could not initialize AES-256 decryption", exception);
        } finally {
            destroy(key);
        }
    }

    private static ContainerHeader readHeader(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        byte[] magic = input.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new InvalidEncryptedFileException("This is not a Frost encrypted file");
        }

        int version = input.readUnsignedByte();
        if (version != VERSION) {
            throw new InvalidEncryptedFileException("Unsupported encrypted file version: " + version);
        }
        int flags = input.readUnsignedByte();
        if (flags != 0) {
            throw new InvalidEncryptedFileException("Unsupported encrypted file flags: " + flags);
        }
        int iterations = input.readInt();
        int saltLength = input.readUnsignedByte();
        int nonceLength = input.readUnsignedByte();
        long originalSize = input.readLong();
        if (iterations < 100_000 || iterations > 10_000_000
                || saltLength != SALT_BYTES || nonceLength != NONCE_BYTES || originalSize < 0) {
            throw new InvalidEncryptedFileException("Encrypted file header is invalid");
        }
        byte[] salt = input.readNBytes(saltLength);
        byte[] nonce = input.readNBytes(nonceLength);
        if (salt.length != saltLength || nonce.length != nonceLength) {
            throw new InvalidEncryptedFileException("Encrypted file header is truncated");
        }
        return new ContainerHeader(iterations, originalSize, salt, nonce,
                header(iterations, originalSize, salt, nonce));
    }

    private static byte[] header(int iterations, long originalSize, byte[] salt, byte[] nonce) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(0);
            output.writeInt(iterations);
            output.writeByte(salt.length);
            output.writeByte(nonce.length);
            output.writeLong(originalSize);
            output.write(salt);
            output.write(nonce);
        }
        return bytes.toByteArray();
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        byte[] encoded = null;
        try {
            encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    private static boolean hasBadTag(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AEADBadTagException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void copyWithProgress(InputStream input, OutputStream output, long total,
                                         ProgressListener progress) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long processed = 0;
        int read;
        progress.onProgress(0, total);
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            processed += read;
            progress.onProgress(Math.min(processed, total), total);
        }
        progress.onProgress(total, total);
    }

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }
    }

    private static Path requireReadableInput(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("Input path is required");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            throw new IOException("Input file is not readable: " + normalized);
        }
        return normalized;
    }

    private static Path requireOutput(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("Output path is required");
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Output path must have a parent directory");
        Files.createDirectories(parent);
        return normalized;
    }

    private static Path requireDistinctOutput(Path input, Path output) throws IOException {
        Path destination = requireOutput(output);
        if (input.equals(destination)) {
            throw new IOException("Input and output paths must be different");
        }
        return destination;
    }

    private static void writeAtomically(Path output, TempFileWriter writer) throws IOException {
        Path parent = output.getParent();
        Path temporary = Files.createTempFile(parent, ".frost-", ".tmp");
        boolean complete = false;
        try {
            writer.write(temporary);
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(temporary);
        }
    }

    private static void destroy(SecretKeySpec key) {
        if (key == null) return;
        byte[] encoded = key.getEncoded();
        if (encoded != null) Arrays.fill(encoded, (byte) 0);
    }

    private record ContainerHeader(int iterations, long originalSize, byte[] salt, byte[] nonce, byte[] encoded) {
    }

    @FunctionalInterface
    private interface TempFileWriter {
        void write(Path temporary) throws IOException;
    }

    public static final class IncorrectPasswordException extends IOException {
        public IncorrectPasswordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class InvalidEncryptedFileException extends IOException {
        public InvalidEncryptedFileException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NOOP = (processed, total) -> { };

        void onProgress(long processedBytes, long totalBytes);
    }
}
