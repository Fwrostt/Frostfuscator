package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntiAgentTest {

    @Test
    void testAntiAgentTransformerInjectsCheck() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/AgentProtected";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        AntiAgentTransformer transformer = new AntiAgentTransformer();
        TransformerConfig config = new TransformerConfig();

        transformer.transform(pool, new MappingCollector(), config);

        AbstractInsnNode first = method.instructions.getFirst();
        assertTrue(first instanceof MethodInsnNode);
        MethodInsnNode call = (MethodInsnNode) first;
        assertEquals("checkInstrumentationAndAgents", call.name);
    }
}
