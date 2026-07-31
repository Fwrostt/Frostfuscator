package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Splits independent, side-effect-free primitive expression branches across two
 * concurrent workers. Generated workers exchange captured values and results
 * through volatile fields and are joined before the original operation resumes.
 */
public final class ThreadInterleavedFlowTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String COMPLETABLE_FUTURE = "java/util/concurrent/CompletableFuture";
    private static final String RUNNABLE = "java/lang/Runnable";

    @Override
    public String getName() {
        return "thread-interleaved-flow";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public int orderWeight() {
        return -1_000;
    }

    @Override
    public void transform(Context context) {
        Counts counts = apply(context.pool(), context.config());
        context.stats().add("threadInterleavedExpressions", counts.expressions.sum());
        context.stats().add("threadInterleavedWorkers", counts.workers.sum());
        context.stats().add("threadInterleavedRejectedMethods", counts.rejectedMethods.sum());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private Counts apply(ClassPool pool, TransformerConfig config) {
        Options options = Options.from(config);
        long runSeed = options.seed == 0L ? SECURE_RANDOM.nextLong() : options.seed;
        ConcurrentLinkedQueue<ClassNode> generatedWorkers = new ConcurrentLinkedQueue<>();
        Set<String> reservedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        reservedNames.addAll(pool.getClassMap().keySet());
        Counts counts = new Counts();

        pool.forEachClass(owner -> {
            if (!eligibleClass(owner, config, pool)) return;
            Random random = new Random(runSeed ^ ((long) owner.name.hashCode() << 32) ^ owner.methods.size());
            List<ClassNode> additions = new ArrayList<>();
            int changedInClass = 0;
            int methodIndex = 0;

            for (MethodNode method : new ArrayList<>(owner.methods)) {
                if (changedInClass >= options.maximumPerClass) break;
                if (isExcludedMember(method.name, config) || !eligibleMethod(method, options)) {
                    counts.rejectedMethods.increment();
                    methodIndex++;
                    continue;
                }

                List<Candidate> candidates = candidates(method, options);
                int changedInMethod = 0;
                for (Candidate candidate : candidates) {
                    if (changedInMethod >= options.maximumPerMethod
                            || changedInClass >= options.maximumPerClass) break;
                    if (random.nextInt(100) >= options.probability) continue;
                    if (!stillPresent(method, candidate)) continue;

                    Worker left = worker(owner, methodIndex, changedInMethod, 0,
                            candidate.left, random, reservedNames);
                    Worker right = worker(owner, methodIndex, changedInMethod, 1,
                            candidate.right, random, reservedNames);
                    int nextLocal = Math.max(method.maxLocals, argumentSlots(method)) + 4;
                    InsnList replacement = replacement(method, candidate, left, right);
                    if (method.instructions.size() + replacement.size() - candidate.instructions.size()
                            > options.maximumOutputInstructions) {
                        reservedNames.remove(left.node.name);
                        reservedNames.remove(right.node.name);
                        continue;
                    }

                    method.instructions.insertBefore(candidate.start, replacement);
                    for (AbstractInsnNode instruction : candidate.instructions) {
                        method.instructions.remove(instruction);
                    }
                    method.maxLocals = nextLocal;
                    additions.add(left.node);
                    additions.add(right.node);
                    changedInMethod++;
                    changedInClass++;
                    counts.expressions.increment();
                    counts.workers.add(2L);
                }
                methodIndex++;
            }

            if (!additions.isEmpty()) {
                generatedWorkers.addAll(additions);
                pool.markFramesDirty(owner.name);
                detail("Split {} primitive expression(s) across {} workers in {}",
                        changedInClass, additions.size(), owner.name);
            }
        });

        List<ClassNode> orderedWorkers = new ArrayList<>(generatedWorkers);
        orderedWorkers.sort(Comparator.comparing(node -> node.name));
        for (ClassNode worker : orderedWorkers) {
            pool.addClass(worker.name, worker);
            pool.markDirty(worker.name);
            pool.excludeFromTransformation(worker.name, "generated thread-interleaved worker");
        }
        return counts;
    }

    private boolean eligibleClass(ClassNode owner, TransformerConfig config, ClassPool pool) {
        return owner.version >= Opcodes.V1_8
                && (owner.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) == 0
                && shouldProcess(owner.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions());
    }

    private boolean eligibleMethod(MethodNode method, Options options) {
        if (method.instructions == null || method.instructions.size() < options.minimumExpressionInstructions
                || method.instructions.size() > options.maximumMethodInstructions) return false;
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED
                | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return false;
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.MONITORENTER
                    || instruction.getOpcode() == Opcodes.MONITOREXIT) return false;
        }
        return true;
    }

    private List<Candidate> candidates(MethodNode method, Options options) {
        List<Candidate> discovered = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            Operator operator = Operator.forOpcode(instruction.getOpcode());
            Candidate candidate = null;
            if (operator != null && operator.binary) {
                ParseResult right = parse(instruction.getPrevious());
                ParseResult left = right == null ? null : parse(right.before);
                if (left != null && right != null
                        && left.expression.kind() == operator.left
                        && right.expression.kind() == operator.right) {
                    candidate = createCandidate(instruction, operator, left, right, true, options);
                }
            } else if (instruction instanceof JumpInsnNode
                    && instruction.getOpcode() >= Opcodes.IF_ICMPEQ
                    && instruction.getOpcode() <= Opcodes.IF_ICMPLE) {
                ParseResult right = parse(instruction.getPrevious());
                ParseResult left = right == null ? null : parse(right.before);
                if (left != null && right != null
                        && left.expression.kind() == Kind.INTEGER
                        && right.expression.kind() == Kind.INTEGER) {
                    candidate = createCandidate(instruction, null, left, right, false, options);
                }
            }
            if (candidate != null) discovered.add(candidate);
        }

        discovered.sort(Comparator.comparingInt(Candidate::complexity).reversed());
        Set<AbstractInsnNode> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : discovered) {
            if (candidate.instructions.stream().anyMatch(claimed::contains)) continue;
            claimed.addAll(candidate.instructions);
            selected.add(candidate);
        }
        selected.sort(Comparator.comparingInt(candidate -> method.instructions.indexOf(candidate.start)));
        return selected;
    }

    private Candidate createCandidate(AbstractInsnNode terminal, Operator operator,
                                      ParseResult left, ParseResult right, boolean removeTerminal,
                                      Options options) {
        int complexity = left.expression.size() + right.expression.size() + (removeTerminal ? 1 : 0);
        if (left.expression.size() < options.minimumBranchInstructions
                || right.expression.size() < options.minimumBranchInstructions
                || complexity < options.minimumExpressionInstructions
                || complexity > options.maximumExpressionInstructions) return null;
        if (captureSlots(left.expression) > options.maximumCaptureSlots
                || captureSlots(right.expression) > options.maximumCaptureSlots) return null;

        List<AbstractInsnNode> instructions = new ArrayList<>();
        instructions.addAll(left.instructions);
        instructions.addAll(right.instructions);
        if (removeTerminal) instructions.add(terminal);
        AbstractInsnNode start = left.start;
        AbstractInsnNode end = removeTerminal ? terminal : right.instructions.get(right.instructions.size() - 1);
        if (!contiguous(start, end, instructions)) return null;
        return new Candidate(start, operator, left.expression, right.expression,
                List.copyOf(instructions), complexity);
    }

    private int captureSlots(Expression expression) {
        LinkedHashMap<Capture, String> captures = new LinkedHashMap<>();
        expression.collectCaptures(captures);
        return captures.keySet().stream().mapToInt(capture -> capture.kind.slots).sum();
    }

    private boolean contiguous(AbstractInsnNode start, AbstractInsnNode end,
                               List<AbstractInsnNode> expected) {
        Set<AbstractInsnNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        nodes.addAll(expected);
        int encountered = 0;
        AbstractInsnNode cursor = start;
        while (cursor != null) {
            if (cursor.getOpcode() < 0) return false;
            if (nodes.contains(cursor)) {
                encountered++;
                if (cursor == end) return encountered == nodes.size();
                cursor = cursor.getNext();
                continue;
            }
            AbstractInsnNode afterNoise = afterNeutralNoise(cursor);
            if (afterNoise == null) return false;
            cursor = afterNoise;
        }
        return false;
    }

    private boolean stillPresent(MethodNode method, Candidate candidate) {
        if (method.instructions.indexOf(candidate.start) < 0) return false;
        for (AbstractInsnNode instruction : candidate.instructions) {
            if (method.instructions.indexOf(instruction) < 0) return false;
        }
        return true;
    }

    private ParseResult parse(AbstractInsnNode instruction) {
        instruction = previousExpressionInstruction(instruction);
        if (instruction == null || instruction.getOpcode() < 0) return null;
        int opcode = instruction.getOpcode();
        Expression expression;
        AbstractInsnNode before;
        List<AbstractInsnNode> instructions = new ArrayList<>();

        Kind loadKind = loadKind(opcode);
        if (loadKind != null && instruction instanceof VarInsnNode variable) {
            expression = new LoadExpression(variable.var, loadKind);
            before = instruction.getPrevious();
        } else {
            ConstantExpression constant = constant(instruction);
            if (constant != null) {
                expression = constant;
                before = instruction.getPrevious();
            } else {
                Operator operator = Operator.forOpcode(opcode);
                if (operator == null) return null;
                if (operator.binary) {
                    ParseResult right = parse(instruction.getPrevious());
                    ParseResult left = right == null ? null : parse(right.before);
                    if (left == null || right == null
                            || left.expression.kind() != operator.left
                            || right.expression.kind() != operator.right) return null;
                    expression = new BinaryExpression(opcode, operator.result,
                            left.expression, right.expression);
                    instructions.addAll(left.instructions);
                    instructions.addAll(right.instructions);
                    before = left.before;
                } else {
                    ParseResult operand = parse(instruction.getPrevious());
                    if (operand == null || operand.expression.kind() != operator.left) return null;
                    expression = new UnaryExpression(opcode, operator.result, operand.expression);
                    instructions.addAll(operand.instructions);
                    before = operand.before;
                }
            }
        }
        instructions.add(instruction);
        return new ParseResult(expression, instructions.get(0), before, List.copyOf(instructions));
    }

    private AbstractInsnNode previousExpressionInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction;
        while (cursor != null) {
            if (cursor.getOpcode() == Opcodes.POP) {
                AbstractInsnNode value = cursor.getPrevious();
                if (value != null && (value.getOpcode() == Opcodes.ICONST_0
                        || value.getOpcode() == Opcodes.ACONST_NULL)) {
                    cursor = value.getPrevious();
                    continue;
                }
                if (value != null && value.getOpcode() == Opcodes.INEG) {
                    AbstractInsnNode one = value.getPrevious();
                    if (one != null && one.getOpcode() == Opcodes.ICONST_1) {
                        cursor = one.getPrevious();
                        continue;
                    }
                }
            } else if (cursor.getOpcode() == Opcodes.POP2) {
                AbstractInsnNode value = cursor.getPrevious();
                if (value != null && value.getOpcode() == Opcodes.LCONST_0) {
                    cursor = value.getPrevious();
                    continue;
                }
            }
            return cursor;
        }
        return null;
    }

    private AbstractInsnNode afterNeutralNoise(AbstractInsnNode instruction) {
        if (instruction.getOpcode() == Opcodes.ICONST_0
                || instruction.getOpcode() == Opcodes.ACONST_NULL) {
            AbstractInsnNode pop = instruction.getNext();
            return pop != null && pop.getOpcode() == Opcodes.POP ? pop.getNext() : null;
        }
        if (instruction.getOpcode() == Opcodes.LCONST_0) {
            AbstractInsnNode pop = instruction.getNext();
            return pop != null && pop.getOpcode() == Opcodes.POP2 ? pop.getNext() : null;
        }
        if (instruction.getOpcode() == Opcodes.ICONST_1) {
            AbstractInsnNode negate = instruction.getNext();
            AbstractInsnNode pop = negate == null ? null : negate.getNext();
            return negate != null && negate.getOpcode() == Opcodes.INEG
                    && pop != null && pop.getOpcode() == Opcodes.POP ? pop.getNext() : null;
        }
        return null;
    }

    private InsnList replacement(MethodNode method, Candidate candidate, Worker left, Worker right) {
        int firstWorker = Math.max(method.maxLocals, argumentSlots(method));
        int firstFuture = firstWorker + 1;
        int secondWorker = firstWorker + 2;
        int secondFuture = firstWorker + 3;
        InsnList list = new InsnList();
        launch(list, left, firstWorker, firstFuture);
        launch(list, right, secondWorker, secondFuture);
        join(list, firstFuture);
        join(list, secondFuture);
        readResult(list, left, firstWorker);
        readResult(list, right, secondWorker);
        if (candidate.operator != null) list.add(new InsnNode(candidate.operator.opcode));
        return list;
    }

    private void launch(InsnList list, Worker worker, int workerLocal, int futureLocal) {
        list.add(new TypeInsnNode(Opcodes.NEW, worker.node.name));
        list.add(new InsnNode(Opcodes.DUP));
        for (Capture capture : worker.captures.keySet()) {
            list.add(new VarInsnNode(capture.kind.loadOpcode, capture.local));
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, worker.node.name, "<init>",
                constructorDescriptor(worker.captures.keySet()), false));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.ASTORE, workerLocal));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPLETABLE_FUTURE, "runAsync",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", false));
        list.add(new VarInsnNode(Opcodes.ASTORE, futureLocal));
    }

    private void join(InsnList list, int futureLocal) {
        list.add(new VarInsnNode(Opcodes.ALOAD, futureLocal));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPLETABLE_FUTURE, "join",
                "()Ljava/lang/Object;", false));
        list.add(new InsnNode(Opcodes.POP));
    }

    private void readResult(InsnList list, Worker worker, int workerLocal) {
        list.add(new VarInsnNode(Opcodes.ALOAD, workerLocal));
        list.add(new FieldInsnNode(Opcodes.GETFIELD, worker.node.name,
                worker.resultField, worker.resultKind.descriptor));
    }

    private Worker worker(ClassNode owner, int methodIndex, int expressionIndex, int branch,
                          Expression expression, Random random, Set<String> reservedNames) {
        String workerName = uniqueWorkerName(owner.name, methodIndex, expressionIndex, branch,
                random, reservedNames);
        LinkedHashMap<Capture, String> captures = new LinkedHashMap<>();
        expression.collectCaptures(captures);
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Capture capture : new ArrayList<>(captures.keySet())) {
            captures.put(capture, randomIdentifier(random, fieldNames));
        }
        String resultField = randomIdentifier(random, fieldNames);

        ClassNode node = new ClassNode();
        node.version = Math.max(Opcodes.V1_8, owner.version);
        node.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        node.name = workerName;
        node.superName = "java/lang/Object";
        node.interfaces = new ArrayList<>(List.of(RUNNABLE));
        for (Map.Entry<Capture, String> entry : captures.entrySet()) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                    entry.getValue(), entry.getKey().kind.descriptor, null, null));
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                resultField, expression.kind().descriptor, null, null));
        node.methods.add(workerConstructor(workerName, captures));
        node.methods.add(workerRun(workerName, resultField, expression, captures));
        return new Worker(node, captures, resultField, expression.kind());
    }

    private MethodNode workerConstructor(String owner, LinkedHashMap<Capture, String> captures) {
        String descriptor = constructorDescriptor(captures.keySet());
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "<init>", descriptor, null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        int local = 1;
        for (Map.Entry<Capture, String> entry : captures.entrySet()) {
            constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            constructor.instructions.add(new VarInsnNode(entry.getKey().kind.loadOpcode, local));
            constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner,
                    entry.getValue(), entry.getKey().kind.descriptor));
            local += entry.getKey().kind.slots;
        }
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxLocals = local;
        constructor.maxStack = 3;
        return constructor;
    }

    private MethodNode workerRun(String owner, String resultField, Expression expression,
                                 LinkedHashMap<Capture, String> captures) {
        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "run", "()V", null, null);
        run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        expression.emit(run.instructions, owner, captures);
        run.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner,
                resultField, expression.kind().descriptor));
        run.instructions.add(new InsnNode(Opcodes.RETURN));
        run.maxLocals = 1;
        run.maxStack = Math.max(4, expression.stackSize() + 2);
        return run;
    }

    private String uniqueWorkerName(String owner, int methodIndex, int expressionIndex, int branch,
                                    Random random, Set<String> reservedNames) {
        int packageEnd = owner.lastIndexOf('/');
        String prefix = packageEnd < 0 ? "" : owner.substring(0, packageEnd + 1);
        String candidate;
        do {
            candidate = prefix + randomClassIdentifier(random) + Integer.toUnsignedString(
                    methodIndex * 131 + expressionIndex * 17 + branch, 36);
        } while (!reservedNames.add(candidate));
        return candidate;
    }

    private String randomClassIdentifier(Random random) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder value = new StringBuilder(10);
        value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        for (int index = 1; index < 10; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private String randomIdentifier(Random random, Set<String> used) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String candidate;
        do {
            StringBuilder value = new StringBuilder(8);
            for (int index = 0; index < 8; index++) {
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            candidate = value.toString();
        } while (!used.add(candidate));
        return candidate;
    }

    private String constructorDescriptor(Set<Capture> captures) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Capture capture : captures) descriptor.append(capture.kind.descriptor);
        return descriptor.append(")V").toString();
    }

    private int argumentSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) slots += argument.getSize();
        return slots;
    }

    private Kind loadKind(int opcode) {
        return switch (opcode) {
            case Opcodes.ILOAD -> Kind.INTEGER;
            case Opcodes.LLOAD -> Kind.LONG;
            default -> null;
        };
    }

    private ConstantExpression constant(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return new ConstantExpression(opcode - Opcodes.ICONST_0, Kind.INTEGER);
        }
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) {
            return new ConstantExpression((long) (opcode - Opcodes.LCONST_0), Kind.LONG);
        }
        if (instruction instanceof IntInsnNode integer
                && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return new ConstantExpression(integer.operand, Kind.INTEGER);
        }
        if (instruction instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Integer integer) return new ConstantExpression(integer, Kind.INTEGER);
            if (ldc.cst instanceof Long value) return new ConstantExpression(value, Kind.LONG);
        }
        return null;
    }

    private enum Kind {
        INTEGER("I", Opcodes.ILOAD, 1),
        LONG("J", Opcodes.LLOAD, 2);

        private final String descriptor;
        private final int loadOpcode;
        private final int slots;

        Kind(String descriptor, int loadOpcode, int slots) {
            this.descriptor = descriptor;
            this.loadOpcode = loadOpcode;
            this.slots = slots;
        }
    }

    private enum Operator {
        IADD(Opcodes.IADD, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        ISUB(Opcodes.ISUB, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        IMUL(Opcodes.IMUL, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        IAND(Opcodes.IAND, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        IOR(Opcodes.IOR, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        IXOR(Opcodes.IXOR, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        ISHL(Opcodes.ISHL, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        ISHR(Opcodes.ISHR, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        IUSHR(Opcodes.IUSHR, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER, true),
        LADD(Opcodes.LADD, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LSUB(Opcodes.LSUB, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LMUL(Opcodes.LMUL, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LAND(Opcodes.LAND, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LOR(Opcodes.LOR, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LXOR(Opcodes.LXOR, Kind.LONG, Kind.LONG, Kind.LONG, true),
        LSHL(Opcodes.LSHL, Kind.LONG, Kind.INTEGER, Kind.LONG, true),
        LSHR(Opcodes.LSHR, Kind.LONG, Kind.INTEGER, Kind.LONG, true),
        LUSHR(Opcodes.LUSHR, Kind.LONG, Kind.INTEGER, Kind.LONG, true),
        LCMP(Opcodes.LCMP, Kind.LONG, Kind.LONG, Kind.INTEGER, true),
        INEG(Opcodes.INEG, Kind.INTEGER, null, Kind.INTEGER, false),
        LNEG(Opcodes.LNEG, Kind.LONG, null, Kind.LONG, false),
        I2L(Opcodes.I2L, Kind.INTEGER, null, Kind.LONG, false),
        L2I(Opcodes.L2I, Kind.LONG, null, Kind.INTEGER, false),
        I2B(Opcodes.I2B, Kind.INTEGER, null, Kind.INTEGER, false),
        I2C(Opcodes.I2C, Kind.INTEGER, null, Kind.INTEGER, false),
        I2S(Opcodes.I2S, Kind.INTEGER, null, Kind.INTEGER, false);

        private static final Map<Integer, Operator> BY_OPCODE = new java.util.HashMap<>();

        static {
            for (Operator operator : values()) BY_OPCODE.put(operator.opcode, operator);
        }

        private final int opcode;
        private final Kind left;
        private final Kind right;
        private final Kind result;
        private final boolean binary;

        Operator(int opcode, Kind left, Kind right, Kind result, boolean binary) {
            this.opcode = opcode;
            this.left = left;
            this.right = right;
            this.result = result;
            this.binary = binary;
        }

        private static Operator forOpcode(int opcode) {
            return BY_OPCODE.get(opcode);
        }
    }

    private sealed interface Expression permits LoadExpression, ConstantExpression,
            UnaryExpression, BinaryExpression {
        Kind kind();
        int size();
        int stackSize();
        void collectCaptures(LinkedHashMap<Capture, String> captures);
        void emit(InsnList output, String workerOwner, Map<Capture, String> fields);
    }

    private record LoadExpression(int local, Kind kind) implements Expression {
        @Override public int size() { return 1; }
        @Override public int stackSize() { return kind.slots; }

        @Override
        public void collectCaptures(LinkedHashMap<Capture, String> captures) {
            captures.putIfAbsent(new Capture(local, kind), null);
        }

        @Override
        public void emit(InsnList output, String workerOwner, Map<Capture, String> fields) {
            Capture capture = new Capture(local, kind);
            output.add(new VarInsnNode(Opcodes.ALOAD, 0));
            output.add(new FieldInsnNode(Opcodes.GETFIELD, workerOwner,
                    fields.get(capture), kind.descriptor));
        }
    }

    private record ConstantExpression(Object value, Kind kind) implements Expression {
        @Override public int size() { return 1; }
        @Override public int stackSize() { return kind.slots; }
        @Override public void collectCaptures(LinkedHashMap<Capture, String> captures) { }

        @Override
        public void emit(InsnList output, String workerOwner, Map<Capture, String> fields) {
            if (kind == Kind.INTEGER) pushInteger(output, (Integer) value);
            else pushLong(output, (Long) value);
        }
    }

    private record UnaryExpression(int opcode, Kind kind, Expression operand) implements Expression {
        @Override public int size() { return operand.size() + 1; }
        @Override public int stackSize() { return Math.max(operand.stackSize(), kind.slots); }

        @Override
        public void collectCaptures(LinkedHashMap<Capture, String> captures) {
            operand.collectCaptures(captures);
        }

        @Override
        public void emit(InsnList output, String workerOwner, Map<Capture, String> fields) {
            operand.emit(output, workerOwner, fields);
            output.add(new InsnNode(opcode));
        }
    }

    private record BinaryExpression(int opcode, Kind kind,
                                    Expression left, Expression right) implements Expression {
        @Override public int size() { return left.size() + right.size() + 1; }

        @Override
        public int stackSize() {
            return Math.max(left.stackSize(), left.kind().slots + right.stackSize());
        }

        @Override
        public void collectCaptures(LinkedHashMap<Capture, String> captures) {
            left.collectCaptures(captures);
            right.collectCaptures(captures);
        }

        @Override
        public void emit(InsnList output, String workerOwner, Map<Capture, String> fields) {
            left.emit(output, workerOwner, fields);
            right.emit(output, workerOwner, fields);
            output.add(new InsnNode(opcode));
        }
    }

    private static void pushInteger(InsnList output, int value) {
        if (value >= -1 && value <= 5) output.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            output.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            output.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else output.add(new LdcInsnNode(value));
    }

    private static void pushLong(InsnList output, long value) {
        if (value == 0L) output.add(new InsnNode(Opcodes.LCONST_0));
        else if (value == 1L) output.add(new InsnNode(Opcodes.LCONST_1));
        else output.add(new LdcInsnNode(value));
    }

    private record Capture(int local, Kind kind) { }

    private record ParseResult(Expression expression, AbstractInsnNode start,
                               AbstractInsnNode before, List<AbstractInsnNode> instructions) { }

    private record Candidate(AbstractInsnNode start, Operator operator,
                             Expression left, Expression right, List<AbstractInsnNode> instructions,
                             int complexity) { }

    private record Worker(ClassNode node, LinkedHashMap<Capture, String> captures,
                          String resultField, Kind resultKind) { }

    private static final class Counts {
        private final LongAdder expressions = new LongAdder();
        private final LongAdder workers = new LongAdder();
        private final LongAdder rejectedMethods = new LongAdder();
    }

    private record Options(int probability, int maximumPerMethod, int maximumPerClass,
                           int minimumBranchInstructions, int minimumExpressionInstructions,
                           int maximumExpressionInstructions, int maximumCaptureSlots,
                           int maximumMethodInstructions,
                           int maximumOutputInstructions, long seed) {
        private static Options from(TransformerConfig config) {
            return new Options(
                    clamp(config.getOptionInt("probability", 20), 0, 100),
                    clamp(config.getOptionInt("max-per-method", 1), 0, 8),
                    clamp(config.getOptionInt("max-per-class", 8), 0, 64),
                    clamp(config.getOptionInt("min-branch-instructions", 3), 1, 64),
                    clamp(config.getOptionInt("min-expression-instructions", 7), 3, 192),
                    clamp(config.getOptionInt("max-expression-instructions", 96), 7, 512),
                    clamp(config.getOptionInt("max-capture-slots", 16), 1, 64),
                    clamp(config.getOptionInt("max-method-instructions", 2_000), 16, 10_000),
                    clamp(config.getOptionInt("max-output-method-instructions", 8_000), 64, 20_000),
                    config.getOptionLong("seed", 0L)
            );
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
