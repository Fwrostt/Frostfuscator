package dev.frost.obfuscator.transformer.funsies;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompilerCrasherTest {

    @Test
    void testDecompilerCrasherInjectsVerifierSafeTryCatchTrap() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V17;
        classNode.name = "com/example/CrasherTest";
        classNode.access = Opcodes.ACC_PUBLIC;

        // Access Modifier may set ACC_SYNTHETIC on every ordinary method before this pass.
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "compute", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        DecompilerCrasherTransformer transformer = new DecompilerCrasherTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        transformer.transform(pool, new MappingCollector(), config);

        assertTrue(method.tryCatchBlocks.size() >= 2);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        assertDoesNotThrow(() -> {
            classNode.accept(writer);
            new ClassReader(writer.toByteArray());
        });
    }

    @Test
    void injectedTrapVerifiesAcrossPrimitiveAndReferenceControlFlow() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V17;
        classNode.name = "com/example/CrasherControlFlow";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(Ljava/lang/Object;Z)Ljava/lang/Object;", null, null);
        LabelNode useFallback = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, useFallback));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(useFallback);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 2;
        method.maxStack = 4;
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new DecompilerCrasherTransformer().transform(pool, new MappingCollector(), config);

        assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(classNode.name, method));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        assertDoesNotThrow(() -> {
            classNode.accept(writer);
            new ClassReader(writer.toByteArray());
        });
    }

    @Test
    void skipsReservedGeneratedMethodsByDefault() {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V17;
        classNode.name = "com/example/GeneratedHelpers";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "fragment", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("part"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        MappingCollector mappings = new MappingCollector();
        mappings.preserveMethod(classNode.name, method.name, method.desc);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new DecompilerCrasherTransformer().transform(pool, mappings, config);

        assertTrue(method.tryCatchBlocks.isEmpty());
    }
}
