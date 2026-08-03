package dev.frost.obfuscator.transformer.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class ParameterEncryptionTransformerTest {
    @Test
    void rewritesExactIntAndLongEntryAndCallsiteValuesThroughSsa() throws Exception {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = "fixture/EncryptedParameters";
        owner.superName = "java/lang/Object";

        MethodNode target = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "target", "(IJ)J", null, null);
        target.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        target.instructions.add(new InsnNode(Opcodes.I2L));
        target.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        target.instructions.add(new InsnNode(Opcodes.LADD));
        target.instructions.add(new InsnNode(Opcodes.LRETURN));
        target.maxStack = 4;
        target.maxLocals = 3;
        owner.methods.add(target);

        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()J", null, null);
        caller.instructions.add(new LdcInsnNode(7));
        caller.instructions.add(new LdcInsnNode(9L));
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                owner.name, target.name, target.desc, false));
        caller.instructions.add(new InsnNode(Opcodes.LRETURN));
        caller.maxStack = 4;
        owner.methods.add(caller);

        ClassPool pool = new ClassPool();
        pool.addClass(owner.name, owner);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("probability", 100));
        new ParameterEncryptionTransformer().transform(pool, new MappingCollector(), config);

        assertTrue(containsOpcode(target, Opcodes.IXOR));
        assertTrue(containsOpcode(target, Opcodes.LXOR));
        assertTrue(containsOpcode(caller, Opcodes.IXOR));
        assertTrue(containsOpcode(caller, Opcodes.LXOR));

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        byte[] bytecode = writer.toByteArray();
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(owner.name.replace('/', '.'), bytecode, 0, bytecode.length); }
        }.define();
        assertEquals(16L, generated.getMethod("run").invoke(null));
    }

    private boolean containsOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return true;
        }
        return false;
    }
}
