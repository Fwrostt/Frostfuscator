package dev.frost.obfuscator.transformer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.encryption.NumberObfuscationTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class PhaseTwoSsaTransformerTest {
    private static final String OWNER = "fixture/PhaseTwoNumberTransformer";

    @Test
    void numberTransformerUsesSsaForMethodsAndPreservesConstantValueFields() throws Exception {
        ClassPool pool = subjectPool();
        TransformerConfig config = config(0x1234abcdL);

        new NumberObfuscationTransformer().transform(pool, new MappingCollector(), config);

        ClassNode transformed = pool.getClass(OWNER);
        FieldNode fieldNode = transformed.fields.stream().filter(field -> field.name.equals("VALUE"))
                .findFirst().orElseThrow();
        assertNull(fieldNode.value, "ConstantValue fields must be materialized in <clinit>");
        assertTrue(pool.requiresFrameComputation(OWNER));
        assertTrue(transformed.methods.stream().anyMatch(method -> method.name.equals("<clinit>")));
        assertTrue(transformed.methods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0)
                .count() >= 2, "Field materialization retains the deterministic runtime decryptors");

        Class<?> type = define(transformed);
        Field value = type.getField("VALUE");
        Method add = type.getMethod("add", int.class);
        assertEquals(37, value.getInt(null));
        assertEquals(42, add.invoke(null, 0));
        assertEquals(Integer.MIN_VALUE + 42, add.invoke(null, Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE + 42, add.invoke(null, Integer.MAX_VALUE));
    }

    @Test
    void configuredSeedProducesIdenticalMethodAndFieldRewrites() {
        ClassPool first = subjectPool();
        ClassPool second = subjectPool();
        NumberObfuscationTransformer transformer = new NumberObfuscationTransformer();

        transformer.transform(first, new MappingCollector(), config(77L));
        transformer.transform(second, new MappingCollector(), config(77L));

        assertArrayEquals(bytes(first.getClass(OWNER)), bytes(second.getClass(OWNER)));
    }

    private TransformerConfig config(long seed) {
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.of(
                "probability", 100,
                "max-per-method", 32,
                "max-per-class", 64,
                "max-method-instructions", 1_000,
                "seed", seed
        )));
        config.getOptions().put("entangle-data-flow", true);
        config.getOptions().put("spread-across-blocks", true);
        return config;
    }

    private ClassPool subjectPool() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VALUE", "I", null, 37));

        MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "add", "(I)I", null, null);
        add.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        add.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 42));
        add.instructions.add(new InsnNode(Opcodes.IADD));
        add.instructions.add(new InsnNode(Opcodes.IRETURN));
        add.maxLocals = 1;
        add.maxStack = 2;
        node.methods.add(add);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        return pool;
    }

    private Class<?> define(ClassNode node) {
        byte[] bytes = bytes(node);
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
