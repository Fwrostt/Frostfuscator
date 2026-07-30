package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Control flow obfuscation.
 *
 * Modes:
 *   lite   - opaque predicates on unconditional jumps.
 *   medium - + number obfuscation patterns.
 *   heavy  - + scattered predicates + conditional-to-switch + optional flattening.
 *
 * Opaque predicates mix volatile class state, live thread state, monotonic
 * time, and environment properties into algebraic invariants. The state is
 * externally observable and changes between executions, while the predicate
 * result remains guaranteed so application semantics never depend on a race.
 */
public class FlowObfuscationTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ThreadLocal<Random> ACTIVE_RANDOM = new ThreadLocal<>();
    private static final ThreadLocal<Set<Integer>> ACTIVE_CLASS_KEYS = new ThreadLocal<>();
    private static final ThreadLocal<FlowMetrics> ACTIVE_METRICS = new ThreadLocal<>();

    @Override
    public String getName() {
        return "flow-obfuscation";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        FlowMetrics metrics = apply(context.pool(), context.config());
        context.stats().add("opaquePredicates", metrics.predicates.sum());
        context.stats().add("flattenedMethods", metrics.flattenedMethods.sum());
        context.stats().add("partiallyFlattenedMethods", metrics.partiallyFlattenedMethods.sum());
        context.stats().add("fakeDispatcherStates", metrics.fakeStates.sum());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private FlowMetrics apply(ClassPool pool, TransformerConfig config) {
        long configuredSeed = getLongOption(config, "seed", 0L);
        FlowMetrics metrics = new FlowMetrics();
        long runSeed = configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed;
        transformConfigured(pool, config, runSeed, metrics);
        return metrics;
    }

    private void transformConfigured(ClassPool pool, TransformerConfig config, long runSeed, FlowMetrics metrics) {
        String mode = config.getOption("mode", "medium").toLowerCase();
        boolean lite = mode.equals("lite");
        boolean medium = mode.equals("medium") || mode.equals("heavy");
        boolean heavy = mode.equals("heavy");
        boolean flatten = getBooleanOption(config, "flatten", heavy);
        boolean exceptionGuards = getBooleanOption(config, "exception-guards", heavy);
        boolean stackNoise = getBooleanOption(config, "stack-noise", heavy);
        int minMethodInstructions = Math.max(0, getIntOption(config, "min-method-instructions", heavy ? 12 : 6));
        int maxMethodInstructions = getIntOption(config, "max-method-instructions", 5000);
        int predicateRate = clamp(getIntOption(config, "predicate-rate", heavy ? 8 : 4), 0, 100);
        int maxPredicatesPerMethod = Math.max(0, getIntOption(config, "max-predicates-per-method", heavy ? 24 : 8));
        int flattenProbability = clamp(getIntOption(config, "flatten-probability", heavy ? 65 : 0), 0, 100);
        int flattenMinimumBlocks = clamp(getIntOption(config, "flatten-min-blocks", 3), 2, 256);
        int flattenMaximumBlocks = clamp(getIntOption(config, "flatten-max-blocks", 64), flattenMinimumBlocks, 512);
        int maximumOutputInstructions = clamp(
                getIntOption(config, "max-output-method-instructions", Math.max(12_000, maxMethodInstructions)),
                maxMethodInstructions,
                65_000
        );
        PredicatePolicy predicatePolicy = predicatePolicy(config);
        FlattenPolicy flattenPolicy = flattenPolicy(
                config,
                flattenProbability,
                flattenMinimumBlocks,
                flattenMaximumBlocks,
                maximumOutputInstructions
        );
        boolean includeSynthetic = getBooleanOption(config, "include-synthetic", false);

        pool.forEachClass(classNode -> {
            ACTIVE_RANDOM.set(new Random(runSeed ^ classNode.name.hashCode()));
            ACTIVE_CLASS_KEYS.set(new HashSet<>());
            try {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                return;
            }
            if (AccessHelper.isInterface(classNode.access)) {
                return;
            }

            PredicateClassContext predicateContext = ensurePredicateContext(classNode, predicatePolicy);
            boolean changed = false;
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (!includeSynthetic && (method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if (method.instructions.size() < minMethodInstructions) continue;
                if (method.instructions.size() > maxMethodInstructions) continue;

                int methodIndex = classNode.methods.indexOf(method);
                MethodNode originalMethod = copyMethod(method);
                FlowMetrics methodMetrics = new FlowMetrics();
                ACTIVE_METRICS.set(methodMetrics);
                try {
                    PredicateBudget predicateBudget = new PredicateBudget(predicatePolicy.costBudget());
                    LoopProfile loopProfile = loopProfile(method);
                    // Flatten before exception guards add synthetic handlers.
                    // The old ordering caused flattening to silently skip every
                    // method whenever exception-guards was enabled.
                    if (heavy && flatten && random().nextInt(100) < flattenPolicy.probability()) {
                        flattenMethod(
                                predicateContext,
                                method,
                                flattenPolicy,
                                loopProfile
                        );
                    }
                    if (!AccessHelper.isInitializer(method)) {
                        if (exceptionGuards) {
                            exceptionGuard(
                                    classNode.name,
                                    predicateContext,
                                    method,
                                    predicatePolicy,
                                    predicateBudget,
                                    loopProfile,
                                    maximumOutputInstructions
                            );
                        }
                        if (stackNoise) stackNoise(method);
                    }
                    if (lite || medium || heavy) {
                        opaqueGoto(
                                classNode.name,
                                predicateContext,
                                method,
                                predicatePolicy,
                                predicateBudget,
                                loopProfile,
                                maximumOutputInstructions
                        );
                    }
                    if (medium) numberObfuscation(method);
                    if (heavy) {
                        conditionalToSwitch(method);
                        scatteredPredicates(
                                classNode.name,
                                predicateContext,
                                method,
                                predicateRate,
                                maxPredicatesPerMethod,
                                predicatePolicy,
                                predicateBudget,
                                loopProfile,
                                maximumOutputInstructions
                        );
                    }
                    analyzeWithHeadroom(classNode.name, method, true);
                    metrics.add(methodMetrics);
                    changed = true;
                } catch (Exception e) {
                    if (methodIndex >= 0) classNode.methods.set(methodIndex, originalMethod);
                    metrics.skippedMethods.increment();
                    detail("Kept original bytecode for {}.{} after flow safety check: {}",
                            classNode.name, method.name, e.getMessage());
                }
            }
            if (changed) {
                pool.markFramesDirty(classNode.name);
            }
            } finally {
                ACTIVE_RANDOM.remove();
                ACTIVE_CLASS_KEYS.remove();
                ACTIVE_METRICS.remove();
            }
        });
        if (metrics.skippedMethods.sum() > 0) {
            log("Safely retained {} method(s) that were not compatible with the selected flow policy",
                    metrics.skippedMethods.sum());
        }
    }

    // region Lite: opaque predicates on unconditional jumps

    private void opaqueGoto(String owner,
                            PredicateClassContext context,
                            MethodNode method,
                            PredicatePolicy predicatePolicy,
                            PredicateBudget predicateBudget,
                            LoopProfile loopProfile,
                            int maximumOutputInstructions) {
        List<JumpInsnNode> gotos = new ArrayList<>();
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (insn.getOpcode() == Opcodes.GOTO) {
                gotos.add((JumpInsnNode) insn);
            }
            insn = insn.getNext();
        }

        for (JumpInsnNode go : gotos) {
            if (predicateBudget.remaining() <= 0) {
                break;
            }
            LabelNode target = go.label;
            LabelNode fake = new LabelNode(new Label());
            InsnList replacement = new InsnList();
            PredicateEmission predicate = opaquePredicate(
                    context,
                    method,
                    go,
                    predicatePolicy,
                    predicateBudget,
                    loopProfile
            );
            replacement.add(predicate.instructions());
            appendNeverJump(replacement, predicate.expected(), fake);
            replacement.add(new JumpInsnNode(Opcodes.GOTO, target));
            replacement.add(fake);
            replacement.add(new LdcInsnNode(random().nextInt()));
            replacement.add(new InsnNode(Opcodes.POP));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, target));
            if (method.instructions.size() + replacement.size() - 1 > maximumOutputInstructions) {
                break;
            }

            method.instructions.insertBefore(go, replacement);
            method.instructions.remove(go);
        }
    }

    // endregion

    // region Medium: number obfuscation

    private void numberObfuscation(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            int op = insn.getOpcode();
            if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5 && random().nextBoolean()) {
                int value = op - Opcodes.ICONST_0;
                int left = random().nextInt();
                int right = left ^ value;
                InsnList replace = new InsnList();
                replace.add(new LdcInsnNode(left));
                replace.add(new LdcInsnNode(right));
                replace.add(new InsnNode(Opcodes.IXOR));
                method.instructions.insertBefore(insn, replace);
                method.instructions.remove(insn);
            }
            insn = next;
        }
    }

    // endregion

    // region Heavy: conditional-to-switch

    private void conditionalToSwitch(MethodNode method) {
        List<JumpInsnNode> conditionals = new ArrayList<>();
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            int op = insn.getOpcode();
            if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
                conditionals.add((JumpInsnNode) insn);
            }
            insn = insn.getNext();
        }

        for (JumpInsnNode jump : conditionals) {
            if (random().nextBoolean()) continue;

            LabelNode target = jump.label;
            int opcode = jump.getOpcode();
            int conditionSlot = ASMHelper.allocateLocal(method, 1);
            LabelNode zero = new LabelNode(new Label());
            LabelNode fall = new LabelNode(new Label());
            LabelNode dispatch = new LabelNode(new Label());
            LabelNode[] keys = opcode == Opcodes.IFEQ
                    ? new LabelNode[]{target, fall}
                    : new LabelNode[]{fall, target};

            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ISTORE, conditionSlot));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, conditionSlot));
            replacement.add(new JumpInsnNode(Opcodes.IFEQ, zero));
            replacement.add(new InsnNode(Opcodes.ICONST_1));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
            replacement.add(zero);
            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(dispatch);
            replacement.add(new TableSwitchInsnNode(0, 1, fall, keys));
            replacement.add(fall);

            method.instructions.insertBefore(jump, replacement);
            method.instructions.remove(jump);
        }
    }

    // endregion

    // region Heavy: scattered opaque predicates

    private void scatteredPredicates(String owner,
                                     PredicateClassContext context,
                                     MethodNode method,
                                     int predicateRate,
                                     int maxPredicates,
                                     PredicatePolicy predicatePolicy,
                                     PredicateBudget predicateBudget,
                                     LoopProfile loopProfile,
                                     int maximumOutputInstructions) {
        if (predicateRate <= 0 || maxPredicates <= 0) return;
        int inserted = 0;
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (inserted >= maxPredicates) break;
            if (random().nextInt(100) < predicateRate && !(insn instanceof LabelNode)) {
                if (insertPredicate(
                        owner,
                        context,
                        method,
                        insn,
                        predicatePolicy,
                        predicateBudget,
                        loopProfile,
                        maximumOutputInstructions
                )) {
                    inserted++;
                } else {
                    break;
                }
            }
            insn = next;
        }
    }

    private boolean insertPredicate(String owner,
                                    PredicateClassContext context,
                                    MethodNode method,
                                    AbstractInsnNode anchor,
                                    PredicatePolicy predicatePolicy,
                                    PredicateBudget predicateBudget,
                                    LoopProfile loopProfile,
                                    int maximumOutputInstructions) {
        if (predicateBudget.remaining() <= 0) {
            return false;
        }
        LabelNode join = new LabelNode(new Label());
        LabelNode dead = new LabelNode(new Label());
        InsnList guard = new InsnList();
        PredicateEmission predicate = opaquePredicate(
                context,
                method,
                anchor,
                predicatePolicy,
                predicateBudget,
                loopProfile
        );
        guard.add(predicate.instructions());
        if (random().nextBoolean()) {
            appendAlwaysJump(guard, predicate.expected(), join);
        } else {
            appendNeverJump(guard, predicate.expected(), dead);
            guard.add(new JumpInsnNode(Opcodes.GOTO, join));
            guard.add(dead);
        }
        guard.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        guard.add(new InsnNode(Opcodes.ATHROW));
        guard.add(join);
        if (method.instructions.size() + guard.size() > maximumOutputInstructions) {
            return false;
        }

        method.instructions.insertBefore(anchor, guard);
        return true;
    }

    private void appendNeverJump(InsnList list, int expected, LabelNode target) {
        if (random().nextBoolean()) {
            list.add(new JumpInsnNode(expected == 0 ? Opcodes.IFNE : Opcodes.IFEQ, target));
        } else {
            list.add(new InsnNode(expected == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
            list.add(new JumpInsnNode(Opcodes.IF_ICMPNE, target));
        }
    }

    private void appendAlwaysJump(InsnList list, int expected, LabelNode target) {
        if (random().nextBoolean()) {
            list.add(new JumpInsnNode(expected == 0 ? Opcodes.IFEQ : Opcodes.IFNE, target));
        } else {
            list.add(new InsnNode(expected == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
            list.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, target));
        }
    }

    // endregion

    // region Stateful opaque guards and stack-neutral noise

    private PredicateClassContext ensurePredicateContext(ClassNode classNode, PredicatePolicy policy) {
        String stateField = policy.volatileState() ? uniqueFieldName(classNode) : null;
        String tableField = policy.families().contains(PredicateFamily.LOOKUP_TABLE)
                ? uniqueFieldName(classNode)
                : null;
        String helperName = policy.families().contains(PredicateFamily.INTERPROCEDURAL)
                ? uniqueMethodName(classNode)
                : null;
        int keyA;
        Set<Integer> usedKeys = ACTIVE_CLASS_KEYS.get();
        do {
            keyA = nonZeroRandomInt() ^ classNode.name.hashCode();
        } while (keyA == 0 || usedKeys != null && !usedKeys.add(keyA));
        int keyB;
        do {
            keyB = nonZeroRandomInt();
        } while (keyB == keyA);
        int modulus = List.of(3, 5, 7, 11, 13, 17, 19).get(random().nextInt(7));

        if (stateField != null) {
            classNode.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                    stateField,
                    "I",
                    null,
                    null
            ));
        }
        if (tableField != null) {
            classNode.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    tableField,
                    "[I",
                    null,
                    null
            ));
        }

        MethodNode clinit = null;
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<clinit>")) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(clinit);
        }

        InsnList init = new InsnList();
        if (stateField != null) {
            init.add(new LdcInsnNode(Type.getObjectType(classNode.name)));
            init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode",
                    "(Ljava/lang/Object;)I", false));
            init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            init.add(new InsnNode(Opcodes.L2I));
            init.add(new InsnNode(Opcodes.IXOR));
            init.add(new LdcInsnNode(keyA));
            init.add(new InsnNode(Opcodes.IXOR));
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, stateField, "I"));
        }
        if (tableField != null) {
            appendTableInitialization(init, classNode.name, tableField, keyA, keyB);
        }
        clinit.instructions.insert(init);
        if (helperName != null) {
            classNode.methods.add(predicateHelper(helperName));
        }
        return new PredicateClassContext(
                classNode.name,
                stateField,
                tableField,
                helperName,
                keyA,
                keyB,
                modulus
        );
    }

    private void appendTableInitialization(InsnList init,
                                           String owner,
                                           String tableField,
                                           int keyA,
                                           int keyB) {
        init.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int index = 0; index < 8; index++) {
            init.add(new InsnNode(Opcodes.DUP));
            init.add(new IntInsnNode(Opcodes.BIPUSH, index));
            init.add(new LdcInsnNode(
                    Integer.rotateLeft(keyA ^ index * 0x9E3779B9, index + 1) + keyB
            ));
            init.add(new InsnNode(Opcodes.IASTORE));
        }
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, tableField, "[I"));
    }

    private MethodNode predicateHelper(String name) {
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "(II)I",
                null,
                null
        );
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.IXOR));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.IXOR));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.IXOR));
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 2;
        helper.maxStack = 2;
        return helper;
    }

    private String uniqueFieldName(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (FieldNode field : classNode.fields) used.add(field.name);
        String name;
        do {
            name = randomIdentifier();
        } while (!used.add(name));
        return name;
    }

    private String randomIdentifier() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";
        String body = alphabet + "0123456789";
        int length = 4 + random().nextInt(7);
        StringBuilder builder = new StringBuilder(length);
        builder.append(alphabet.charAt(random().nextInt(alphabet.length())));
        for (int i = 1; i < length; i++) {
            builder.append(body.charAt(random().nextInt(body.length())));
        }
        return builder.toString();
    }

    private String uniqueMethodName(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (MethodNode method : classNode.methods) {
            used.add(method.name);
        }
        String name;
        do {
            name = randomIdentifier();
        } while (!used.add(name));
        return name;
    }

    private PredicateEmission opaquePredicate(PredicateClassContext context,
                                              MethodNode method,
                                              AbstractInsnNode anchor,
                                              PredicatePolicy policy,
                                              PredicateBudget budget,
                                              LoopProfile loopProfile) {
        boolean hot = loopProfile.isHot(anchor);
        List<PredicateFamily> eligible = new ArrayList<>();
        for (PredicateFamily family : policy.families()) {
            int cost = predicateCost(family);
            if (!(method.name.equals("<clinit>") && family == PredicateFamily.LOOKUP_TABLE)
                    && cost <= budget.remaining()
                    && (!hot || policy.heavyInLoops() || cost <= policy.hotLoopMaximumCost())) {
                eligible.add(family);
                if (cost <= 2) {
                    eligible.add(family);
                    eligible.add(family);
                }
            }
        }
        if (eligible.isEmpty()) {
            eligible.add(PredicateFamily.BITWISE);
        }
        PredicateFamily family = eligible.get(random().nextInt(eligible.size()));
        int cost = predicateCost(family);
        PreparedValue value = prepareValue(context, method, policy);
        InsnList instructions = new InsnList();
        instructions.add(value.prefix());
        appendFamilyPredicate(instructions, context, value.source(), family);

        if (!hot
                && !policy.sources().isEmpty()
                && random().nextInt(100) < policy.camouflageRate()
                && cost + 2 <= budget.remaining()) {
            appendCamouflageZero(instructions, context, policy);
            instructions.add(new InsnNode(random().nextBoolean() ? Opcodes.IXOR : Opcodes.IADD));
            cost += 2;
        }

        int expected = random().nextBoolean() ? 0 : 1;
        if (expected == 1) {
            instructions.add(new InsnNode(Opcodes.ICONST_1));
            instructions.add(new InsnNode(Opcodes.IXOR));
        }
        budget.consume(cost);
        FlowMetrics metrics = ACTIVE_METRICS.get();
        if (metrics != null) {
            metrics.predicates.increment();
        }
        return new PredicateEmission(instructions, expected, family, cost);
    }

    private PreparedValue prepareValue(PredicateClassContext context,
                                       MethodNode method,
                                       PredicatePolicy policy) {
        ValueSource source = selectValueSource(context, method);
        if (random().nextInt(100) >= policy.localValueRate()) {
            return new PreparedValue(new InsnList(), source);
        }
        int local = ASMHelper.allocateLocal(method, 1);
        InsnList prefix = new InsnList();
        appendValue(prefix, context, source);
        prefix.add(new VarInsnNode(Opcodes.ISTORE, local));
        return new PreparedValue(prefix, new ValueSource(ValueKind.LOCAL, local, 0));
    }

    private ValueSource selectValueSource(PredicateClassContext context, MethodNode method) {
        List<ValueSource> sources = new ArrayList<>();
        int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0 && !AccessHelper.isInitializer(method)) {
            sources.add(new ValueSource(ValueKind.REFERENCE, 0, 0));
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            switch (argument.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                        sources.add(new ValueSource(ValueKind.INT, local, 0));
                case Type.LONG -> sources.add(new ValueSource(ValueKind.LONG, local, 0));
                case Type.FLOAT -> sources.add(new ValueSource(ValueKind.FLOAT, local, 0));
                case Type.DOUBLE -> sources.add(new ValueSource(ValueKind.DOUBLE, local, 0));
                case Type.ARRAY, Type.OBJECT ->
                        sources.add(new ValueSource(ValueKind.REFERENCE, local, 0));
                default -> {
                }
            }
            local += argument.getSize();
        }
        sources.add(new ValueSource(ValueKind.CONSTANT, -1, context.keyA()));
        return sources.get(random().nextInt(sources.size()));
    }

    private void appendValue(InsnList list,
                             PredicateClassContext context,
                             ValueSource source) {
        switch (source.kind()) {
            case INT, LOCAL -> list.add(new VarInsnNode(Opcodes.ILOAD, source.local()));
            case LONG -> {
                list.add(new VarInsnNode(Opcodes.LLOAD, source.local()));
                list.add(new InsnNode(Opcodes.L2I));
            }
            case FLOAT -> {
                list.add(new VarInsnNode(Opcodes.FLOAD, source.local()));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Float",
                        "floatToRawIntBits",
                        "(F)I",
                        false
                ));
            }
            case DOUBLE -> {
                list.add(new VarInsnNode(Opcodes.DLOAD, source.local()));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Double",
                        "doubleToRawLongBits",
                        "(D)J",
                        false
                ));
                list.add(new InsnNode(Opcodes.L2I));
            }
            case REFERENCE -> {
                list.add(new VarInsnNode(Opcodes.ALOAD, source.local()));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "identityHashCode",
                        "(Ljava/lang/Object;)I",
                        false
                ));
            }
            case CONSTANT -> list.add(new LdcInsnNode(source.constant()));
            case STATE -> list.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    context.owner(),
                    context.stateField(),
                    "I"
            ));
        }
    }

    private void appendFamilyPredicate(InsnList list,
                                       PredicateClassContext context,
                                       ValueSource source,
                                       PredicateFamily family) {
        switch (family) {
            case ARITHMETIC -> {
                appendValue(list, context, source);
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.IADD));
                list.add(new InsnNode(Opcodes.IMUL));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.IAND));
            }
            case BITWISE -> {
                appendValue(list, context, source);
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(random().nextBoolean() ? Opcodes.IXOR : Opcodes.ISUB));
            }
            case REVERSIBLE -> {
                appendValue(list, context, source);
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new LdcInsnNode(context.keyA()));
                list.add(new InsnNode(Opcodes.IXOR));
                list.add(new LdcInsnNode(context.keyA()));
                list.add(new InsnNode(Opcodes.IXOR));
                list.add(new InsnNode(Opcodes.IXOR));
            }
            case MODULAR -> {
                appendValue(list, context, source);
                list.add(new LdcInsnNode(context.modulus()));
                list.add(new InsnNode(Opcodes.IREM));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.ISUB));
            }
            case LOOKUP_TABLE -> {
                list.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        context.owner(),
                        context.tableField(),
                        "[I"
                ));
                appendValue(list, context, source);
                list.add(new IntInsnNode(Opcodes.BIPUSH, 7));
                list.add(new InsnNode(Opcodes.IAND));
                list.add(new InsnNode(Opcodes.IALOAD));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.IXOR));
            }
            case STATEFUL -> {
                appendValue(list, context, new ValueSource(ValueKind.STATE, -1, 0));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(random().nextBoolean() ? Opcodes.IXOR : Opcodes.ISUB));
            }
            case ARGUMENT_DERIVED -> {
                appendValue(list, context, source);
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new InsnNode(Opcodes.ICONST_M1));
                list.add(new InsnNode(Opcodes.IXOR));
                list.add(new InsnNode(Opcodes.IOR));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(new InsnNode(Opcodes.IADD));
            }
            case INTERPROCEDURAL -> {
                appendValue(list, context, source);
                list.add(new LdcInsnNode(context.keyB()));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        context.owner(),
                        context.helperName(),
                        "(II)I",
                        false
                ));
            }
        }
    }

    private void appendCamouflageZero(InsnList list,
                                      PredicateClassContext context,
                                      PredicatePolicy policy) {
        List<PredicateSource> sources = new ArrayList<>(policy.sources());
        PredicateSource source = sources.get(random().nextInt(sources.size()));
        appendPredicateSource(list, context, source);
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(random().nextBoolean() ? Opcodes.IXOR : Opcodes.ISUB));
    }

    private void appendPredicateSource(InsnList list,
                                       PredicateClassContext context,
                                       PredicateSource source) {
        switch (source) {
            case VOLATILE -> list.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    context.owner(),
                    context.stateField(),
                    "I"
            ));
            case THREAD -> {
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Thread",
                        "currentThread",
                        "()Ljava/lang/Thread;",
                        false
                ));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/Thread",
                        "getId",
                        "()J",
                        false
                ));
                list.add(new InsnNode(Opcodes.L2I));
            }
            case ENVIRONMENT -> {
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Runtime",
                        "getRuntime",
                        "()Ljava/lang/Runtime;",
                        false
                ));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/Runtime",
                        "availableProcessors",
                        "()I",
                        false
                ));
            }
            case TIME -> {
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "nanoTime",
                        "()J",
                        false
                ));
                list.add(new InsnNode(Opcodes.L2I));
            }
        }
    }

    private void exceptionGuard(String owner,
                                PredicateClassContext context,
                                MethodNode method,
                                PredicatePolicy predicatePolicy,
                                PredicateBudget predicateBudget,
                                LoopProfile loopProfile,
                                int maximumOutputInstructions) {
        if (predicateBudget.remaining() <= 0) {
            return;
        }
        AbstractInsnNode first = firstExecutable(method);
        if (first == null) return;

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        LabelNode handler = new LabelNode(new Label());
        LabelNode join = new LabelNode(new Label());

        InsnList guard = new InsnList();
        guard.add(start);
        PredicateEmission predicate = opaquePredicate(
                context,
                method,
                first,
                predicatePolicy,
                predicateBudget,
                loopProfile
        );
        guard.add(predicate.instructions());
        appendAlwaysJump(guard, predicate.expected(), join);
        guard.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        guard.add(new InsnNode(Opcodes.ATHROW));
        guard.add(end);
        guard.add(new JumpInsnNode(Opcodes.GOTO, join));
        guard.add(handler);
        guard.add(new InsnNode(Opcodes.POP));
        guard.add(join);
        if (method.instructions.size() + guard.size() > maximumOutputInstructions) {
            return;
        }

        method.instructions.insertBefore(first, guard);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/IllegalStateException"));
    }

    private void stackNoise(MethodNode method) {
        AbstractInsnNode first = firstExecutable(method);
        if (first == null) return;

        InsnList noise = new InsnList();
        noise.add(new LdcInsnNode(random().nextInt()));
        noise.add(new InsnNode(Opcodes.POP));
        noise.add(new InsnNode(Opcodes.ACONST_NULL));
        noise.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(first, noise);
    }

    private AbstractInsnNode firstExecutable(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode)
                    && !(insn instanceof FrameNode)) {
                return insn;
            }
            insn = insn.getNext();
        }
        return null;
    }

    // region Heavy: dispatcher-loop control-flow flattening

    private boolean flattenMethod(PredicateClassContext classContext,
                                  MethodNode method,
                                  FlattenPolicy policy,
                                  LoopProfile loopProfile) throws AnalyzerException {
        if (AccessHelper.isInitializer(method)
                || method.tryCatchBlocks != null
                && method.tryCatchBlocks.size() > policy.maximumExceptionHandlers()
                || containsLegacySubroutine(method)) {
            return false;
        }
        // Handler entries carry an exception on the operand stack and require
        // a dedicated typed dispatcher. Preserve such methods unchanged.
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }

        List<BasicBlock> blocks = splitBasicBlocks(method);
        if (blocks.size() < policy.minimumBlocks() || blocks.size() > policy.maximumBlocks()) {
            return false;
        }
        int complexity = cfgComplexity(blocks, loopProfile);
        if (complexity < policy.minimumComplexity()
                || complexity > policy.costBudget()
                || method.instructions.size() + blocks.size() * 18 + 96
                > policy.maximumOutputInstructions()) {
            return false;
        }

        Frame<BasicValue>[] frames = analyzeWithHeadroom(classContext.owner(), method, false);
        Map<AbstractInsnNode, Frame<BasicValue>> originalFrames = frameMap(method, frames);
        for (BasicBlock block : blocks) {
            int instructionIndex = method.instructions.indexOf(block.firstExecutable());
            if (instructionIndex < 0
                    || frames[instructionIndex] == null
                    || frames[instructionIndex].getStackSize() != 0) {
                // Carrying operand-stack values across dispatcher cases would
                // require typed spill locals. Leave unusual bytecode unchanged.
                return false;
            }
        }
        InsnList localInitializers = localInitializers(method, blocks, frames);
        if (localInitializers == null) {
            // One physical local slot is reused for incompatible primitive or
            // reference categories. A common dispatcher frame cannot represent
            // that layout without typed spilling, so retain the original CFG.
            return false;
        }

        Map<LabelNode, Integer> targetBlocks = targetBlocks(blocks);
        if (!allTargetsResolved(blocks, targetBlocks)) {
            return false;
        }

        Set<Integer> protectedBlocks = selectFlattenedBlocks(blocks, loopProfile, policy);
        if (protectedBlocks.isEmpty()) {
            return false;
        }
        boolean partial = protectedBlocks.size() < blocks.size();
        DispatcherStyle style = selectDispatcherStyle(policy, blocks.size());
        int[] states = style == DispatcherStyle.LOOKUP
                ? uniqueRandomStates(blocks.size())
                : sequentialStates(blocks.size());
        StateCodec codec = new StateCodec(
                ASMHelper.allocateLocal(method, 1),
                ASMHelper.allocateLocal(method, 1),
                ASMHelper.allocateLocal(method, 1),
                classContext.keyA(),
                classContext.keyB(),
                policy.stateReencodeRate()
        );
        LabelNode dispatch = new LabelNode(new Label());
        LabelNode invalidState = new LabelNode(new Label());
        LabelNode[] caseLabels = new LabelNode[blocks.size()];

        for (int index = 0; index < blocks.size(); index++) {
            caseLabels[index] = new LabelNode(new Label());
            method.instructions.insertBefore(blocks.get(index).start(), caseLabels[index]);
        }

        for (int index = 0; index < blocks.size(); index++) {
            if (!protectedBlocks.contains(index)) {
                continue;
            }
            BasicBlock block = blocks.get(index);
            AbstractInsnNode terminal = block.lastExecutable();
            if (terminal instanceof JumpInsnNode jump) {
                if (jump.getOpcode() == Opcodes.GOTO) {
                    replaceWithTransition(
                            method,
                            terminal,
                            codec,
                            states[targetBlocks.get(jump.label)],
                            dispatch
                    );
                } else {
                    int fallthrough = index + 1;
                    if (fallthrough >= blocks.size()) {
                        return false;
                    }
                    replaceConditionalTransition(
                            method,
                            jump,
                            codec,
                            states[targetBlocks.get(jump.label)],
                            states[fallthrough],
                            dispatch
                    );
                }
            } else if (terminal instanceof TableSwitchInsnNode tableSwitch) {
                replaceTableSwitch(
                        method,
                        tableSwitch,
                        targetBlocks,
                        states,
                        codec,
                        dispatch
                );
            } else if (terminal instanceof LookupSwitchInsnNode lookupSwitch) {
                replaceLookupSwitch(
                        method,
                        lookupSwitch,
                        targetBlocks,
                        states,
                        codec,
                        dispatch
                );
            } else if (!isTerminalOpcode(terminal.getOpcode())) {
                int fallthrough = index + 1;
                if (fallthrough >= blocks.size()) {
                    return false;
                }
                method.instructions.insert(
                        block.end(),
                        transition(
                                codec,
                                states[fallthrough],
                                dispatch
                        )
                );
            }
        }

        int fakeCount = Math.min(policy.fakeStates(), Math.max(0, policy.costBudget() - complexity) / 4);
        LabelNode[] fakeLabels = new LabelNode[fakeCount];
        for (int index = 0; index < fakeCount; index++) {
            fakeLabels[index] = new LabelNode(new Label());
        }
        InsnList header = new InsnList();
        header.add(localInitializers);
        if (partial) {
            header.add(new JumpInsnNode(Opcodes.GOTO, caseLabels[0]));
        } else {
            header.add(stateAssignment(codec, states[0]));
            header.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
        }
        header.add(dispatch);
        appendDecodedState(header, codec);
        appendDispatcher(
                header,
                style,
                states,
                caseLabels,
                fakeLabels,
                invalidState,
                method
        );
        header.add(invalidState);
        header.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        header.add(new InsnNode(Opcodes.DUP));
        header.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException",
                "<init>",
                "()V",
                false
        ));
        header.add(new InsnNode(Opcodes.ATHROW));
        appendFakeStateBodies(
                header,
                fakeLabels,
                blocks,
                originalFrames,
                states,
                codec,
                dispatch,
                policy
        );
        method.instructions.insertBefore(method.instructions.getFirst(), header);
        FlowMetrics metrics = ACTIVE_METRICS.get();
        if (metrics != null) {
            metrics.flattenedMethods.increment();
            if (partial) {
                metrics.partiallyFlattenedMethods.increment();
            }
            metrics.fakeStates.add(fakeCount);
        }
        return true;
    }

    private int cfgComplexity(List<BasicBlock> blocks, LoopProfile loopProfile) {
        int score = blocks.size();
        for (BasicBlock block : blocks) {
            AbstractInsnNode terminal = block.lastExecutable();
            if (terminal instanceof JumpInsnNode jump) {
                score += jump.getOpcode() == Opcodes.GOTO ? 1 : 3;
            } else if (terminal instanceof TableSwitchInsnNode
                    || terminal instanceof LookupSwitchInsnNode) {
                score += 6;
            }
            if (loopProfile.isHot(block.firstExecutable())) {
                score += 2;
            }
        }
        return score;
    }

    private Map<AbstractInsnNode, Frame<BasicValue>> frameMap(MethodNode method,
                                                              Frame<BasicValue>[] frames) {
        Map<AbstractInsnNode, Frame<BasicValue>> result = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index < frames.length) {
                result.put(instruction, frames[index]);
            }
            index++;
        }
        return result;
    }

    private Set<Integer> selectFlattenedBlocks(List<BasicBlock> blocks,
                                               LoopProfile loopProfile,
                                               FlattenPolicy policy) {
        boolean containsHotLoop = blocks.stream()
                .anyMatch(block -> loopProfile.isHot(block.firstExecutable()));
        boolean partial = random().nextInt(100) < policy.partialRate()
                || containsHotLoop && !policy.flattenHotLoops();
        Set<Integer> selected = new LinkedHashSet<>();
        for (int index = 0; index < blocks.size(); index++) {
            BasicBlock block = blocks.get(index);
            boolean hot = loopProfile.isHot(block.firstExecutable());
            if (partial && hot && !policy.flattenHotLoops()) {
                continue;
            }
            boolean complex = block.lastExecutable() instanceof JumpInsnNode
                    || block.lastExecutable() instanceof TableSwitchInsnNode
                    || block.lastExecutable() instanceof LookupSwitchInsnNode;
            if (!partial
                    || complex
                    || random().nextInt(100) < policy.partialRegionRate()) {
                selected.add(index);
            }
        }
        return selected;
    }

    private DispatcherStyle selectDispatcherStyle(FlattenPolicy policy, int blockCount) {
        List<DispatcherStyle> eligible = new ArrayList<>();
        for (DispatcherStyle style : policy.styles()) {
            if ((style == DispatcherStyle.NESTED || style == DispatcherStyle.SPLIT)
                    && blockCount < 8) {
                continue;
            }
            if (style == DispatcherStyle.TABLE && blockCount > 256) {
                continue;
            }
            eligible.add(style);
        }
        if (eligible.isEmpty()) {
            eligible.add(DispatcherStyle.LOOKUP);
        }
        return eligible.get(random().nextInt(eligible.size()));
    }

    private void appendDecodedState(InsnList list, StateCodec codec) {
        list.add(new VarInsnNode(Opcodes.ILOAD, codec.stateSlot()));
        list.add(new VarInsnNode(Opcodes.ILOAD, codec.addSlot()));
        list.add(new InsnNode(Opcodes.ISUB));
        list.add(new VarInsnNode(Opcodes.ILOAD, codec.xorSlot()));
        list.add(new InsnNode(Opcodes.IXOR));
    }

    private void appendDispatcher(InsnList list,
                                  DispatcherStyle style,
                                  int[] states,
                                  LabelNode[] caseLabels,
                                  LabelNode[] fakeLabels,
                                  LabelNode invalidState,
                                  MethodNode method) {
        switch (style) {
            case LOOKUP -> appendLookupDispatcher(
                    list,
                    states,
                    caseLabels,
                    fakeLabels,
                    invalidState
            );
            case TABLE -> appendTableDispatcher(list, caseLabels, fakeLabels, invalidState);
            case COMPUTED -> appendComputedDispatcher(
                    list,
                    caseLabels,
                    fakeLabels,
                    invalidState
            );
            case NESTED -> appendNestedDispatcher(
                    list,
                    caseLabels,
                    fakeLabels,
                    invalidState,
                    method,
                    false
            );
            case SPLIT -> appendNestedDispatcher(
                    list,
                    caseLabels,
                    fakeLabels,
                    invalidState,
                    method,
                    true
            );
        }
    }

    private void appendLookupDispatcher(InsnList list,
                                        int[] states,
                                        LabelNode[] caseLabels,
                                        LabelNode[] fakeLabels,
                                        LabelNode invalidState) {
        List<SwitchEntry> entries = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int index = 0; index < states.length; index++) {
            entries.add(new SwitchEntry(states[index], caseLabels[index]));
            used.add(states[index]);
        }
        for (LabelNode fakeLabel : fakeLabels) {
            int key;
            do {
                key = random().nextInt();
            } while (!used.add(key));
            entries.add(new SwitchEntry(key, fakeLabel));
        }
        entries.sort(Comparator.comparingInt(SwitchEntry::key));
        list.add(new LookupSwitchInsnNode(
                invalidState,
                entries.stream().mapToInt(SwitchEntry::key).toArray(),
                entries.stream().map(SwitchEntry::label).toArray(LabelNode[]::new)
        ));
    }

    private void appendTableDispatcher(InsnList list,
                                       LabelNode[] caseLabels,
                                       LabelNode[] fakeLabels,
                                       LabelNode invalidState) {
        LabelNode[] labels = new LabelNode[caseLabels.length + fakeLabels.length];
        System.arraycopy(caseLabels, 0, labels, 0, caseLabels.length);
        System.arraycopy(fakeLabels, 0, labels, caseLabels.length, fakeLabels.length);
        list.add(new TableSwitchInsnNode(0, labels.length - 1, invalidState, labels));
    }

    private void appendComputedDispatcher(InsnList list,
                                          LabelNode[] caseLabels,
                                          LabelNode[] fakeLabels,
                                          LabelNode invalidState) {
        int multiplier = nonZeroOddInt();
        int bias = random().nextInt();
        list.add(new LdcInsnNode(multiplier));
        list.add(new InsnNode(Opcodes.IMUL));
        list.add(new LdcInsnNode(bias));
        list.add(new InsnNode(Opcodes.IADD));
        List<SwitchEntry> entries = new ArrayList<>();
        for (int index = 0; index < caseLabels.length; index++) {
            entries.add(new SwitchEntry(index * multiplier + bias, caseLabels[index]));
        }
        for (int index = 0; index < fakeLabels.length; index++) {
            entries.add(new SwitchEntry(
                    (caseLabels.length + index) * multiplier + bias,
                    fakeLabels[index]
            ));
        }
        entries.sort(Comparator.comparingInt(SwitchEntry::key));
        list.add(new LookupSwitchInsnNode(
                invalidState,
                entries.stream().mapToInt(SwitchEntry::key).toArray(),
                entries.stream().map(SwitchEntry::label).toArray(LabelNode[]::new)
        ));
    }

    private void appendNestedDispatcher(InsnList list,
                                        LabelNode[] caseLabels,
                                        LabelNode[] fakeLabels,
                                        LabelNode invalidState,
                                        MethodNode method,
                                        boolean split) {
        int total = caseLabels.length + fakeLabels.length;
        LabelNode[] allLabels = new LabelNode[total];
        System.arraycopy(caseLabels, 0, allLabels, 0, caseLabels.length);
        System.arraycopy(fakeLabels, 0, allLabels, caseLabels.length, fakeLabels.length);
        int indexSlot = ASMHelper.allocateLocal(method, 1);
        int groups = split ? 2 : Math.max(2, Math.min(8, (total + 5) / 6));
        LabelNode[] groupLabels = new LabelNode[groups];
        for (int group = 0; group < groups; group++) {
            groupLabels[group] = new LabelNode(new Label());
        }
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.ISTORE, indexSlot));
        list.add(new LdcInsnNode(groups));
        list.add(new InsnNode(Opcodes.IREM));
        list.add(new TableSwitchInsnNode(0, groups - 1, invalidState, groupLabels));
        for (int group = 0; group < groups; group++) {
            list.add(groupLabels[group]);
            List<SwitchEntry> entries = new ArrayList<>();
            for (int index = group; index < total; index += groups) {
                entries.add(new SwitchEntry(index, allLabels[index]));
            }
            list.add(new VarInsnNode(Opcodes.ILOAD, indexSlot));
            list.add(new LookupSwitchInsnNode(
                    invalidState,
                    entries.stream().mapToInt(SwitchEntry::key).toArray(),
                    entries.stream().map(SwitchEntry::label).toArray(LabelNode[]::new)
            ));
        }
    }

    private void appendFakeStateBodies(InsnList list,
                                       LabelNode[] fakeLabels,
                                       List<BasicBlock> blocks,
                                       Map<AbstractInsnNode, Frame<BasicValue>> originalFrames,
                                       int[] states,
                                       StateCodec codec,
                                       LabelNode dispatch,
                                       FlattenPolicy policy) {
        for (int index = 0; index < fakeLabels.length; index++) {
            list.add(fakeLabels[index]);
            if (random().nextInt(100) < policy.blockCloneRate()) {
                appendSafeBlockClone(list, blocks, originalFrames);
            } else {
                int left = random().nextInt();
                list.add(new LdcInsnNode(left));
                list.add(new LdcInsnNode(left ^ classKeyNoise(codec)));
                list.add(new InsnNode(Opcodes.IXOR));
                list.add(new InsnNode(Opcodes.POP));
            }
            list.add(transition(
                    codec,
                    states[random().nextInt(states.length)],
                    dispatch
            ));
        }
    }

    private void appendSafeBlockClone(InsnList target,
                                      List<BasicBlock> blocks,
                                      Map<AbstractInsnNode, Frame<BasicValue>> originalFrames) {
        List<BasicBlock> candidates = new ArrayList<>();
        for (BasicBlock block : blocks) {
            if (safeCloneCandidate(block, originalFrames)) {
                candidates.add(block);
            }
        }
        if (candidates.isEmpty()) {
            target.add(new LdcInsnNode(random().nextInt()));
            target.add(new InsnNode(Opcodes.POP));
            return;
        }
        BasicBlock chosen = candidates.get(random().nextInt(candidates.size()));
        for (AbstractInsnNode instruction : chosen.instructions()) {
            if (instruction == chosen.lastExecutable()
                    || instruction.getOpcode() < 0) {
                continue;
            }
            target.add(instruction.clone(new IdentityHashMap<>()));
        }
    }

    private boolean safeCloneCandidate(BasicBlock block,
                                       Map<AbstractInsnNode, Frame<BasicValue>> originalFrames) {
        Frame<BasicValue> terminalFrame = originalFrames.get(block.lastExecutable());
        if (terminalFrame == null || terminalFrame.getStackSize() != 0) {
            return false;
        }
        for (AbstractInsnNode instruction : block.instructions()) {
            if (instruction == block.lastExecutable()) {
                continue;
            }
            int opcode = instruction.getOpcode();
            if (opcode < 0) {
                continue;
            }
            if (!(instruction instanceof InsnNode
                    || instruction instanceof VarInsnNode
                    || instruction instanceof IincInsnNode
                    || instruction instanceof LdcInsnNode)) {
                return false;
            }
            if (instruction instanceof InsnNode && !safeDecoyOpcode(opcode)) {
                return false;
            }
            if (instruction instanceof LdcInsnNode constant
                    && !(constant.cst instanceof Number
                    || constant.cst instanceof String
                    || constant.cst instanceof Type)) {
                return false;
            }
        }
        return block.lastExecutable().getOpcode() == Opcodes.GOTO;
    }

    private boolean safeDecoyOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.NOP,
                    Opcodes.ACONST_NULL,
                    Opcodes.ICONST_M1,
                    Opcodes.ICONST_0,
                    Opcodes.ICONST_1,
                    Opcodes.ICONST_2,
                    Opcodes.ICONST_3,
                    Opcodes.ICONST_4,
                    Opcodes.ICONST_5,
                    Opcodes.LCONST_0,
                    Opcodes.LCONST_1,
                    Opcodes.FCONST_0,
                    Opcodes.FCONST_1,
                    Opcodes.FCONST_2,
                    Opcodes.DCONST_0,
                    Opcodes.DCONST_1,
                    Opcodes.POP,
                    Opcodes.POP2,
                    Opcodes.DUP,
                    Opcodes.DUP_X1,
                    Opcodes.DUP_X2,
                    Opcodes.DUP2,
                    Opcodes.DUP2_X1,
                    Opcodes.DUP2_X2,
                    Opcodes.SWAP,
                    Opcodes.IADD,
                    Opcodes.LADD,
                    Opcodes.FADD,
                    Opcodes.DADD,
                    Opcodes.ISUB,
                    Opcodes.LSUB,
                    Opcodes.FSUB,
                    Opcodes.DSUB,
                    Opcodes.IMUL,
                    Opcodes.LMUL,
                    Opcodes.FMUL,
                    Opcodes.DMUL,
                    Opcodes.INEG,
                    Opcodes.LNEG,
                    Opcodes.FNEG,
                    Opcodes.DNEG,
                    Opcodes.ISHL,
                    Opcodes.LSHL,
                    Opcodes.ISHR,
                    Opcodes.LSHR,
                    Opcodes.IUSHR,
                    Opcodes.LUSHR,
                    Opcodes.IAND,
                    Opcodes.LAND,
                    Opcodes.IOR,
                    Opcodes.LOR,
                    Opcodes.IXOR,
                    Opcodes.LXOR,
                    Opcodes.I2L,
                    Opcodes.I2F,
                    Opcodes.I2D,
                    Opcodes.L2I,
                    Opcodes.L2F,
                    Opcodes.L2D,
                    Opcodes.F2I,
                    Opcodes.F2L,
                    Opcodes.F2D,
                    Opcodes.D2I,
                    Opcodes.D2L,
                    Opcodes.D2F,
                    Opcodes.I2B,
                    Opcodes.I2C,
                    Opcodes.I2S,
                    Opcodes.LCMP,
                    Opcodes.FCMPL,
                    Opcodes.FCMPG,
                    Opcodes.DCMPL,
                    Opcodes.DCMPG -> true;
            default -> false;
        };
    }

    private int classKeyNoise(StateCodec codec) {
        return codec.classXorKey() ^ codec.classAddKey();
    }

    private InsnList localInitializers(MethodNode method,
                                       List<BasicBlock> blocks,
                                       Frame<BasicValue>[] frames) {
        int argumentSlots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            argumentSlots += argument.getSize();
        }

        LocalKind[] kinds = new LocalKind[method.maxLocals];
        for (BasicBlock block : blocks) {
            int instructionIndex = method.instructions.indexOf(block.firstExecutable());
            Frame<BasicValue> frame = frames[instructionIndex];
            for (int local = argumentSlots; local < Math.min(frame.getLocals(), kinds.length); local++) {
                BasicValue value = frame.getLocal(local);
                LocalKind candidate = localKind(value);
                if (candidate == null) {
                    continue;
                }
                if (kinds[local] != null && kinds[local] != candidate) {
                    return null;
                }
                kinds[local] = candidate;
            }
        }

        InsnList result = new InsnList();
        for (int local = argumentSlots; local < kinds.length; local++) {
            LocalKind kind = kinds[local];
            if (kind == null || kind == LocalKind.WIDE_TAIL) {
                continue;
            }
            switch (kind) {
                case INT -> {
                    result.add(new InsnNode(Opcodes.ICONST_0));
                    result.add(new VarInsnNode(Opcodes.ISTORE, local));
                }
                case FLOAT -> {
                    result.add(new InsnNode(Opcodes.FCONST_0));
                    result.add(new VarInsnNode(Opcodes.FSTORE, local));
                }
                case LONG -> {
                    result.add(new InsnNode(Opcodes.LCONST_0));
                    result.add(new VarInsnNode(Opcodes.LSTORE, local));
                    if (local + 1 < kinds.length) {
                        kinds[local + 1] = LocalKind.WIDE_TAIL;
                    }
                }
                case DOUBLE -> {
                    result.add(new InsnNode(Opcodes.DCONST_0));
                    result.add(new VarInsnNode(Opcodes.DSTORE, local));
                    if (local + 1 < kinds.length) {
                        kinds[local + 1] = LocalKind.WIDE_TAIL;
                    }
                }
                case REFERENCE -> {
                    result.add(new InsnNode(Opcodes.ACONST_NULL));
                    result.add(new VarInsnNode(Opcodes.ASTORE, local));
                }
                case WIDE_TAIL -> {
                    // Initialized together with the preceding wide local.
                }
            }
        }
        return result;
    }

    private LocalKind localKind(BasicValue value) {
        if (value == null || value == BasicValue.UNINITIALIZED_VALUE) {
            return null;
        }
        if (value == BasicValue.INT_VALUE) {
            return LocalKind.INT;
        }
        if (value == BasicValue.FLOAT_VALUE) {
            return LocalKind.FLOAT;
        }
        if (value == BasicValue.LONG_VALUE) {
            return LocalKind.LONG;
        }
        if (value == BasicValue.DOUBLE_VALUE) {
            return LocalKind.DOUBLE;
        }
        if (value.isReference()) {
            return LocalKind.REFERENCE;
        }
        return null;
    }

    private List<BasicBlock> splitBasicBlocks(MethodNode method) {
        Set<AbstractInsnNode> leaders = Collections.newSetFromMap(new IdentityHashMap<>());
        AbstractInsnNode first = method.instructions.getFirst();
        if (first != null) {
            leaders.add(first);
        }

        for (AbstractInsnNode instruction = first; instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump) {
                leaders.add(jump.label);
                if (instruction.getNext() != null) {
                    leaders.add(instruction.getNext());
                }
            } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
                leaders.add(tableSwitch.dflt);
                leaders.addAll(tableSwitch.labels);
                if (instruction.getNext() != null) {
                    leaders.add(instruction.getNext());
                }
            } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
                leaders.add(lookupSwitch.dflt);
                leaders.addAll(lookupSwitch.labels);
                if (instruction.getNext() != null) {
                    leaders.add(instruction.getNext());
                }
            } else if (isTerminalOpcode(instruction.getOpcode()) && instruction.getNext() != null) {
                leaders.add(instruction.getNext());
            }
        }

        List<BasicBlock> blocks = new ArrayList<>();
        List<AbstractInsnNode> current = new ArrayList<>();
        for (AbstractInsnNode instruction = first; instruction != null; instruction = instruction.getNext()) {
            if (!current.isEmpty() && leaders.contains(instruction)) {
                addBlock(blocks, current);
                current = new ArrayList<>();
            }
            current.add(instruction);
        }
        addBlock(blocks, current);
        return blocks;
    }

    private void addBlock(List<BasicBlock> blocks, List<AbstractInsnNode> instructions) {
        if (instructions.isEmpty()) {
            return;
        }
        AbstractInsnNode firstExecutable = null;
        AbstractInsnNode lastExecutable = null;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction.getOpcode() >= 0) {
                if (firstExecutable == null) {
                    firstExecutable = instruction;
                }
                lastExecutable = instruction;
            }
        }
        if (firstExecutable != null) {
            blocks.add(new BasicBlock(
                    instructions.get(0),
                    instructions.get(instructions.size() - 1),
                    firstExecutable,
                    lastExecutable,
                    List.copyOf(instructions)
            ));
        }
    }

    private Map<LabelNode, Integer> targetBlocks(List<BasicBlock> blocks) {
        Map<LabelNode, Integer> result = new IdentityHashMap<>();
        for (int index = 0; index < blocks.size(); index++) {
            for (AbstractInsnNode instruction : blocks.get(index).instructions()) {
                if (instruction == blocks.get(index).firstExecutable()) {
                    break;
                }
                if (instruction instanceof LabelNode label) {
                    result.put(label, index);
                }
            }
        }
        return result;
    }

    private boolean allTargetsResolved(List<BasicBlock> blocks, Map<LabelNode, Integer> targetBlocks) {
        for (BasicBlock block : blocks) {
            AbstractInsnNode terminal = block.lastExecutable();
            if (terminal instanceof JumpInsnNode jump && !targetBlocks.containsKey(jump.label)) {
                return false;
            }
            if (terminal instanceof TableSwitchInsnNode tableSwitch
                    && (!targetBlocks.containsKey(tableSwitch.dflt)
                    || tableSwitch.labels.stream().anyMatch(label -> !targetBlocks.containsKey(label)))) {
                return false;
            }
            if (terminal instanceof LookupSwitchInsnNode lookupSwitch
                    && (!targetBlocks.containsKey(lookupSwitch.dflt)
                    || lookupSwitch.labels.stream().anyMatch(label -> !targetBlocks.containsKey(label)))) {
                return false;
            }
        }
        return true;
    }

    private void replaceWithTransition(MethodNode method,
                                       AbstractInsnNode terminal,
                                       StateCodec codec,
                                       int state,
                                       LabelNode dispatch) {
        method.instructions.insertBefore(terminal, transition(codec, state, dispatch));
        method.instructions.remove(terminal);
    }

    private void replaceConditionalTransition(MethodNode method,
                                              JumpInsnNode conditional,
                                              StateCodec codec,
                                              int takenState,
                                              int fallthroughState,
                                              LabelNode dispatch) {
        LabelNode taken = new LabelNode(new Label());
        InsnList replacement = new InsnList();
        replacement.add(new JumpInsnNode(conditional.getOpcode(), taken));
        replacement.add(transition(codec, fallthroughState, dispatch));
        replacement.add(taken);
        replacement.add(transition(codec, takenState, dispatch));
        method.instructions.insertBefore(conditional, replacement);
        method.instructions.remove(conditional);
    }

    private void replaceTableSwitch(MethodNode method,
                                    TableSwitchInsnNode original,
                                    Map<LabelNode, Integer> targetBlocks,
                                    int[] states,
                                    StateCodec codec,
                                    LabelNode dispatch) {
        Map<Integer, LabelNode> transitions = new LinkedHashMap<>();
        LabelNode defaultTransition = transitionLabel(targetBlocks.get(original.dflt), transitions);
        LabelNode[] labels = new LabelNode[original.labels.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = transitionLabel(targetBlocks.get(original.labels.get(index)), transitions);
        }
        TableSwitchInsnNode replacement = new TableSwitchInsnNode(
                original.min,
                original.max,
                defaultTransition,
                labels
        );
        method.instructions.insertBefore(original, replacement);
        method.instructions.insert(replacement, transitionBodies(transitions, states, codec, dispatch));
        method.instructions.remove(original);
    }

    private void replaceLookupSwitch(MethodNode method,
                                     LookupSwitchInsnNode original,
                                     Map<LabelNode, Integer> targetBlocks,
                                     int[] states,
                                     StateCodec codec,
                                     LabelNode dispatch) {
        Map<Integer, LabelNode> transitions = new LinkedHashMap<>();
        LabelNode defaultTransition = transitionLabel(targetBlocks.get(original.dflt), transitions);
        LabelNode[] labels = new LabelNode[original.labels.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = transitionLabel(targetBlocks.get(original.labels.get(index)), transitions);
        }
        int[] keys = original.keys.stream().mapToInt(Integer::intValue).toArray();
        LookupSwitchInsnNode replacement = new LookupSwitchInsnNode(defaultTransition, keys, labels);
        method.instructions.insertBefore(original, replacement);
        method.instructions.insert(replacement, transitionBodies(transitions, states, codec, dispatch));
        method.instructions.remove(original);
    }

    private LabelNode transitionLabel(int target, Map<Integer, LabelNode> transitions) {
        return transitions.computeIfAbsent(target, ignored -> new LabelNode(new Label()));
    }

    private InsnList transitionBodies(Map<Integer, LabelNode> transitions,
                                      int[] states,
                                      StateCodec codec,
                                      LabelNode dispatch) {
        InsnList result = new InsnList();
        for (Map.Entry<Integer, LabelNode> transition : transitions.entrySet()) {
            result.add(transition.getValue());
            result.add(transition(
                    codec,
                    states[transition.getKey()],
                    dispatch
            ));
        }
        return result;
    }

    private InsnList transition(StateCodec codec, int state, LabelNode dispatch) {
        InsnList result = new InsnList();
        result.add(stateAssignment(codec, state));
        result.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
        return result;
    }

    private InsnList stateAssignment(StateCodec codec, int state) {
        boolean reencode = random().nextInt(100) < codec.reencodeRate();
        int xorKey = reencode
                ? codec.classXorKey() ^ random().nextInt()
                : codec.classXorKey();
        int addKey = reencode
                ? codec.classAddKey() + random().nextInt()
                : codec.classAddKey();
        int encoded = (state ^ xorKey) + addKey;

        InsnList result = new InsnList();
        if (random().nextBoolean()) {
            result.add(new LdcInsnNode(xorKey));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.xorSlot()));
            result.add(new LdcInsnNode(addKey));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.addSlot()));
            result.add(new LdcInsnNode(encoded));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.stateSlot()));
        } else {
            result.add(new LdcInsnNode(state));
            result.add(new LdcInsnNode(xorKey));
            result.add(new InsnNode(Opcodes.IXOR));
            result.add(new LdcInsnNode(addKey));
            result.add(new InsnNode(Opcodes.IADD));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.stateSlot()));
            result.add(new LdcInsnNode(addKey));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.addSlot()));
            result.add(new LdcInsnNode(xorKey));
            result.add(new VarInsnNode(Opcodes.ISTORE, codec.xorSlot()));
        }
        return result;
    }

    private boolean containsLegacySubroutine(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.JSR || instruction.getOpcode() == Opcodes.RET) {
                return true;
            }
        }
        return false;
    }

    private boolean isTerminalOpcode(int opcode) {
        return opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN
                || opcode == Opcodes.RETURN
                || opcode == Opcodes.ATHROW;
    }

    private int[] uniqueRandomStates(int count) {
        int[] states = new int[count];
        Set<Integer> used = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int state;
            do {
                state = random().nextInt();
            } while (!used.add(state));
            states[index] = state;
        }
        return states;
    }

    private int[] sequentialStates(int count) {
        int[] states = new int[count];
        for (int index = 0; index < count; index++) {
            states[index] = index;
        }
        return states;
    }

    private int nonZeroOddInt() {
        int value;
        do {
            value = random().nextInt() | 1;
        } while (value == 0);
        return value;
    }

    private int nonZeroRandomInt() {
        int value;
        do {
            value = random().nextInt();
        } while (value == 0);
        return value;
    }

    private record BasicBlock(AbstractInsnNode start,
                              AbstractInsnNode end,
                              AbstractInsnNode firstExecutable,
                              AbstractInsnNode lastExecutable,
                              List<AbstractInsnNode> instructions) {
    }

    private enum LocalKind {
        INT,
        FLOAT,
        LONG,
        DOUBLE,
        REFERENCE,
        WIDE_TAIL
    }

    // endregion

    private PredicatePolicy predicatePolicy(TransformerConfig config) {
        String configuredSources = config.getOption(
                "predicate-sources",
                "volatile,thread,environment,time"
        );
        Set<PredicateSource> sources = EnumSet.noneOf(PredicateSource.class);
        for (String token : configuredSources.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                sources.add(PredicateSource.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                log("Ignoring unknown opaque predicate source '{}'", token);
            }
        }
        if (sources.isEmpty()) {
            sources.add(PredicateSource.VOLATILE);
            sources.add(PredicateSource.THREAD);
        }
        Set<PredicateFamily> families = EnumSet.noneOf(PredicateFamily.class);
        String configuredFamilies = config.getOption(
                "predicate-families",
                "arithmetic,bitwise,reversible,modular,lookup-table,stateful,argument-derived,interprocedural"
        );
        for (String token : configuredFamilies.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                families.add(PredicateFamily.valueOf(
                        token.trim().replace('-', '_').toUpperCase(Locale.ROOT)
                ));
            } catch (IllegalArgumentException ignored) {
                log("Ignoring unknown opaque predicate family '{}'", token);
            }
        }
        if (families.isEmpty()) {
            families.add(PredicateFamily.ARITHMETIC);
            families.add(PredicateFamily.BITWISE);
        }
        boolean volatileState = getBooleanOption(config, "volatile-predicate-state", true);
        if (!volatileState) {
            sources.remove(PredicateSource.VOLATILE);
            families.remove(PredicateFamily.STATEFUL);
        }
        return new PredicatePolicy(
                orderedEnumSet(PredicateSource.class, sources),
                orderedEnumSet(PredicateFamily.class, families),
                clamp(getIntOption(config, "predicate-cost-budget", 96), 1, 10_000),
                clamp(getIntOption(config, "predicate-camouflage-rate", 35), 0, 100),
                clamp(getIntOption(config, "predicate-local-rate", 30), 0, 100),
                getBooleanOption(config, "heavy-predicates-in-loops", false),
                clamp(getIntOption(config, "hot-loop-max-predicate-cost", 2), 1, 8),
                volatileState
        );
    }

    private FlattenPolicy flattenPolicy(TransformerConfig config,
                                        int probability,
                                        int minimumBlocks,
                                        int maximumBlocks,
                                        int maximumOutputInstructions) {
        Set<DispatcherStyle> styles = EnumSet.noneOf(DispatcherStyle.class);
        String configured = config.getOption(
                "dispatcher-styles",
                "lookup,table,computed,nested,split"
        );
        for (String token : configured.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                styles.add(DispatcherStyle.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                log("Ignoring unknown dispatcher style '{}'", token);
            }
        }
        if (styles.isEmpty()) {
            styles.add(DispatcherStyle.LOOKUP);
        }
        return new FlattenPolicy(
                orderedEnumSet(DispatcherStyle.class, styles),
                probability,
                minimumBlocks,
                maximumBlocks,
                clamp(getIntOption(config, "flatten-min-complexity", 8), 1, 10_000),
                clamp(getIntOption(config, "flatten-cost-budget", 512), 16, 100_000),
                maximumOutputInstructions,
                clamp(getIntOption(config, "partial-flattening-rate", 35), 0, 100),
                clamp(getIntOption(config, "partial-region-rate", 55), 0, 100),
                getBooleanOption(config, "flatten-hot-loops", false),
                clamp(getIntOption(config, "state-reencode-rate", 75), 0, 100),
                clamp(getIntOption(config, "fake-dispatcher-states", 3), 0, 64),
                clamp(getIntOption(config, "block-clone-rate", 30), 0, 100),
                clamp(getIntOption(config, "max-exception-handlers", 0), 0, 128)
        );
    }

    private enum PredicateSource {
        VOLATILE,
        THREAD,
        ENVIRONMENT,
        TIME
    }

    private enum PredicateFamily {
        ARITHMETIC,
        BITWISE,
        REVERSIBLE,
        MODULAR,
        LOOKUP_TABLE,
        STATEFUL,
        ARGUMENT_DERIVED,
        INTERPROCEDURAL
    }

    private enum DispatcherStyle {
        LOOKUP,
        TABLE,
        COMPUTED,
        NESTED,
        SPLIT
    }

    private enum ValueKind {
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        REFERENCE,
        CONSTANT,
        STATE,
        LOCAL
    }

    private int predicateCost(PredicateFamily family) {
        return switch (family) {
            case BITWISE -> 1;
            case ARITHMETIC, REVERSIBLE, STATEFUL, ARGUMENT_DERIVED -> 2;
            case MODULAR -> 3;
            case LOOKUP_TABLE -> 5;
            case INTERPROCEDURAL -> 6;
        };
    }

    private LoopProfile loopProfile(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        Map<AbstractInsnNode, Integer> indexes = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            instructions.add(instruction);
            indexes.put(instruction, index++);
        }
        Set<AbstractInsnNode> hot = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode instruction : instructions) {
            int source = indexes.get(instruction);
            if (instruction instanceof JumpInsnNode jump) {
                markBackEdge(instructions, indexes, hot, source, jump.label);
            } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
                markBackEdge(instructions, indexes, hot, source, tableSwitch.dflt);
                for (LabelNode label : tableSwitch.labels) {
                    markBackEdge(instructions, indexes, hot, source, label);
                }
            } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
                markBackEdge(instructions, indexes, hot, source, lookupSwitch.dflt);
                for (LabelNode label : lookupSwitch.labels) {
                    markBackEdge(instructions, indexes, hot, source, label);
                }
            }
        }
        return new LoopProfile(hot);
    }

    private void markBackEdge(List<AbstractInsnNode> instructions,
                              Map<AbstractInsnNode, Integer> indexes,
                              Set<AbstractInsnNode> hot,
                              int source,
                              LabelNode target) {
        Integer destination = indexes.get(target);
        if (destination == null || destination > source) {
            return;
        }
        for (int index = destination; index <= source; index++) {
            hot.add(instructions.get(index));
        }
    }

    private Random random() {
        Random random = ACTIVE_RANDOM.get();
        return random == null ? SECURE_RANDOM : random;
    }

    private Frame<BasicValue>[] analyzeWithHeadroom(String owner, MethodNode method, boolean verify)
            throws AnalyzerException {
        int originalMaxStack = Math.max(0, method.maxStack);
        int headroom = Math.max(64, originalMaxStack + 32);
        AnalyzerException last = null;
        while (headroom <= 4096) {
            method.maxStack = headroom;
            try {
                Analyzer<BasicValue> analyzer = verify
                        ? new Analyzer<>(new BasicVerifier())
                        : new Analyzer<>(new BasicInterpreter());
                Frame<BasicValue>[] frames = analyzer.analyze(owner, method);
                int observed = originalMaxStack;
                for (Frame<BasicValue> frame : frames) {
                    if (frame != null) observed = Math.max(observed, frame.getStackSize());
                }
                method.maxStack = Math.min(65_535, observed + 8);
                return frames;
            } catch (AnalyzerException exception) {
                last = exception;
                if (!isInsufficientMaxStack(exception)) {
                    method.maxStack = originalMaxStack;
                    throw exception;
                }
                headroom *= 2;
            }
        }
        method.maxStack = originalMaxStack;
        throw last == null ? new AnalyzerException(null, "Could not analyze method") : last;
    }

    private boolean isInsufficientMaxStack(AnalyzerException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("Insufficient maximum stack size");
    }

    private MethodNode copyMethod(MethodNode source) {
        String[] exceptions = source.exceptions == null || source.exceptions.isEmpty()
                ? null
                : source.exceptions.toArray(new String[0]);
        MethodNode copy = new MethodNode(
                source.access,
                source.name,
                source.desc,
                source.signature,
                exceptions
        );
        source.accept(copy);
        return copy;
    }

    private <E extends Enum<E>> Set<E> orderedEnumSet(Class<E> type, Set<E> values) {
        EnumSet<E> ordered = EnumSet.noneOf(type);
        ordered.addAll(values);
        return Collections.unmodifiableSet(ordered);
    }

    private record PredicatePolicy(Set<PredicateSource> sources,
                                   Set<PredicateFamily> families,
                                   int costBudget,
                                   int camouflageRate,
                                   int localValueRate,
                                   boolean heavyInLoops,
                                   int hotLoopMaximumCost,
                                   boolean volatileState) {
    }

    private record FlattenPolicy(Set<DispatcherStyle> styles,
                                 int probability,
                                 int minimumBlocks,
                                 int maximumBlocks,
                                 int minimumComplexity,
                                 int costBudget,
                                 int maximumOutputInstructions,
                                 int partialRate,
                                 int partialRegionRate,
                                 boolean flattenHotLoops,
                                 int stateReencodeRate,
                                 int fakeStates,
                                 int blockCloneRate,
                                 int maximumExceptionHandlers) {
    }

    private record StateCodec(int stateSlot,
                              int xorSlot,
                              int addSlot,
                              int classXorKey,
                              int classAddKey,
                              int reencodeRate) {
    }

    private record SwitchEntry(int key, LabelNode label) {
    }

    private record PredicateClassContext(String owner,
                                         String stateField,
                                         String tableField,
                                         String helperName,
                                         int keyA,
                                         int keyB,
                                         int modulus) {
    }

    private record PredicateEmission(InsnList instructions,
                                     int expected,
                                     PredicateFamily family,
                                     int cost) {
    }

    private record ValueSource(ValueKind kind, int local, int constant) {
    }

    private record PreparedValue(InsnList prefix, ValueSource source) {
    }

    private record LoopProfile(Set<AbstractInsnNode> hotInstructions) {
        boolean isHot(AbstractInsnNode instruction) {
            return hotInstructions.contains(instruction);
        }
    }

    private static final class PredicateBudget {
        private int remaining;

        private PredicateBudget(int remaining) {
            this.remaining = remaining;
        }

        int remaining() {
            return remaining;
        }

        void consume(int cost) {
            remaining = Math.max(0, remaining - Math.max(0, cost));
        }
    }

    private static final class FlowMetrics {
        private final LongAdder predicates = new LongAdder();
        private final LongAdder flattenedMethods = new LongAdder();
        private final LongAdder partiallyFlattenedMethods = new LongAdder();
        private final LongAdder fakeStates = new LongAdder();
        private final LongAdder skippedMethods = new LongAdder();

        private void add(FlowMetrics other) {
            predicates.add(other.predicates.sum());
            flattenedMethods.add(other.flattenedMethods.sum());
            partiallyFlattenedMethods.add(other.partiallyFlattenedMethods.sum());
            fakeStates.add(other.fakeStates.sum());
        }
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return defaultValue;
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private long getLongOption(TransformerConfig config, String key, long defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
