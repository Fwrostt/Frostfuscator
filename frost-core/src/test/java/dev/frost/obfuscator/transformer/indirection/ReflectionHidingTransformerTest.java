package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionHidingTransformerTest {
    private static final String OWNER = "fixture/ReflectionSubject";

    @Test
    void removesDirectApiReferenceAndExecutesThroughEncryptedMethodHandleSite() throws Exception {
        ClassPool pool = subjectPool();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.of(
                "probability", 100,
                "owner-prefixes", "java/io,java/net",
                "excluded-owners", "",
                "max-per-method", 16,
                "max-per-class", 64,
                "max-method-instructions", 6000,
                "seed", 7123L
        )));

        new ReflectionHidingTransformer().transform(pool, new MappingCollector(), config);

        ClassNode transformed = pool.getClass(OWNER);
        MethodNode probe = transformed.methods.stream()
                .filter(method -> method.name.equals("probe"))
                .findFirst()
                .orElseThrow();
        assertTrue(containsInvokeDynamic(probe));
        assertFalse(containsDirectCall(probe, "java/io/InputStream", "available", "()I"));
        MethodNode uriScheme = transformed.methods.stream()
                .filter(method -> method.name.equals("uriScheme"))
                .findFirst()
                .orElseThrow();
        assertTrue(containsInvokeDynamic(uriScheme));
        assertFalse(containsDirectCall(
                uriScheme,
                "java/net/URI",
                "create",
                "(Ljava/lang/String;)Ljava/net/URI;"
        ));

        byte[] bytes = write(transformed);
        assertFalse(contains(bytes, "available".getBytes(StandardCharsets.UTF_8)),
                "The target member name should only survive in encrypted bootstrap data");
        assertFalse(contains(bytes, "getScheme".getBytes(StandardCharsets.UTF_8)));

        Class<?> subject = load(bytes);
        Method method = subject.getMethod("probe", InputStream.class);
        Object result = method.invoke(null, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        assertEquals(4, result);
        assertEquals("https", subject.getMethod("uriScheme", String.class)
                .invoke(null, "https://example.test/path"));
    }

    private ClassPool subjectPool() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";

        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe",
                "(Ljava/io/InputStream;)I",
                null,
                new String[]{"java/io/IOException"}
        );
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/io/InputStream",
                "available",
                "()I",
                false
        ));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);

        MethodNode uriScheme = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "uriScheme",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null
        );
        uriScheme.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        uriScheme.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/net/URI",
                "create",
                "(Ljava/lang/String;)Ljava/net/URI;",
                false
        ));
        uriScheme.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/net/URI",
                "getScheme",
                "()Ljava/lang/String;",
                false
        ));
        uriScheme.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(uriScheme);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        return pool;
    }

    private boolean containsInvokeDynamic(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDirectCall(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private Class<?> load(byte[] bytes) {
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
