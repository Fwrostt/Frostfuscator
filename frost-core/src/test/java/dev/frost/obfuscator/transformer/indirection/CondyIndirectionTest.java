package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CondyIndirectionTest {

    @Test
    void testCondyIndirectionReplacesLdcWithConstantDynamic() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/CondyApp";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V11;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getMessage", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("Hello Condy"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        CondyIndirectionTransformer transformer = new CondyIndirectionTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        transformer.transform(pool, new MappingCollector(), config);

        // Verify LdcInsnNode holds ConstantDynamic
        AbstractInsnNode firstInsn = method.instructions.getFirst();
        assertTrue(firstInsn instanceof LdcInsnNode);
        LdcInsnNode ldc = (LdcInsnNode) firstInsn;
        assertTrue(ldc.cst instanceof ConstantDynamic);

        // Verify bootstrap method was generated
        boolean hasBootstrap = classNode.methods.stream().anyMatch(m -> m.name.equals("__frost$condy$bootstrap"));
        assertTrue(hasBootstrap, "Condy bootstrap method should be generated");
    }

    @Test
    void primitiveConstantsRetainPrimitiveJvmStackTypes() throws Exception {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/PrimitiveCondy";
        classNode.superName = "java/lang/Object";
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.version = Opcodes.V17;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()I", null, null);
        method.instructions.add(new LdcInsnNode(7));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(8L));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new LdcInsnNode(9.0f));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(10.0d));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new LdcInsnNode(11));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));

        new CondyIndirectionTransformer().transform(pool, new MappingCollector(), config);

        List<String> descriptors = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic condy) {
                descriptors.add(condy.getDescriptor());
            }
        }
        assertEquals(List.of("I", "J", "F", "D", "I"), descriptors);
        assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(classNode.name, method));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        assertDoesNotThrow(() -> {
            classNode.accept(writer);
            new ClassReader(writer.toByteArray());
        });
    }
}
