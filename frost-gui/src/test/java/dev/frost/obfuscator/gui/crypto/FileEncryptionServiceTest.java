package dev.frost.obfuscator.gui.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEncryptionServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void encryptsAndDecryptsWithoutBlockingTheUiThread() throws Exception {
        byte[] original = new byte[512 * 1024 + 19];
        new java.security.SecureRandom().nextBytes(original);
        Path input = temporaryDirectory.resolve("archive.zip");
        Path encrypted = temporaryDirectory.resolve("archive.zip.frost");
        Path restored = temporaryDirectory.resolve("archive-restored.zip");
        Files.write(input, original);

        try (FileEncryptionService service = new FileEncryptionService()) {
            var encryptedResult = service.encrypt(input, encrypted, "desktop-password".toCharArray())
                    .get(30, TimeUnit.SECONDS);
            assertTrue(encryptedResult.encrypted());
            assertTrue(service.isEncrypted(encrypted).get(5, TimeUnit.SECONDS));
            var decryptedResult = service.decrypt(encrypted, restored, "desktop-password".toCharArray())
                    .get(30, TimeUnit.SECONDS);
            assertFalse(decryptedResult.encrypted());
        }

        assertArrayEquals(original, Files.readAllBytes(restored));
    }
}
