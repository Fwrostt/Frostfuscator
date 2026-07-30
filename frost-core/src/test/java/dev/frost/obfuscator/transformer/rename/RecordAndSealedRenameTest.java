package dev.frost.obfuscator.transformer.rename;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.FrostRemapper;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordAndSealedRenameTest {

    @Test
    void keepsRecordTripletAndPermittedSubclassesSynchronized() throws Exception {
        ClassNode record = recordClass("sample/User");
        ClassNode sealedBase = ordinaryClass("sample/Shape", "java/lang/Object", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT);
        sealedBase.permittedSubclasses = List.of("sample/Circle");
        addConstructor(sealedBase, Opcodes.ACC_PROTECTED);
        ClassNode child = ordinaryClass("sample/Circle", sealedBase.name, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
        addConstructor(child, Opcodes.ACC_PUBLIC);

        ClassPool pool = new ClassPool();
        pool.addClass(record.name, record);
        pool.addClass(sealedBase.name, sealedBase);
        pool.addClass(child.name, child);
        pool.buildHierarchy();

        MappingCollector mappings = new MappingCollector();
        new FieldRenameTransformer().transform(pool, mappings, new TransformerConfig());
        new MethodRenameTransformer().transform(pool, mappings, new TransformerConfig());
        mappings.mapClass("sample/User", "renamed/UserRecord");
        mappings.mapClass("sample/Shape", "renamed/ShapeBase");
        mappings.mapClass("sample/Circle", "renamed/ShapeChild");
        mappings.mapClass("sample/Marker", "renamed/Marker");

        String componentName = mappings.getMappedRecordComponent("sample/User", "name", "Ljava/lang/String;");
        assertNotEquals("name", componentName);
        assertEquals(componentName, mappings.getMappedField("sample/User", "name", "Ljava/lang/String;"));
        assertEquals(componentName, mappings.getMappedMethod("sample/User", "name", "()Ljava/lang/String;"));

        FrostRemapper remapper = new FrostRemapper(mappings);
        Map<String, byte[]> bytecode = new HashMap<>();
        ClassNode remappedRecord = remap(record, remapper);
        ClassNode remappedBase = remap(sealedBase, remapper);
        ClassNode remappedChild = remap(child, remapper);
        for (ClassNode node : List.of(remappedRecord, remappedBase, remappedChild)) {
            bytecode.put(node.name.replace('/', '.'), write(node));
        }

        RecordComponentNode component = remappedRecord.recordComponents.getFirst();
        assertEquals(componentName, component.name);
        assertEquals("Lrenamed/Marker;", component.visibleAnnotations.getFirst().desc);
        assertEquals(List.of("renamed/ShapeChild"), remappedBase.permittedSubclasses);

        ByteMapClassLoader loader = new ByteMapClassLoader(bytecode);
        Class<?> recordClass = loader.loadClass("renamed.UserRecord");
        assertTrue(recordClass.isRecord());
        var reflectedComponent = recordClass.getRecordComponents()[0];
        assertEquals(componentName, reflectedComponent.getName());
        assertEquals(componentName, reflectedComponent.getAccessor().getName());
        Object value = recordClass.getConstructor(String.class).newInstance("Frost");
        assertEquals("Frost", reflectedComponent.getAccessor().invoke(value));

        Class<?> baseClass = loader.loadClass("renamed.ShapeBase");
        assertTrue(baseClass.isSealed());
        assertEquals("renamed.ShapeChild", baseClass.getPermittedSubclasses()[0].getName());
    }

    private static ClassNode recordClass(String name) {
        ClassNode node = ordinaryClass(name, "java/lang/Record",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD);
        RecordComponentNode component = new RecordComponentNode("name", "Ljava/lang/String;", null);
        component.visibleAnnotations = List.of(new AnnotationNode("Lsample/Marker;"));
        node.recordComponents = List.of(component);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "name", "Ljava/lang/String;", null, null));

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;)V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Record", "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                name, "name", "Ljava/lang/String;"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);

        MethodNode accessor = new MethodNode(Opcodes.ACC_PUBLIC,
                "name", "()Ljava/lang/String;", null, null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                name, "name", "Ljava/lang/String;"));
        accessor.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(accessor);
        return node;
    }

    private static ClassNode ordinaryClass(String name, String superName, int access) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = access;
        node.name = name;
        node.superName = superName;
        return node;
    }

    private static void addConstructor(ClassNode node, int access) {
        MethodNode constructor = new MethodNode(access, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                node.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
    }

    private static ClassNode remap(ClassNode original, FrostRemapper remapper) {
        ClassNode remapped = new ClassNode();
        original.accept(remapper.createClassRemapper(remapped));
        return remapped;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class ByteMapClassLoader extends ClassLoader {
        private final Map<String, byte[]> bytecode;

        private ByteMapClassLoader(Map<String, byte[]> bytecode) {
            super(RecordAndSealedRenameTest.class.getClassLoader());
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = bytecode.get(name);
            if (bytes == null) return super.findClass(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
