package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.rename.MethodRenameTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringSplittingTransformerTest {
    private static final String OWNER = "fixture/StringSubject";
    private static final String ASCII_VALUE = "interleaved-runtime-secret";
    private static final String UNICODE_VALUE = "frost-\u2744\uFE0F-\uD83D\uDD25-\uD83C\uDF19";
    private static final String FIELD_VALUE = "constant-field-secret";

    @Test
    void splitsEncodesAndScattersLiteralsAcrossCarrierClasses() throws Exception {
        ClassPool pool = subjectPool();
        Set<String> originalClasses = new HashSet<>(pool.getClassMap().keySet());
        TransformerConfig config = splittingConfig();

        new StringSplittingTransformer().transform(pool, new MappingCollector(), config);

        assertEquals(originalClasses, pool.getClassMap().keySet(),
                "String splitting must not create recognizable carrier classes");
        ClassNode subject = pool.getClass(OWNER);
        Set<String> carrierOwners = carrierCalls(subject.methods.stream()
                .filter(method -> method.name.equals("ascii"))
                .findFirst()
                .orElseThrow());
        assertEquals(1, carrierOwners.size(),
                "The original string location should contain only one relocated entry call");
        long modifiedExistingClasses = pool.getClasses().stream()
                .filter(node -> !node.name.equals(OWNER))
                .filter(node -> !node.methods.isEmpty())
                .count();
        assertTrue(modifiedExistingClasses >= 2,
                "Fragments and indirection should be distributed through existing classes");
        assertTrue(subject.methods.stream()
                .flatMap(method -> method.instructions == null
                        ? java.util.stream.Stream.empty()
                        : java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .noneMatch(instruction -> instruction instanceof LdcInsnNode ldc
                        && (ASCII_VALUE.equals(ldc.cst)
                        || UNICODE_VALUE.equals(ldc.cst)
                        || FIELD_VALUE.equals(ldc.cst))));

        Map<String, byte[]> classes = writeClasses(pool);
        for (byte[] bytes : classes.values()) {
            assertFalse(contains(bytes, ASCII_VALUE.getBytes(StandardCharsets.UTF_8)),
                    "The complete ASCII literal must not survive in any class");
            assertFalse(contains(bytes, FIELD_VALUE.getBytes(StandardCharsets.UTF_8)),
                    "The complete ConstantValue literal must not survive in any class");
        }
        assertRuntimeValues(classes);
    }

    @ParameterizedTest(name = "string splitting + {0} encryption")
    @ValueSource(strings = {"lite", "medium", "heavy", "condy", "polymorphic"})
    void remainsRuntimeCompatibleWithEveryStringEncryptionMode(String mode) throws Exception {
        ClassPool pool = subjectPool();
        new StringSplittingTransformer().transform(pool, new MappingCollector(), splittingConfig());

        TransformerConfig encryption = new TransformerConfig();
        encryption.setOptions(new LinkedHashMap<>(Map.of(
                "mode", mode,
                "min-length", 1,
                "max-method-instructions", 12_000
        )));
        new StringEncryptionTransformer().transform(pool, new MappingCollector(), encryption);

        Map<String, byte[]> classes = writeClasses(pool);
        for (byte[] bytes : classes.values()) {
            assertFalse(contains(bytes, ASCII_VALUE.getBytes(StandardCharsets.UTF_8)));
            assertFalse(contains(bytes, FIELD_VALUE.getBytes(StandardCharsets.UTF_8)));
        }
        assertRuntimeValues(classes);
    }

    @Test
    void generatedMethodsUseRenameDictionaryAndAreNotRenamedAgain() {
        ClassPool pool = subjectPool();
        MappingCollector mappings = new MappingCollector();
        TransformerConfig splitting = splittingConfig();
        splitting.setDictionary("alphabet");

        new StringSplittingTransformer().transform(pool, mappings, splitting);

        List<GeneratedMethod> generated = pool.getClasses().stream()
                .flatMap(owner -> owner.methods.stream()
                        .filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0)
                        .map(method -> new GeneratedMethod(owner.name, method.name, method.desc)))
                .toList();
        assertFalse(generated.isEmpty());
        assertTrue(generated.stream().allMatch(method -> method.name().matches("[a-z]+")),
                "Generated methods should use the configured method-rename dictionary");
        assertTrue(generated.stream().anyMatch(method -> method.name().equals("a")));

        pool.buildHierarchy();
        TransformerConfig rename = new TransformerConfig();
        rename.setDictionary("alphabet");
        rename.getOptions().put("mode", "aggressive");
        new MethodRenameTransformer().transform(pool, mappings, rename);

        for (GeneratedMethod method : generated) {
            assertTrue(mappings.isMethodPreserved(method.owner(), method.name(), method.desc()));
            assertFalse(mappings.hasMethodMapping(method.owner(), method.name(), method.desc()),
                    "Method Rename must not rename a String Splitting method twice");
        }
        assertTrue(mappings.hasMethodMapping(OWNER, "ascii", "()Ljava/lang/String;"),
                "Ordinary application methods should still be renamed");
    }

    private TransformerConfig splittingConfig() {
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.of(
                "min-length", 2,
                "min-fragments", 4,
                "max-fragments", 8,
                "max-fragment-length", 3,
                "carrier-classes", 4,
                "decoys-per-string", 1,
                "encode-fragments", true,
                "seed", 1337L
        )));
        return config;
    }

    private ClassPool subjectPool() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VALUE",
                "Ljava/lang/String;",
                null,
                FIELD_VALUE
        ));
        node.methods.add(constantMethod("ascii", ASCII_VALUE));
        node.methods.add(constantMethod("unicode", UNICODE_VALUE));

        MethodNode field = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "field",
                "()Ljava/lang/String;",
                null,
                null
        );
        field.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                OWNER,
                "VALUE",
                "Ljava/lang/String;"
        ));
        field.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(field);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        for (int i = 0; i < 4; i++) {
            ClassNode carrier = new ClassNode();
            carrier.version = Opcodes.V17;
            carrier.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
            carrier.name = "fixture/ExistingComponent" + i;
            carrier.superName = "java/lang/Object";
            pool.addClass(carrier.name, carrier);
        }
        return pool;
    }

    private MethodNode constantMethod(String name, String value) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                "()Ljava/lang/String;",
                null,
                null
        );
        method.instructions.add(new LdcInsnNode(value));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private Set<String> carrierCalls(MethodNode method) {
        Set<String> owners = new HashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.desc.equals("()Ljava/lang/String;")
                    && !call.owner.equals(OWNER)) {
                owners.add(call.owner);
            }
        }
        return owners;
    }

    private Map<String, byte[]> writeClasses(ClassPool pool) {
        Map<String, byte[]> result = new HashMap<>();
        for (ClassNode node : pool.getClasses()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };
            node.accept(writer);
            result.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        return result;
    }

    private void assertRuntimeValues(Map<String, byte[]> classes) throws Exception {
        ClassLoader loader = new ByteMapClassLoader(classes);
        Class<?> subject = Class.forName(OWNER.replace('/', '.'), true, loader);
        assertEquals(ASCII_VALUE, invoke(subject, "ascii"));
        assertEquals(UNICODE_VALUE, invoke(subject, "unicode"));
        assertEquals(FIELD_VALUE, invoke(subject, "field"));
    }

    private Object invoke(Class<?> owner, String methodName) throws Exception {
        Method method = owner.getMethod(methodName);
        return method.invoke(null);
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

    private static final class ByteMapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteMapClassLoader(Map<String, byte[]> classes) {
            super(StringSplittingTransformerTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private record GeneratedMethod(String owner, String name, String desc) {
    }
}
