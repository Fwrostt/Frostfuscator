package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlowOutlinerTransformerTest {

    @Test
    void outlinesFrameBearingMethodsWithNullTryCatchMetadataInParallel() {
        ClassPool pool = new ClassPool();
        for (int index = 0; index < 64; index++) {
            ClassNode node = classNode("example/Outlined" + index);
            node.methods.add(branchingMethod());
            pool.addClass(node.name, node);
        }
        pool.configureParallelism(true, 8, 1);

        TransformerConfig config = new TransformerConfig();
        config.getOptions().put("probability", 100);
        config.getOptions().put("max-per-class", 4);

        try {
            assertDoesNotThrow(() -> new FlowOutlinerTransformer()
                    .transform(pool, new MappingCollector(), config));
        } finally {
            pool.closeParallelism();
        }

        for (ClassNode node : pool.getClassMap().values()) {
            assertEquals(2, node.methods.size());
            for (MethodNode method : node.methods) {
                assertNotNull(method.tryCatchBlocks);
                assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(node.name, method));
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            assertDoesNotThrow(() -> {
                node.accept(writer);
                new ClassReader(writer.toByteArray());
            });
        }
    }

    private ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        node.methods = new ArrayList<>();
        return node;
    }

    private MethodNode branchingMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(I)I", null, null);
        LabelNode fallback = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(fallback);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        method.tryCatchBlocks = null;
        return method;
    }
}
