package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowObfuscationTransformerTest {
    private static final String OWNER = "fixture/FlowSubject";

    @Test
    void rollbackPreservesMethodIdentityWhenSafetyAnalysisRejectsARewrite() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/RejectedFlow";
        node.superName = "java/lang/Object";
        MethodNode rejected = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "unsafe", "()V", null, new String[] {"java/lang/Exception"});
        rejected.instructions.add(new InsnNode(Opcodes.POP));
        rejected.instructions.add(new InsnNode(Opcodes.RETURN));
        rejected.maxLocals = 0;
        rejected.maxStack = 1;
        node.methods.add(rejected);

        ClassPool pool = new ClassPool();
        pool.addClass(node.name, node);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("mode", "lite"),
                Map.entry("flatten", false),
                Map.entry("exception-guards", false),
                Map.entry("stack-noise", false),
                Map.entry("predicate-rate", 0),
                Map.entry("min-method-instructions", 1),
                Map.entry("max-method-instructions", 100),
                Map.entry("seed", 7)
        )));

        new FlowObfuscationTransformer().transform(pool, new MappingCollector(), config);

        MethodNode restored = node.methods.getFirst();
        assertEquals("unsafe", restored.name);
        assertEquals("()V", restored.desc);
        assertEquals(1, restored.exceptions.size());
        assertEquals("java/lang/Exception", restored.exceptions.getFirst());
    }

    @Test
    void flattensBranchesIntoDispatcherAndUsesLivePredicateState() throws Exception {
        ClassPool pool = new ClassPool();
        ClassNode subject = subject();
        pool.addClass(subject.name, subject);

        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("mode", "heavy"),
                Map.entry("flatten", true),
                Map.entry("flatten-probability", 100),
                Map.entry("flatten-min-blocks", 3),
                Map.entry("flatten-max-blocks", 64),
                Map.entry("flatten-min-complexity", 1),
                Map.entry("flatten-cost-budget", 2048),
                Map.entry("dispatcher-styles", "lookup"),
                Map.entry("partial-flattening-rate", 0),
                Map.entry("flatten-hot-loops", true),
                Map.entry("state-reencode-rate", 100),
                Map.entry("fake-dispatcher-states", 4),
                Map.entry("block-clone-rate", 100),
                Map.entry("exception-guards", true),
                Map.entry("stack-noise", true),
                Map.entry("predicate-rate", 100),
                Map.entry("max-predicates-per-method", 8),
                Map.entry("predicate-families", "arithmetic,bitwise,reversible,modular,lookup-table,stateful,argument-derived,interprocedural"),
                Map.entry("predicate-cost-budget", 1024),
                Map.entry("predicate-camouflage-rate", 100),
                Map.entry("predicate-local-rate", 50),
                Map.entry("predicate-sources", "volatile,thread,environment,time"),
                Map.entry("min-method-instructions", 1),
                Map.entry("max-method-instructions", 5000),
                Map.entry("max-output-method-instructions", 16000),
                Map.entry("seed", 424242)
        )));

        new FlowObfuscationTransformer().transform(pool, new MappingCollector(), config);

        MethodNode transformed = subject.methods.stream()
                .filter(method -> method.name.equals("compute"))
                .findFirst()
                .orElseThrow();
        assertTrue(contains(transformed, LookupSwitchInsnNode.class),
                "Flattening should introduce a randomized lookup-switch dispatcher");
        assertTrue(calls(transformed, "java/lang/Thread", "getId"),
                "Predicates should consume live multithreading state");
        assertTrue(calls(transformed, "java/lang/Runtime", "availableProcessors"),
                "Predicates should consume native environment state");
        assertTrue(calls(transformed, "java/lang/System", "nanoTime"),
                "Predicates should consume changing runtime state");

        Class<?> loaded = load(subject);
        Method compute = loaded.getMethod("compute", int.class);
        Object instance = loaded.getConstructor().newInstance();
        for (int value = -100; value <= 100; value++) {
            int expected = value < 0
                    ? -value
                    : value % 2 == 0 ? value + 2 : value * 3;
            assertEquals(expected, compute.invoke(instance, value), "value=" + value);
        }

        Method loopSwitch = loaded.getMethod("loopSwitch", int.class);
        for (int value = 0; value <= 100; value++) {
            assertEquals(expectedLoopSwitch(value), loopSwitch.invoke(null, value), "loop=" + value);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "table", "computed", "nested", "split"})
    void everyDispatcherStylePreservesLoopsAndSwitches(String style) throws Exception {
        ClassPool pool = new ClassPool();
        ClassNode subject = subject();
        pool.addClass(subject.name, subject);

        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("mode", "heavy"),
                Map.entry("flatten", true),
                Map.entry("flatten-probability", 100),
                Map.entry("flatten-min-blocks", 3),
                Map.entry("flatten-max-blocks", 64),
                Map.entry("flatten-min-complexity", 1),
                Map.entry("flatten-cost-budget", 2048),
                Map.entry("dispatcher-styles", style),
                Map.entry("partial-flattening-rate", 0),
                Map.entry("flatten-hot-loops", true),
                Map.entry("state-reencode-rate", 100),
                Map.entry("fake-dispatcher-states", 2),
                Map.entry("block-clone-rate", 50),
                Map.entry("exception-guards", false),
                Map.entry("stack-noise", false),
                Map.entry("predicate-rate", 0),
                Map.entry("max-predicates-per-method", 0),
                Map.entry("predicate-families", "bitwise"),
                Map.entry("predicate-cost-budget", 64),
                Map.entry("predicate-camouflage-rate", 0),
                Map.entry("min-method-instructions", 1),
                Map.entry("max-method-instructions", 5000),
                Map.entry("max-output-method-instructions", 16000),
                Map.entry("seed", 9000 + style.hashCode())
        )));

        new FlowObfuscationTransformer().transform(pool, new MappingCollector(), config);
        Class<?> loaded = load(subject);
        Method loopSwitch = loaded.getMethod("loopSwitch", int.class);
        for (int value = 0; value <= 40; value++) {
            assertEquals(expectedLoopSwitch(value), loopSwitch.invoke(null, value),
                    style + " loop=" + value);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arithmetic",
            "bitwise",
            "reversible",
            "modular",
            "lookup-table",
            "stateful",
            "argument-derived",
            "interprocedural"
    })
    void everyPredicateFamilyIsAlgebraicallyGuaranteed(String family) throws Exception {
        ClassPool pool = new ClassPool();
        ClassNode subject = subject();
        pool.addClass(subject.name, subject);
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("mode", "heavy"),
                Map.entry("flatten", false),
                Map.entry("exception-guards", true),
                Map.entry("stack-noise", false),
                Map.entry("predicate-rate", 100),
                Map.entry("max-predicates-per-method", 12),
                Map.entry("predicate-families", family),
                Map.entry("predicate-cost-budget", 512),
                Map.entry("predicate-camouflage-rate", 100),
                Map.entry("predicate-local-rate", 50),
                Map.entry("predicate-sources", "volatile,thread,environment,time"),
                Map.entry("heavy-predicates-in-loops", true),
                Map.entry("min-method-instructions", 1),
                Map.entry("max-method-instructions", 5000),
                Map.entry("max-output-method-instructions", 16000),
                Map.entry("seed", 1337 + family.hashCode())
        )));

        new FlowObfuscationTransformer().transform(pool, new MappingCollector(), config);
        Class<?> loaded = load(subject);
        Object instance = loaded.getConstructor().newInstance();
        Method compute = loaded.getMethod("compute", int.class);
        for (int value = -40; value <= 40; value++) {
            int expected = value < 0
                    ? -value
                    : value % 2 == 0 ? value + 2 : value * 3;
            assertEquals(expected, compute.invoke(instance, value), family + " value=" + value);
        }
    }

    @Test
    void fixedSeedProducesIdenticalLayeredFlowBytecode() {
        TransformerConfig firstConfig = deterministicConfig();
        TransformerConfig secondConfig = deterministicConfig();
        ClassPool firstPool = new ClassPool();
        ClassPool secondPool = new ClassPool();
        ClassNode first = subject();
        ClassNode second = subject();
        firstPool.addClass(first.name, first);
        secondPool.addClass(second.name, second);

        new FlowObfuscationTransformer().transform(firstPool, new MappingCollector(), firstConfig);
        new FlowObfuscationTransformer().transform(secondPool, new MappingCollector(), secondConfig);

        assertArrayEquals(write(first), write(second));
    }

    @Test
    void partialFlatteningLeavesHotLoopRegionSemanticallyIntact() throws Exception {
        ClassPool pool = new ClassPool();
        ClassNode subject = subject();
        pool.addClass(subject.name, subject);
        TransformerConfig config = deterministicConfig();
        config.getOptions().put("dispatcher-styles", "nested");
        config.getOptions().put("partial-flattening-rate", 100);
        config.getOptions().put("partial-region-rate", 35);
        config.getOptions().put("flatten-hot-loops", false);
        config.getOptions().put("seed", 11235813);

        new FlowObfuscationTransformer().transform(pool, new MappingCollector(), config);
        Class<?> loaded = load(subject);
        Method loopSwitch = loaded.getMethod("loopSwitch", int.class);
        for (int value = 0; value <= 80; value++) {
            assertEquals(expectedLoopSwitch(value), loopSwitch.invoke(null, value),
                    "partial loop=" + value);
        }
    }

    private ClassNode subject() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = OWNER;
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false
        ));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxLocals = 1;
        constructor.maxStack = 1;
        node.methods.add(constructor);

        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "compute",
                "(I)I",
                null,
                null
        );
        LabelNode negative = new LabelNode(new Label());
        LabelNode even = new LabelNode(new Label());
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFLT, negative));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IREM));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, even));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new InsnNode(Opcodes.IMUL));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(even);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(negative);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.INEG));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;
        node.methods.add(method);
        node.methods.add(loopSwitchMethod());
        return node;
    }

    private MethodNode loopSwitchMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "loopSwitch",
                "(I)I",
                null,
                null
        );
        LabelNode loop = new LabelNode(new Label());
        LabelNode caseZero = new LabelNode(new Label());
        LabelNode caseOne = new LabelNode(new Label());
        LabelNode caseTwo = new LabelNode(new Label());
        LabelNode increment = new LabelNode(new Label());
        LabelNode done = new LabelNode(new Label());

        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new InsnNode(Opcodes.IREM));
        method.instructions.add(new TableSwitchInsnNode(
                0,
                2,
                caseTwo,
                caseZero,
                caseOne,
                caseTwo
        ));
        method.instructions.add(caseZero);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, increment));
        method.instructions.add(caseOne);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ISUB));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, increment));
        method.instructions.add(caseTwo);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IXOR));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(increment);
        method.instructions.add(new IincInsnNode(2, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        method.instructions.add(done);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 3;
        method.maxStack = 2;
        return method;
    }

    private int expectedLoopSwitch(int limit) {
        int result = 0;
        for (int index = 0; index < limit; index++) {
            result = switch (index % 3) {
                case 0 -> result + index;
                case 1 -> result - index;
                default -> result ^ index;
            };
        }
        return result;
    }

    private TransformerConfig deterministicConfig() {
        TransformerConfig config = new TransformerConfig();
        config.setOptions(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("mode", "heavy"),
                Map.entry("flatten", true),
                Map.entry("flatten-probability", 100),
                Map.entry("flatten-min-blocks", 3),
                Map.entry("flatten-max-blocks", 64),
                Map.entry("flatten-min-complexity", 1),
                Map.entry("flatten-cost-budget", 2048),
                Map.entry("dispatcher-styles", "lookup,table,computed,nested,split"),
                Map.entry("partial-flattening-rate", 35),
                Map.entry("flatten-hot-loops", false),
                Map.entry("state-reencode-rate", 75),
                Map.entry("fake-dispatcher-states", 3),
                Map.entry("block-clone-rate", 50),
                Map.entry("exception-guards", true),
                Map.entry("stack-noise", true),
                Map.entry("predicate-rate", 50),
                Map.entry("max-predicates-per-method", 8),
                Map.entry("predicate-families", "arithmetic,bitwise,reversible,modular,lookup-table,stateful,argument-derived,interprocedural"),
                Map.entry("predicate-cost-budget", 256),
                Map.entry("predicate-camouflage-rate", 35),
                Map.entry("predicate-local-rate", 30),
                Map.entry("min-method-instructions", 1),
                Map.entry("max-method-instructions", 5000),
                Map.entry("max-output-method-instructions", 16000),
                Map.entry("seed", 8675309)
        )));
        return config;
    }

    private boolean contains(MethodNode method, Class<? extends AbstractInsnNode> type) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (type.isInstance(instruction)) {
                return true;
            }
        }
        return false;
    }

    private boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> load(ClassNode node) {
        byte[] bytes = write(node);
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
