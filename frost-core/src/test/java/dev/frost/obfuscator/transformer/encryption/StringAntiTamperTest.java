package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StringAntiTamperTest {

    @Test
    void testStringEncryptionInjectsAntiTamperCheck() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/example/SecureApp";
        classNode.access = Opcodes.ACC_PUBLIC;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getSecret", "()Ljava/lang/String;", null, null);
        method.instructions.add(new LdcInsnNode("CONFIDENTIAL_TOKEN"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(method);

        ClassPool pool = new ClassPool();
        pool.addClass(classNode.name, classNode);

        StringEncryptionTransformer transformer = new StringEncryptionTransformer();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(Map.of("mode", "lite", "anti-tamper", true));

        transformer.transform(pool, new MappingCollector(), config);

        // Verify decryptor method contains getStackTrace caller check
        MethodNode decryptor = classNode.methods.stream()
                .filter(m -> m.desc.equals("([BI)Ljava/lang/String;"))
                .findFirst().orElse(null);

        assertNotNull(decryptor, "Decryptor method should be created");
        boolean hasStackTraceCall = false;
        for (AbstractInsnNode insn : decryptor.instructions) {
            if (insn instanceof MethodInsnNode minsn && minsn.name.equals("getStackTrace")) {
                hasStackTraceCall = true;
                break;
            }
        }
        assertTrue(hasStackTraceCall, "Decryptor should contain caller stack trace verification");
    }
}
