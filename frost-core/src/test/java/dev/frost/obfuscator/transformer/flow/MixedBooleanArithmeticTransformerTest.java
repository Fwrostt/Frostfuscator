package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedBooleanArithmeticTransformerTest {
    private static final String OWNER = "fixture/MbaSubject";

    @Test
    void preservesIntAndLongOverflowSemanticsForEverySupportedOperation() throws Exception {
        ClassPool pool = subjectPool();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.of(
                "probability", 100,
                "rounds", 2,
                "operations", "add,sub,and,or,xor,neg",
                "max-per-method", 128,
                "max-per-class", 2048,
                "max-method-instructions", 6000,
                "max-output-method-instructions", 20000,
                "seed", 991L
        )));

        ClassNode before = pool.getClass(OWNER);
        int originalInstructions = before.methods.stream().mapToInt(method -> method.instructions.size()).sum();
        new MixedBooleanArithmeticTransformer().transform(pool, new MappingCollector(), config);
        int transformedInstructions = before.methods.stream().mapToInt(method -> method.instructions.size()).sum();

        assertEquals(1, pool.size());
        assertTrue(transformedInstructions > originalInstructions * 2,
                "MBA should materially expand the arithmetic expressions");

        Class<?> subject = load(pool);
        int[] ints = {Integer.MIN_VALUE, -1_000_003, -1, 0, 1, 999_983, Integer.MAX_VALUE};
        long[] longs = {Long.MIN_VALUE, -9_000_000_007L, -1L, 0L, 1L, 9_000_000_007L, Long.MAX_VALUE};

        for (int left : ints) {
            assertEquals(-left, invokeInt(subject, "ineg", left));
            for (int right : ints) {
                assertEquals(left + right, invokeInt(subject, "iadd", left, right));
                assertEquals(left - right, invokeInt(subject, "isub", left, right));
                assertEquals(left & right, invokeInt(subject, "iand", left, right));
                assertEquals(left | right, invokeInt(subject, "ior", left, right));
                assertEquals(left ^ right, invokeInt(subject, "ixor", left, right));
            }
        }
        for (long left : longs) {
            assertEquals(-left, invokeLong(subject, "lneg", left));
            for (long right : longs) {
                assertEquals(left + right, invokeLong(subject, "ladd", left, right));
                assertEquals(left - right, invokeLong(subject, "lsub", left, right));
                assertEquals(left & right, invokeLong(subject, "land", left, right));
                assertEquals(left | right, invokeLong(subject, "lor", left, right));
                assertEquals(left ^ right, invokeLong(subject, "lxor", left, right));
            }
        }
    }

    private ClassPool subjectPool() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";
        node.methods.add(binary("iadd", "(II)I", Opcodes.ILOAD, Opcodes.IADD, Opcodes.IRETURN, 1));
        node.methods.add(binary("isub", "(II)I", Opcodes.ILOAD, Opcodes.ISUB, Opcodes.IRETURN, 1));
        node.methods.add(binary("iand", "(II)I", Opcodes.ILOAD, Opcodes.IAND, Opcodes.IRETURN, 1));
        node.methods.add(binary("ior", "(II)I", Opcodes.ILOAD, Opcodes.IOR, Opcodes.IRETURN, 1));
        node.methods.add(binary("ixor", "(II)I", Opcodes.ILOAD, Opcodes.IXOR, Opcodes.IRETURN, 1));
        node.methods.add(unary("ineg", "(I)I", Opcodes.ILOAD, Opcodes.INEG, Opcodes.IRETURN));
        node.methods.add(binary("ladd", "(JJ)J", Opcodes.LLOAD, Opcodes.LADD, Opcodes.LRETURN, 2));
        node.methods.add(binary("lsub", "(JJ)J", Opcodes.LLOAD, Opcodes.LSUB, Opcodes.LRETURN, 2));
        node.methods.add(binary("land", "(JJ)J", Opcodes.LLOAD, Opcodes.LAND, Opcodes.LRETURN, 2));
        node.methods.add(binary("lor", "(JJ)J", Opcodes.LLOAD, Opcodes.LOR, Opcodes.LRETURN, 2));
        node.methods.add(binary("lxor", "(JJ)J", Opcodes.LLOAD, Opcodes.LXOR, Opcodes.LRETURN, 2));
        node.methods.add(unary("lneg", "(J)J", Opcodes.LLOAD, Opcodes.LNEG, Opcodes.LRETURN));

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        return pool;
    }

    private MethodNode binary(String name, String descriptor, int load, int operation, int result, int width) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                descriptor,
                null,
                null
        );
        method.instructions.add(new VarInsnNode(load, 0));
        method.instructions.add(new VarInsnNode(load, width));
        method.instructions.add(new InsnNode(operation));
        method.instructions.add(new InsnNode(result));
        return method;
    }

    private MethodNode unary(String name, String descriptor, int load, int operation, int result) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                descriptor,
                null,
                null
        );
        method.instructions.add(new VarInsnNode(load, 0));
        method.instructions.add(new InsnNode(operation));
        method.instructions.add(new InsnNode(result));
        return method;
    }

    private Class<?> load(ClassPool pool) {
        ClassNode node = pool.getClass(OWNER);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private int invokeInt(Class<?> owner, String name, int... arguments) throws Exception {
        Class<?>[] types = arguments.length == 1
                ? new Class<?>[]{int.class}
                : new Class<?>[]{int.class, int.class};
        Method method = owner.getMethod(name, types);
        Object[] boxed = arguments.length == 1
                ? new Object[]{arguments[0]}
                : new Object[]{arguments[0], arguments[1]};
        return (Integer) method.invoke(null, boxed);
    }

    private long invokeLong(Class<?> owner, String name, long... arguments) throws Exception {
        Class<?>[] types = arguments.length == 1
                ? new Class<?>[]{long.class}
                : new Class<?>[]{long.class, long.class};
        Method method = owner.getMethod(name, types);
        Object[] boxed = arguments.length == 1
                ? new Object[]{arguments[0]}
                : new Object[]{arguments[0], arguments[1]};
        return (Long) method.invoke(null, boxed);
    }
}
