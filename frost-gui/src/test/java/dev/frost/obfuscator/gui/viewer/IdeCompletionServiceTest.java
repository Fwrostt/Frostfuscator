package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IdeCompletionServiceTest {

    @Test
    void testJdkImportSuggestions() {
        IdeCompletionService service = new IdeCompletionService();

        List<IdeCompletionService.CompletionCandidate> suggestions = service.getImportSuggestions("Base64");
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().anyMatch(c -> c.displayText().equals("Base64") && c.detail().contains("java.util.Base64")));
    }

    @Test
    void testIndexedJarClassAndMemberSuggestions() {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/acme/fixture/MyService";
        classNode.access = Opcodes.ACC_PUBLIC;

        FieldNode field = new FieldNode(Opcodes.ACC_PUBLIC, "serviceId", "I", null, null);
        classNode.fields.add(field);

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "computeData", "(Ljava/lang/String;)I", null, null);
        classNode.methods.add(method);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        byte[] bytes = writer.toByteArray();

        Map<String, byte[]> classPool = new HashMap<>();
        classPool.put("com/acme/fixture/MyService.class", bytes);

        IdeCompletionService service = new IdeCompletionService();
        service.indexJar(classPool);

        List<IdeCompletionService.CompletionCandidate> importCandidates = service.getImportSuggestions("MyService");
        assertFalse(importCandidates.isEmpty());
        assertTrue(importCandidates.stream().anyMatch(c -> c.displayText().equals("MyService")));

        List<IdeCompletionService.CompletionCandidate> memberCandidates = service.getMemberSuggestions("MyService", "compute");
        assertFalse(memberCandidates.isEmpty());
        assertTrue(memberCandidates.stream().anyMatch(c -> c.displayText().equals("computeData")));
    }
}
