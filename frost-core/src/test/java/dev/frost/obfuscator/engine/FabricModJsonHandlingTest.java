package dev.frost.obfuscator.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frost.obfuscator.remapper.MappingCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FabricModJsonHandlingTest {

    @TempDir
    Path tempDir;

    @Test
    void testDetectsAndRemapsFabricModJsonAndMixins() throws Exception {
        Path jarPath = tempDir.resolve("fabric-mod.jar");

        String fabricModJson = """
                {
                  "schemaVersion": 1,
                  "id": "examplemod",
                  "version": "1.0.0",
                  "name": "Example Mod",
                  "entrypoints": {
                    "main": [
                      "com.example.mod.ExampleMod",
                      "com.example.mod.SecondMod::onInitialize"
                    ],
                    "client": [
                      {
                        "adapter": "com.example.mod.CustomAdapter",
                        "value": "com.example.mod.ClientMod::onInitClient"
                      }
                    ]
                  },
                  "mixins": [
                    "examplemod.mixins.json"
                  ],
                  "languageAdapters": {
                    "custom": "com.example.mod.CustomAdapter"
                  }
                }
                """;

        String mixinJson = """
                {
                  "required": true,
                  "package": "com.example.mod.mixin",
                  "compatibilityLevel": "JAVA_17",
                  "mixins": [
                    "ExampleMixin"
                  ],
                  "client": [
                    "ClientMixin"
                  ]
                }
                """;

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(fabricModJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("examplemod.mixins.json"));
            jos.write(mixinJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        JarProcessor processor = new JarProcessor();
        processor.loadJar(jarPath);

        assertTrue(processor.isFabricMod());
        assertTrue(processor.getDetectedFabricEntrypoints().contains("com.example.mod.ExampleMod"));
        assertTrue(processor.getDetectedFabricEntrypoints().contains("com.example.mod.SecondMod"));
        assertTrue(processor.getDetectedFabricEntrypoints().contains("com.example.mod.ClientMod"));

        MappingCollector mappings = new MappingCollector();
        mappings.mapClass("com/example/mod/ExampleMod", "a/b/ModA");
        mappings.mapClass("com/example/mod/SecondMod", "a/b/ModB");
        mappings.mapClass("com/example/mod/ClientMod", "a/b/ModC");
        mappings.mapClass("com/example/mod/CustomAdapter", "a/b/AdapterA");
        mappings.mapClass("com/example/mod/mixin/ExampleMixin", "a/b/MixinA");

        processor.updateFabricModJson(mappings);

        byte[] updatedFabricBytes = processor.getResources().get("fabric.mod.json");
        assertNotNull(updatedFabricBytes);
        String updatedFabricStr = new String(updatedFabricBytes, StandardCharsets.UTF_8);

        JsonObject root = JsonParser.parseString(updatedFabricStr).getAsJsonObject();
        JsonObject entrypoints = root.getAsJsonObject("entrypoints");

        String mainFirst = entrypoints.getAsJsonArray("main").get(0).getAsString();
        assertEquals("a.b.ModA", mainFirst);

        String mainSecond = entrypoints.getAsJsonArray("main").get(1).getAsString();
        assertEquals("a.b.ModB::onInitialize", mainSecond);

        JsonObject clientObj = entrypoints.getAsJsonArray("client").get(0).getAsJsonObject();
        assertEquals("a.b.ModC::onInitClient", clientObj.get("value").getAsString());
        assertEquals("a.b.AdapterA", clientObj.get("adapter").getAsString());

        assertEquals("a.b.AdapterA", root.getAsJsonObject("languageAdapters").get("custom").getAsString());

        byte[] updatedMixinBytes = processor.getResources().get("examplemod.mixins.json");
        assertNotNull(updatedMixinBytes);
        JsonObject mixinRoot = JsonParser.parseString(new String(updatedMixinBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String mixinClass = mixinRoot.getAsJsonArray("mixins").get(0).getAsString();
        assertEquals("a.b.MixinA", mixinClass);
    }
}
