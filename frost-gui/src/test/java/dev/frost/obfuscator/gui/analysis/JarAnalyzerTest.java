package dev.frost.obfuscator.gui.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class JarAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsManifestRuntimeAndSensitiveArchiveFeatures() throws Exception {
        Path jar = temporaryDirectory.resolve("sample.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/dependency.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output, manifest)) {
            add(archive, "com/example/Main.class", fakeClass(65, "java/lang/reflect Class forName"));
            add(archive, "META-INF/services/com.example.Plugin", "com.example.PluginImpl".getBytes(StandardCharsets.UTF_8));
            add(archive, "native/example.dll", new byte[]{1, 2, 3});
            add(archive, "META-INF/SAMPLE.SF", new byte[]{1});
            add(archive, "BOOT-INF/lib/nested.jar", new byte[]{4, 5, 6});
        }

        ProjectAnalysis analysis = new JarAnalyzer().analyze(jar);

        assertEquals(1, analysis.classCount());
        assertEquals(21, analysis.javaVersion());
        assertEquals("com.example.Main", analysis.mainClass());
        assertTrue(analysis.reflectionUsage());
        assertTrue(analysis.serviceLoaders());
        assertTrue(analysis.nativeLibraries());
        assertTrue(analysis.signed());
        assertTrue(analysis.fatJar());
        assertTrue(analysis.suggestedOutput().endsWith("sample-protected.jar"));
        assertFalse(analysis.keepRules().isEmpty());
    }

    private static void add(JarOutputStream archive, String name, byte[] value) throws Exception {
        archive.putNextEntry(new JarEntry(name));
        archive.write(value);
        archive.closeEntry();
    }

    private static byte[] fakeClass(int major, String constants) {
        byte[] suffix = constants.getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = new byte[8 + suffix.length];
        bytes[0] = (byte) 0xCA;
        bytes[1] = (byte) 0xFE;
        bytes[2] = (byte) 0xBA;
        bytes[3] = (byte) 0xBE;
        bytes[6] = (byte) (major >>> 8);
        bytes[7] = (byte) major;
        System.arraycopy(suffix, 0, bytes, 8, suffix.length);
        return bytes;
    }
}
