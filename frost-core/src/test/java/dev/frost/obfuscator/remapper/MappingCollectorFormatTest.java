package dev.frost.obfuscator.remapper;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingCollectorFormatTest {

    @Test
    void rendersYamlProGuardAndTinyMappings() {
        MappingCollector mappings = new MappingCollector();
        mappings.mapClass("example/User", "a/A");
        mappings.mapClass("example/Dependency", "a/B");
        mappings.mapField("example/User", "name", "Ljava/lang/String;", "a");
        mappings.mapMethod("example/User", "greet", "(I)Ljava/lang/String;", "b");

        String proguard = mappings.renderMappings(MappingFormat.PROGUARD);
        assertTrue(proguard.contains("example.User -> a.A:"));
        assertTrue(proguard.contains("java.lang.String name -> a"));
        assertTrue(proguard.contains("java.lang.String greet(int) -> b"));

        String tiny = mappings.renderMappings(MappingFormat.TINY);
        assertTrue(tiny.startsWith("tiny\t2\t0\toriginal\tobfuscated\n"));
        assertTrue(tiny.contains("c\texample/User\ta/A"));
        assertTrue(tiny.contains("\tf\tLjava/lang/String;\tname\ta"));
        assertTrue(tiny.contains("\tm\t(I)Ljava/lang/String;\tgreet\tb"));

        Map<?, ?> yaml = new Yaml().load(mappings.renderMappings(MappingFormat.YAML));
        assertEquals("frostfuscator-mappings", yaml.get("format"));
        assertEquals("a.A", ((Map<?, ?>) yaml.get("classes")).get("example.User"));
    }
}
