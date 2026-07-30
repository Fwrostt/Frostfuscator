package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RemoveDebugTransformerTest {
    @Test
    void preservesKotlinMetadataByDefault() {
        ClassNode node = kotlinClass();

        transform(node, new TransformerConfig());

        assertTrue(hasKotlinMetadata(node.visibleAnnotations));
        assertTrue(hasKotlinMetadata(node.invisibleAnnotations));
    }

    @Test
    void removesKotlinMetadataOnlyWhenExplicitlyEnabled() {
        ClassNode node = kotlinClass();
        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("remove-kotlin-metadata", true);

        transform(node, config);

        assertFalse(hasKotlinMetadata(node.visibleAnnotations));
        assertFalse(hasKotlinMetadata(node.invisibleAnnotations));
        assertEquals("Lexample/Keep;", node.visibleAnnotations.getFirst().desc);
    }

    private static void transform(ClassNode node, TransformerConfig config) {
        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        new RemoveDebugTransformer().transform(pool, new MappingCollector(), config);
    }

    private static ClassNode kotlinClass() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "example/KotlinType";
        node.superName = "java/lang/Object";
        node.visibleAnnotations = new ArrayList<>();
        node.visibleAnnotations.add(new AnnotationNode("Lkotlin/Metadata;"));
        node.visibleAnnotations.add(new AnnotationNode("Lexample/Keep;"));
        node.invisibleAnnotations = new ArrayList<>();
        node.invisibleAnnotations.add(new AnnotationNode("Lkotlin/Metadata;"));
        return node;
    }

    private static boolean hasKotlinMetadata(java.util.List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream()
                .anyMatch(annotation -> "Lkotlin/Metadata;".equals(annotation.desc));
    }
}
