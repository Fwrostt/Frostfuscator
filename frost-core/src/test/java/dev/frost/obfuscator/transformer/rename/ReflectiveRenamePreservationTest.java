package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveRenamePreservationTest {

    @Test
    void preservesStringReferencedClassesButRemapsSafeClassLiterals() {
        ClassNode reflected = classNode("sample/Reflected");
        ClassNode classLiteral = classNode("sample/ClassLiteral");
        ClassNode caller = classNode("sample/Caller");
        MethodNode method = method();
        method.instructions.insertBefore(method.instructions.getLast(), new LdcInsnNode("sample.Reflected"));
        method.instructions.insertBefore(method.instructions.getLast(), new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(method.instructions.getLast(), new LdcInsnNode(Type.getObjectType(classLiteral.name)));
        method.instructions.insertBefore(method.instructions.getLast(), new InsnNode(Opcodes.POP));
        caller.methods.add(method);

        ClassPool pool = pool(reflected, classLiteral, caller);
        MappingCollector mappings = new MappingCollector();
        new ClassRenameTransformer().transform(pool, mappings, new TransformerConfig());

        assertTrue(mappings.isClassPreserved(reflected.name));
        assertFalse(mappings.hasClassMapping(reflected.name));
        assertTrue(mappings.hasClassMapping(classLiteral.name));
    }

    @Test
    void preservesFieldsWhoseNamesAppearInReflectiveStringConstants() {
        ClassNode owner = classNode("sample/Fields");
        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "secret", "I", null, null));
        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "ordinary", "I", null, null));
        ClassNode caller = classNode("sample/FieldCaller");
        MethodNode method = method();
        method.instructions.insertBefore(method.instructions.getLast(), new LdcInsnNode("secret"));
        method.instructions.insertBefore(method.instructions.getLast(), new InsnNode(Opcodes.POP));
        caller.methods.add(method);

        ClassPool pool = pool(owner, caller);
        MappingCollector mappings = new MappingCollector();
        new FieldRenameTransformer().transform(pool, mappings, new TransformerConfig());

        assertTrue(mappings.isFieldPreserved(owner.name, "secret", "I"));
        assertFalse(mappings.hasFieldMapping(owner.name, "secret", "I"));
        assertNotEquals("ordinary", mappings.getMappedField(owner.name, "ordinary", "I"));
    }

    private static ClassPool pool(ClassNode... classes) {
        ClassPool pool = new ClassPool();
        for (ClassNode classNode : classes) pool.addClass(classNode.name, classNode);
        return pool;
    }

    private static ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private static MethodNode method() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
}
