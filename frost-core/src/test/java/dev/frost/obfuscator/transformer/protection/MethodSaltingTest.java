package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MethodSaltingTest {

    @Test
    void testMethodSaltingInjectsSalts() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/SaltedClass";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        MethodSaltingTransformer transformer = new MethodSaltingTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "max-salts", 3, "seed", 999L));

        int initialSize = method.instructions.size();
        transformer.transform(pool, new MappingCollector(), config);

        assertTrue(method.instructions.size() > initialSize);
    }

    @Test
    void saltsRemainStackNeutralAtEmptyPrimitiveAndReferenceStackStates() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.version = Opcodes.V17;
        classNode.name = "com/example/StackNeutralSalt";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose",
                "(Z)Ljava/lang/Object;",
                null,
                null
        );
        LabelNode nonNull = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, nonNull));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(nonNull);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false
        ));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 1;
        method.maxStack = 16;
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100, "max-salts", 32, "seed", 7L));

        new MethodSaltingTransformer().transform(pool, new MappingCollector(), config);

        new Analyzer<>(new BasicVerifier()).analyze(classNode.name, method);
    }
}
