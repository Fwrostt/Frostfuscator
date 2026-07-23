package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassSaltingTest {

    @Test
    void testClassSaltingAddsSyntheticFields() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/StructSalt";
        classNode.access = Opcodes.ACC_PUBLIC;

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        ClassSaltingTransformer transformer = new ClassSaltingTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("fields-per-class", 3, "seed", 555L));

        assertEquals(0, classNode.fields.size());
        transformer.transform(pool, new MappingCollector(), config);

        assertEquals(3, classNode.fields.size());
        assertTrue(classNode.fields.get(0).name.startsWith("$salt_"));
    }
}
