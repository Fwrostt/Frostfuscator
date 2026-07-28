package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRangeTransformerTest {

    @Test
    void rethrowRangeComputesFramesWithoutNormalHandlerFallthrough() throws Exception {
        ClassNode owner = classNode();
        MethodNode method = intMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value");
        owner.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(owner.name, owner);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new FlowRangeTransformer().transform(pool, new MappingCollector(), config);

        assertEquals(1, method.tryCatchBlocks.size());
        assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(owner.name, method));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        assertDoesNotThrow(() -> {
            owner.accept(writer);
            new ClassReader(writer.toByteArray());
        });
    }

    @Test
    void skipsSyntheticGeneratedHelpersByDefault() {
        ClassNode owner = classNode();
        MethodNode helper = intMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "fragment");
        owner.methods.add(helper);

        ClassPool pool = new ClassPool();
        pool.addClass(owner.name, owner);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new FlowRangeTransformer().transform(pool, new MappingCollector(), config);

        assertTrue(helper.tryCatchBlocks.isEmpty());
    }

    private ClassNode classNode() {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = "example/FlowRange";
        owner.superName = "java/lang/Object";
        return owner;
    }

    private MethodNode intMethod(int access, String name) {
        MethodNode method = new MethodNode(access, name, "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        return method;
    }
}
