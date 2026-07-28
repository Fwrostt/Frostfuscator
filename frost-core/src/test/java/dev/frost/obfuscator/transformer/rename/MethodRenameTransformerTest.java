package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MethodRenameTransformerTest {

    @Test
    void overrideGroupNameDoesNotCollideInChildClass() {
        ClassNode parent = classNode("example/Parent", "java/lang/Object");
        parent.methods.add(method("work"));

        ClassNode child = classNode("example/Child", parent.name);
        child.methods.add(method("work"));
        child.methods.add(method("a"));

        ClassPool pool = new ClassPool();
        pool.addClass(parent.name, parent);
        pool.addClass(child.name, child);
        pool.buildHierarchy();

        // String Splitting and similar passes add helpers after the hierarchy is built.
        parent.methods.add(method("lateOverride"));
        child.methods.add(method("lateOverride"));

        MappingCollector mappings = new MappingCollector();
        new MethodRenameTransformer().transform(pool, mappings, new TransformerConfig());

        assertEquals("b", mappings.getMappedMethod(parent.name, "work", "()V"));
        assertEquals("b", mappings.getMappedMethod(child.name, "work", "()V"));
        String lateName = mappings.getMappedMethod(parent.name, "lateOverride", "()V");
        assertNotNull(lateName);
        assertEquals(lateName, mappings.getMappedMethod(child.name, "lateOverride", "()V"));
    }

    private ClassNode classNode(String name, String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

    private MethodNode method(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
}
