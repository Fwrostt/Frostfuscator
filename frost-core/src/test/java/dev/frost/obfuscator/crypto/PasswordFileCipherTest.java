package dev.frost.obfuscator.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PasswordFileCipherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsArbitraryBinaryFiles() throws Exception {
        byte[] content = new byte[1024 * 1024 + 37];
        new java.security.SecureRandom().nextBytes(content);
        Path input = temporaryDirectory.resolve("payload.bin");
        Path encrypted = temporaryDirectory.resolve("payload.bin.frost");
        Path restored = temporaryDirectory.resolve("payload-restored.bin");
        Files.write(input, content);

        char[] password = "correct horse battery staple".toCharArray();
        PasswordFileCipher.encrypt(input, encrypted, password);
        PasswordFileCipher.decrypt(encrypted, restored, password);

        assertTrue(PasswordFileCipher.isEncrypted(encrypted));
        assertArrayEquals(content, Files.readAllBytes(restored));
        assertFalse(java.util.Arrays.equals(content, Files.readAllBytes(encrypted)));
    }

    @Test
    void wrongPasswordFailsWithoutReplacingDestination() throws Exception {
        Path input = temporaryDirectory.resolve("secret.txt");
        Path encrypted = temporaryDirectory.resolve("secret.txt.frost");
        Path output = temporaryDirectory.resolve("restored.txt");
        Files.writeString(input, "private mapping data", StandardCharsets.UTF_8);
        Files.writeString(output, "keep me", StandardCharsets.UTF_8);
        PasswordFileCipher.encrypt(input, encrypted, "right-password".toCharArray());

        assertThrows(PasswordFileCipher.IncorrectPasswordException.class,
                () -> PasswordFileCipher.decrypt(encrypted, output, "wrong-password".toCharArray()));
        assertEquals("keep me", Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void modifiedCiphertextIsRejected() throws Exception {
        Path encrypted = temporaryDirectory.resolve("mapping.txt.enc");
        PasswordFileCipher.encrypt("a.A -> b.B".getBytes(StandardCharsets.UTF_8), encrypted,
                "mapping-password".toCharArray());
        byte[] content = Files.readAllBytes(encrypted);
        content[content.length - 2] ^= 0x5A;
        Files.write(encrypted, content);

        assertThrows(PasswordFileCipher.IncorrectPasswordException.class,
                () -> PasswordFileCipher.decryptToBytes(encrypted, "mapping-password".toCharArray()));
    }

    @Test
    void rejectsPlainFilesAndSameInputOutput() throws Exception {
        Path input = temporaryDirectory.resolve("plain.txt");
        Files.writeString(input, "plain", StandardCharsets.UTF_8);

        assertFalse(PasswordFileCipher.isEncrypted(input));
        IOException error = assertThrows(IOException.class,
                () -> PasswordFileCipher.encrypt(input, input, "password".toCharArray()));
        assertTrue(error.getMessage().contains("different"));
    }
}
