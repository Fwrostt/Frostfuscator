package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarProcessorYamlMetadataTest {

    @Test
    void updatesNonStandardPluginYamlThroughParsedTree() {
        String source = """
                # Plugin descriptor with an anchor and non-standard main formatting.
                defaults: &defaults
                  website: https://example.test
                metadata:
                  <<: *defaults
                name: FrostPlugin
                main : &entry 'com.example.Plugin' # server entrypoint
                bootstrap: *entry
                api-version: '1.20'
                """;
        JarProcessor processor = new JarProcessor();
        processor.putResource("plugin.yml", source.getBytes(StandardCharsets.UTF_8));

        processor.updatePluginMainClass("com.example.Plugin", "obfuscated.EntryPoint");

        String serialized = new String(processor.getResources().get("plugin.yml"), StandardCharsets.UTF_8);
        Map<?, ?> updated = new Yaml().load(serialized);
        assertEquals("obfuscated.EntryPoint", updated.get("main"));
        assertEquals("obfuscated.EntryPoint", updated.get("bootstrap"));
        assertEquals("FrostPlugin", updated.get("name"));
        assertEquals("1.20", updated.get("api-version"));
        assertEquals("https://example.test", ((Map<?, ?>) updated.get("metadata")).get("website"));
        assertTrue(serialized.contains("# Plugin descriptor with an anchor"));
        assertTrue(serialized.contains("# server entrypoint"));
        assertTrue(serialized.contains("&defaults"));
    }

    @Test
    void leavesMalformedYamlUntouched() {
        byte[] malformed = "main: [unterminated".getBytes(StandardCharsets.UTF_8);
        JarProcessor processor = new JarProcessor();
        processor.putResource("paper-plugin.yml", malformed.clone());

        processor.updatePluginMainClass("com.example.Plugin", "obfuscated.EntryPoint");

        assertArrayEquals(malformed, processor.getResources().get("paper-plugin.yml"));
    }
}
