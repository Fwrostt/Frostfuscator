package dev.frost.obfuscator.config;

import dev.frost.obfuscator.config.preset.ExclusionPreset;
import dev.frost.obfuscator.config.preset.ExclusionPresetRegistry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExclusionPresetTest {

    @Test
    void testPresetParsing() {
        assertEquals(ExclusionPreset.SPIGOT, ExclusionPreset.parse("spigot"));
        assertEquals(ExclusionPreset.SPIGOT, ExclusionPreset.parse("SPIGOT"));
        assertEquals(ExclusionPreset.FABRIC, ExclusionPreset.parse("fabric"));
        assertEquals(ExclusionPreset.FORGE, ExclusionPreset.parse("forge"));
        assertEquals(ExclusionPreset.GSON, ExclusionPreset.parse("gson"));
        assertEquals(ExclusionPreset.JACKSON, ExclusionPreset.parse("jackson"));
        assertEquals(ExclusionPreset.SPRING, ExclusionPreset.parse("spring"));
        assertEquals(ExclusionPreset.JPA, ExclusionPreset.parse("jpa"));
        assertEquals(ExclusionPreset.SPONGE, ExclusionPreset.parse("sponge"));
    }

    @Test
    void testPresetRegistryPackageExclusions() {
        ExclusionPresetRegistry registry = new ExclusionPresetRegistry(List.of("spigot", "gson"));
        List<String> combined = registry.getCombinedPackageExclusions();
        assertTrue(combined.contains("org.bukkit.**"));
        assertTrue(combined.contains("com.google.gson.**"));
    }

    @Test
    void testPresetInterfaceAndAnnotationExclusions() {
        ExclusionPresetRegistry registry = new ExclusionPresetRegistry(List.of("spigot", "gson", "fabric"));

        ClassNode listenerClass = new ClassNode();
        listenerClass.name = "com/example/MyListener";
        listenerClass.interfaces = List.of("org/bukkit/event/Listener");
        assertTrue(registry.isClassExcludedByPreset(listenerClass));

        MethodNode eventMethod = new MethodNode();
        eventMethod.name = "onEvent";
        eventMethod.visibleAnnotations = List.of(new AnnotationNode("Lorg/bukkit/event/EventHandler;"));
        assertTrue(registry.isMethodExcludedByPreset(eventMethod));

        FieldNode jsonField = new FieldNode(0, "field", "Ljava/lang/String;", null, null);
        jsonField.visibleAnnotations = List.of(new AnnotationNode("Lcom/google/gson/annotations/SerializedName;"));
        assertTrue(registry.isFieldExcludedByPreset(jsonField));

        ClassNode normalClass = new ClassNode();
        normalClass.name = "com/example/NormalClass";
        assertFalse(registry.isClassExcludedByPreset(normalClass));
    }
}
