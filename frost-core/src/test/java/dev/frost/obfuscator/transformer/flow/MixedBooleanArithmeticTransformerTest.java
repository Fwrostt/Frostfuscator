package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                "polynomial-degree", 3,
                "zero-terms", 2,
                "operations", "add,sub,mul,and,or,xor,neg",
                "max-per-method", 128,
                "max-per-class", 2048,
                "max-method-instructions", 6000,
                "max-output-method-instructions", 20000,
                "seed", 991L
        )));
        config.getOptions().put("conditionals", false);
        config.getOptions().put("switch-keys", false);
        config.getOptions().put("long-comparisons", false);

        ClassNode before = pool.getClass(OWNER);
        int originalInstructions = before.methods.stream().mapToInt(method -> method.instructions.size()).sum();
        new MixedBooleanArithmeticTransformer().transform(pool, new MappingCollector(), config);
        int transformedInstructions = before.methods.stream().mapToInt(method -> method.instructions.size()).sum();

        assertEquals(1, pool.size());
        assertTrue(pool.requiresFrameComputation(OWNER));
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
                assertEquals(left * right, invokeInt(subject, "imul", left, right));
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
                assertEquals(left * right, invokeLong(subject, "lmul", left, right));
                assertEquals(left & right, invokeLong(subject, "land", left, right));
                assertEquals(left | right, invokeLong(subject, "lor", left, right));
                assertEquals(left ^ right, invokeLong(subject, "lxor", left, right));
            }
        }
    }

    @Test
    void preservesIntegerBranchesSwitchesAndLongComparisonsAcrossExtremeValues() throws Exception {
        ClassPool pool = subjectPool();
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("probability", 100),
                Map.entry("rounds", 1),
                Map.entry("polynomial-degree", 4),
                Map.entry("zero-terms", 2),
                Map.entry("operations", ""),
                Map.entry("conditionals", true),
                Map.entry("switch-keys", true),
                Map.entry("long-comparisons", true),
                Map.entry("max-per-method", 128),
                Map.entry("max-per-class", 2048),
                Map.entry("max-method-instructions", 6000),
                Map.entry("max-output-method-instructions", 25000),
                Map.entry("seed", 773L)
        )));

        ClassNode node = pool.getClass(OWNER);
        int originalInstructions = node.methods.stream().mapToInt(method -> method.instructions.size()).sum();
        new MixedBooleanArithmeticTransformer().transform(pool, new MappingCollector(), config);
        int transformedInstructions = node.methods.stream().mapToInt(method -> method.instructions.size()).sum();
        long polynomialMultiplications = node.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .filter(instruction -> instruction.getOpcode() == Opcodes.IMUL
                        || instruction.getOpcode() == Opcodes.LMUL)
                .count();
        assertTrue(transformedInstructions > originalInstructions * 5);
        assertTrue(pool.requiresFrameComputation(OWNER));
        assertTrue(node.methods.stream().allMatch(method -> method.instructions.size() <= 20_000));
        assertTrue(polynomialMultiplications > 20, "Conditional operands should contain dynamic polynomials");

        Class<?> subject = load(pool);
        int[] values = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
        for (int left : values) {
            assertEquals(left == 0 ? 1 : 0, invokeInt(subject, "ifeq", left));
            assertEquals(left != 0 ? 1 : 0, invokeInt(subject, "ifne", left));
            assertEquals(left < 0 ? 1 : 0, invokeInt(subject, "iflt", left));
            assertEquals(left <= 0 ? 1 : 0, invokeInt(subject, "ifle", left));
            assertEquals(left > 0 ? 1 : 0, invokeInt(subject, "ifgt", left));
            assertEquals(left >= 0 ? 1 : 0, invokeInt(subject, "ifge", left));
            int expectedSwitch = left == -1 ? 11 : left == 0 ? 12 : left == 7 ? 13 : 14;
            assertEquals(expectedSwitch, invokeInt(subject, "switchKey", left));
            for (int right : values) {
                assertEquals(left == right ? 1 : 0, invokeInt(subject, "icmpeq", left, right));
                assertEquals(left != right ? 1 : 0, invokeInt(subject, "icmpne", left, right));
                assertEquals(left < right ? 1 : 0, invokeInt(subject, "icmplt", left, right));
                assertEquals(left <= right ? 1 : 0, invokeInt(subject, "icmple", left, right));
                assertEquals(left > right ? 1 : 0, invokeInt(subject, "icmpgt", left, right));
                assertEquals(left >= right ? 1 : 0, invokeInt(subject, "icmpge", left, right));
            }
        }
        Method compareLong = subject.getMethod("lcompare", long.class, long.class);
        long[] longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
        for (long left : longs) {
            for (long right : longs) {
                assertEquals(Long.compare(left, right), compareLong.invoke(null, left, right));
            }
        }
    }

    @Test
    void producesDifferentPolymorphicFormsForDifferentSeeds() {
        ClassPool first = subjectPool();
        ClassPool second = subjectPool();
        TransformerConfig firstConfig = arithmeticOnlyConfig(101L);
        TransformerConfig secondConfig = arithmeticOnlyConfig(202L);

        MixedBooleanArithmeticTransformer transformer = new MixedBooleanArithmeticTransformer();
        transformer.transform(first, new MappingCollector(), firstConfig);
        transformer.transform(second, new MappingCollector(), secondConfig);

        assertNotEquals(fingerprint(first.getClass(OWNER)), fingerprint(second.getClass(OWNER)),
                "Per-build seeds should select different identities and coefficients");
    }

    private TransformerConfig arithmeticOnlyConfig(long seed) {
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("probability", 100),
                Map.entry("rounds", 1),
                Map.entry("polynomial-degree", 3),
                Map.entry("zero-terms", 2),
                Map.entry("operations", "add,sub,mul,and,or,xor,neg"),
                Map.entry("conditionals", false),
                Map.entry("switch-keys", false),
                Map.entry("long-comparisons", false),
                Map.entry("max-per-method", 128),
                Map.entry("max-per-class", 2048),
                Map.entry("seed", seed)
        )));
        return config;
    }

    private String fingerprint(ClassNode owner) {
        StringBuilder fingerprint = new StringBuilder();
        for (MethodNode method : owner.methods) {
            fingerprint.append(method.name).append(':');
            for (AbstractInsnNode instruction : method.instructions) {
                fingerprint.append(instruction.getOpcode()).append('/');
                if (instruction instanceof LdcInsnNode constant) fingerprint.append(constant.cst);
                if (instruction instanceof IntInsnNode integer) fingerprint.append(integer.operand);
                fingerprint.append(';');
            }
        }
        return fingerprint.toString();
    }

    private ClassPool subjectPool() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";
        node.methods.add(binary("iadd", "(II)I", Opcodes.ILOAD, Opcodes.IADD, Opcodes.IRETURN, 1));
        node.methods.add(binary("isub", "(II)I", Opcodes.ILOAD, Opcodes.ISUB, Opcodes.IRETURN, 1));
        node.methods.add(binary("imul", "(II)I", Opcodes.ILOAD, Opcodes.IMUL, Opcodes.IRETURN, 1));
        node.methods.add(binary("iand", "(II)I", Opcodes.ILOAD, Opcodes.IAND, Opcodes.IRETURN, 1));
        node.methods.add(binary("ior", "(II)I", Opcodes.ILOAD, Opcodes.IOR, Opcodes.IRETURN, 1));
        node.methods.add(binary("ixor", "(II)I", Opcodes.ILOAD, Opcodes.IXOR, Opcodes.IRETURN, 1));
        node.methods.add(unary("ineg", "(I)I", Opcodes.ILOAD, Opcodes.INEG, Opcodes.IRETURN));
        node.methods.add(binary("ladd", "(JJ)J", Opcodes.LLOAD, Opcodes.LADD, Opcodes.LRETURN, 2));
        node.methods.add(binary("lsub", "(JJ)J", Opcodes.LLOAD, Opcodes.LSUB, Opcodes.LRETURN, 2));
        node.methods.add(binary("lmul", "(JJ)J", Opcodes.LLOAD, Opcodes.LMUL, Opcodes.LRETURN, 2));
        node.methods.add(binary("land", "(JJ)J", Opcodes.LLOAD, Opcodes.LAND, Opcodes.LRETURN, 2));
        node.methods.add(binary("lor", "(JJ)J", Opcodes.LLOAD, Opcodes.LOR, Opcodes.LRETURN, 2));
        node.methods.add(binary("lxor", "(JJ)J", Opcodes.LLOAD, Opcodes.LXOR, Opcodes.LRETURN, 2));
        node.methods.add(unary("lneg", "(J)J", Opcodes.LLOAD, Opcodes.LNEG, Opcodes.LRETURN));
        addConditionMethods(node);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        return pool;
    }

    private void addConditionMethods(ClassNode node) {
        node.methods.add(unaryBranch("ifeq", Opcodes.IFEQ));
        node.methods.add(unaryBranch("ifne", Opcodes.IFNE));
        node.methods.add(unaryBranch("iflt", Opcodes.IFLT));
        node.methods.add(unaryBranch("ifle", Opcodes.IFLE));
        node.methods.add(unaryBranch("ifgt", Opcodes.IFGT));
        node.methods.add(unaryBranch("ifge", Opcodes.IFGE));
        node.methods.add(binaryBranch("icmpeq", Opcodes.IF_ICMPEQ));
        node.methods.add(binaryBranch("icmpne", Opcodes.IF_ICMPNE));
        node.methods.add(binaryBranch("icmplt", Opcodes.IF_ICMPLT));
        node.methods.add(binaryBranch("icmple", Opcodes.IF_ICMPLE));
        node.methods.add(binaryBranch("icmpgt", Opcodes.IF_ICMPGT));
        node.methods.add(binaryBranch("icmpge", Opcodes.IF_ICMPGE));

        MethodNode compare = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lcompare", "(JJ)I", null, null);
        compare.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        compare.instructions.add(new InsnNode(Opcodes.LCMP));
        compare.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(compare);

        LabelNode minusOne = new LabelNode();
        LabelNode zero = new LabelNode();
        LabelNode seven = new LabelNode();
        LabelNode fallback = new LabelNode();
        MethodNode switchKey = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "switchKey", "(I)I", null, null);
        switchKey.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        switchKey.instructions.add(new LookupSwitchInsnNode(fallback,
                new int[]{-1, 0, 7}, new LabelNode[]{minusOne, zero, seven}));
        switchKey.instructions.add(minusOne);
        switchKey.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 11));
        switchKey.instructions.add(new InsnNode(Opcodes.IRETURN));
        switchKey.instructions.add(zero);
        switchKey.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 12));
        switchKey.instructions.add(new InsnNode(Opcodes.IRETURN));
        switchKey.instructions.add(seven);
        switchKey.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 13));
        switchKey.instructions.add(new InsnNode(Opcodes.IRETURN));
        switchKey.instructions.add(fallback);
        switchKey.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 14));
        switchKey.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(switchKey);
    }

    private MethodNode unaryBranch(String name, int opcode) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "(I)I", null, null);
        LabelNode matched = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(opcode, matched));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(matched);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private MethodNode binaryBranch(String name, int opcode) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "(II)I", null, null);
        LabelNode matched = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(opcode, matched));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(matched);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
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
