package dev.frost.obfuscator.transformer.cleanup;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LineNumberMutationTest {

    @Test
    void testLineNumberMutationScramblesDebugLines() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/DebugClass";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "test", "()V", null, null);
        LabelNode l1 = new LabelNode();
        LineNumberNode originalLine = new LineNumberNode(12, l1);
        method.instructions.add(l1);
        method.instructions.add(originalLine);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        LineNumberMutationTransformer transformer = new LineNumberMutationTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("min-line", 5000, "max-line", 9000, "seed", 123L));

        transformer.transform(pool, new MappingCollector(), config);

        assertNotEquals(12, originalLine.line);
        assertTrue(originalLine.line >= 5000 && originalLine.line <= 9000);
    }
}
