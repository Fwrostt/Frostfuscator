package dev.frost.obfuscator.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarProcessorFrameComputationTest {

    @TempDir
    Path tempDir;

    @Test
    void recomputesFramesForStructuralFlowChangesUsingPoolHierarchy() throws Exception {
        ClassPool pool = new ClassPool();
        pool.addClass("sample/Base", classWithConstructor("sample/Base", "java/lang/Object"));
        pool.addClass("sample/Left", classWithConstructor("sample/Left", "sample/Base"));
        pool.addClass("sample/Right", classWithConstructor("sample/Right", "sample/Base"));

        ClassNode target = newClass("sample/FlowTarget", "java/lang/Object");
        MethodNode choose = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "choose", "(Z)Lsample/Base;", null, null);
        LabelNode right = new LabelNode();
        LabelNode merge = new LabelNode();
        choose.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        choose.instructions.add(new JumpInsnNode(Opcodes.IFEQ, right));
        addNewInstance(choose, "sample/Left");
        choose.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        choose.instructions.add(right);
        addNewInstance(choose, "sample/Right");
        choose.instructions.add(merge);
        choose.instructions.add(new InsnNode(Opcodes.ARETURN));
        target.methods.add(choose);
        pool.addClass(target.name, target);
        pool.markFramesDirty(target.name);

        Path output = tempDir.resolve("frames.jar");
        new JarProcessor().writeJar(pool, output);

        Map<String, byte[]> classes = readClasses(output);
        ClassNode written = new ClassNode();
        new ClassReader(classes.get("sample.FlowTarget")).accept(written, ClassReader.EXPAND_FRAMES);
        long frames = written.methods.stream()
                .filter(method -> method.name.equals("choose"))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(FrameNode.class::isInstance)
                .count();
        assertTrue(frames > 0, "structurally modified method should contain computed frames");

        Class<?> loaded = new ByteMapClassLoader(classes).loadClass("sample.FlowTarget");
        assertEquals("sample.Left", loaded.getMethod("choose", boolean.class).invoke(null, true).getClass().getName());
        assertEquals("sample.Right", loaded.getMethod("choose", boolean.class).invoke(null, false).getClass().getName());
    }

    private static ClassNode classWithConstructor(String name, String superName) {
        ClassNode node = newClass(name, superName);
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        return node;
    }

    private static ClassNode newClass(String name, String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

    private static void addNewInstance(MethodNode method, String type) {
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, type));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false));
    }

    private static Map<String, byte[]> readClasses(Path jarPath) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                try (var input = jar.getInputStream(entry)) {
                    classes.put(name, input.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static final class ByteMapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteMapClassLoader(Map<String, byte[]> classes) {
            super(JarProcessorFrameComputationTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytecode = classes.get(name);
            if (bytecode == null) return super.findClass(name);
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
