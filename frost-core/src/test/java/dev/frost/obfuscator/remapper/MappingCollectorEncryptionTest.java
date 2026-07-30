package dev.frost.obfuscator.remapper;

import dev.frost.obfuscator.crypto.PasswordFileCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingCollectorEncryptionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsAnAuthenticatedEncryptedMapping() throws Exception {
        MappingCollector mappings = new MappingCollector();
        mappings.mapClass("example/Original", "a/b");
        mappings.mapField("example/Original", "secret", "I", "x");
        mappings.mapMethod("example/Original", "run", "()V", "y");
        Path encrypted = temporaryDirectory.resolve("mapping.txt.enc");

        mappings.exportEncryptedMappings(encrypted, "release-password".toCharArray());

        assertTrue(PasswordFileCipher.isEncrypted(encrypted));
        String cleartext = new String(PasswordFileCipher.decryptToBytes(
                encrypted, "release-password".toCharArray()), StandardCharsets.UTF_8);
        Map<?, ?> document = new Yaml().load(cleartext);
        assertEquals("a.b", ((Map<?, ?>) document.get("classes")).get("example.Original"));
        assertEquals("x", ((Map<?, ?>) ((List<?>) document.get("fields")).getFirst()).get("mappedName"));
        assertEquals("y", ((Map<?, ?>) ((List<?>) document.get("methods")).getFirst()).get("mappedName"));
        assertFalse(cleartext.contains("release-password"));
    }
}
