package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

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
}
