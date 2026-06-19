package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;

/**
 * Control flow obfuscation.
 *
 * Modes:
 *   lite   - opaque predicates on unconditional jumps.
 *   medium - + number obfuscation patterns.
 *   heavy  - + scattered predicates + conditional-to-switch + optional flattening.
 *
 * All opaque predicates use runtime-dependent values (nanoTime, thread id,
 * heap state) so they cannot be constant-folded by a decompiler.
 */
public class FlowObfuscationTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "flow-obfuscation";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        String mode = config.getOption("mode", "medium").toLowerCase();
        boolean lite = mode.equals("lite");
        boolean medium = mode.equals("medium") || mode.equals("heavy");
        boolean heavy = mode.equals("heavy");
        boolean flatten = getBooleanOption(config, "flatten", heavy);
        boolean exceptionGuards = getBooleanOption(config, "exception-guards", heavy);
        boolean stackNoise = getBooleanOption(config, "stack-noise", heavy);
        int maxMethodInstructions = getIntOption(config, "max-method-instructions", 5000);
        int predicateRate = clamp(getIntOption(config, "predicate-rate", heavy ? 8 : 4), 0, 100);
        int maxPredicatesPerMethod = Math.max(0, getIntOption(config, "max-predicates-per-method", heavy ? 24 : 8));

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            if (AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            String trapField = ensureTrapField(classNode);
            boolean changed = false;
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (method.instructions.size() > maxMethodInstructions) continue;

                try {
                    if (!AccessHelper.isInitializer(method)) {
                        if (exceptionGuards) exceptionGuard(classNode.name, trapField, method);
                        if (stackNoise) stackNoise(method);
                    }
                    if (lite || medium || heavy) opaqueGoto(classNode.name, trapField, method);
                    if (medium) numberObfuscation(method);
                    if (heavy) {
                        conditionalToSwitch(method);
                        scatteredPredicates(classNode.name, trapField, method, predicateRate, maxPredicatesPerMethod);
                        if (flatten) flattenSmallMethods(method);
                    }
                    changed = true;
                } catch (Exception e) {
                    log("Flow obfuscation failed for {}.{}, skipping: {}", classNode.name, method.name, e.getMessage());
                }
            }
            if (changed) {
                pool.markDirty(classNode.name);
            }
        }
    }

    // region Lite: opaque predicates on unconditional jumps

    private void opaqueGoto(String owner, String trapField, MethodNode method) {
        List<JumpInsnNode> gotos = new ArrayList<>();
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (insn.getOpcode() == Opcodes.GOTO) {
                gotos.add((JumpInsnNode) insn);
            }
            insn = insn.getNext();
        }

        for (JumpInsnNode go : gotos) {
            LabelNode target = go.label;
            LabelNode fake = new LabelNode(new Label());
            InsnList replacement = new InsnList();
            replacement.add(opaquePredicate(owner, trapField));
            replacement.add(new JumpInsnNode(Opcodes.IFNE, fake));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, target));
            replacement.add(fake);
            replacement.add(new LdcInsnNode(RANDOM.nextInt()));
            replacement.add(new InsnNode(Opcodes.POP));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, target));

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
            if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5 && RANDOM.nextBoolean()) {
                int value = op - Opcodes.ICONST_0;
                int left = RANDOM.nextInt();
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
            if (RANDOM.nextBoolean()) continue;

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

    private void scatteredPredicates(String owner, String trapField, MethodNode method, int predicateRate, int maxPredicates) {
        if (predicateRate <= 0 || maxPredicates <= 0) return;
        int inserted = 0;
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (inserted >= maxPredicates) break;
            if (RANDOM.nextInt(100) < predicateRate && !(insn instanceof LabelNode)) {
                insertPredicate(owner, trapField, method, insn);
                inserted++;
            }
            insn = next;
        }
    }

    private void insertPredicate(String owner, String trapField, MethodNode method, AbstractInsnNode anchor) {
        LabelNode join = new LabelNode(new Label());
        InsnList guard = new InsnList();
        guard.add(opaquePredicate(owner, trapField));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, join));
        guard.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        guard.add(new InsnNode(Opcodes.ATHROW));
        guard.add(join);

        method.instructions.insertBefore(anchor, guard);
    }

    // endregion

    // region Stateful opaque guards and stack-neutral noise

    private String ensureTrapField(ClassNode classNode) {
        String name = uniqueFieldName(classNode);
        classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                name, "I", null, null));

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
        init.add(new LdcInsnNode(Type.getObjectType(classNode.name)));
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode",
                "(Ljava/lang/Object;)I", false));
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        init.add(new InsnNode(Opcodes.L2I));
        init.add(new InsnNode(Opcodes.IXOR));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, name, "I"));
        clinit.instructions.insert(init);
        return name;
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
        int length = 4 + RANDOM.nextInt(7);
        StringBuilder builder = new StringBuilder(length);
        builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        for (int i = 1; i < length; i++) {
            builder.append(body.charAt(RANDOM.nextInt(body.length())));
        }
        return builder.toString();
    }

    private InsnList opaquePredicate(String owner, String trapField) {
        return switch (RANDOM.nextInt(4)) {
            case 0 -> opaqueEvenPredicate(owner, trapField);
            case 1 -> opaqueMirrorPredicate(owner, trapField);
            case 2 -> opaqueShiftPredicate(owner, trapField);
            default -> opaqueBitCountPredicate(owner, trapField);
        };
    }

    private InsnList runtimeSeed(String owner, String trapField) {
        InsnList list = new InsnList();
        list.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, trapField, "I"));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getId", "()J", false));
        list.add(new InsnNode(Opcodes.L2I));
        list.add(new InsnNode(Opcodes.IXOR));
        return list;
    }

    private InsnList opaqueEvenPredicate(String owner, String trapField) {
        InsnList list = runtimeSeed(owner, trapField);
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new InsnNode(Opcodes.IADD));
        list.add(new InsnNode(Opcodes.IMUL));
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new InsnNode(Opcodes.IAND));
        return list;
    }

    private InsnList opaqueMirrorPredicate(String owner, String trapField) {
        InsnList list = runtimeSeed(owner, trapField);
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new InsnNode(Opcodes.IAND));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(Opcodes.IXOR));
        return list;
    }

    private InsnList opaqueShiftPredicate(String owner, String trapField) {
        InsnList list = runtimeSeed(owner, trapField);
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new InsnNode(Opcodes.ISHL));
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new InsnNode(Opcodes.IAND));
        return list;
    }

    private InsnList opaqueBitCountPredicate(String owner, String trapField) {
        InsnList list = runtimeSeed(owner, trapField);
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "bitCount",
                "(I)I", false));
        list.add(new InsnNode(Opcodes.SWAP));
        list.add(new InsnNode(Opcodes.ICONST_M1));
        list.add(new InsnNode(Opcodes.IXOR));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "bitCount",
                "(I)I", false));
        list.add(new InsnNode(Opcodes.IADD));
        list.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        list.add(new InsnNode(Opcodes.IXOR));
        return list;
    }

    private void exceptionGuard(String owner, String trapField, MethodNode method) {
        AbstractInsnNode first = firstExecutable(method);
        if (first == null) return;

        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        LabelNode handler = new LabelNode(new Label());
        LabelNode join = new LabelNode(new Label());

        InsnList guard = new InsnList();
        guard.add(start);
        guard.add(opaquePredicate(owner, trapField));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, join));
        guard.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        guard.add(new InsnNode(Opcodes.ATHROW));
        guard.add(end);
        guard.add(new JumpInsnNode(Opcodes.GOTO, join));
        guard.add(handler);
        guard.add(new InsnNode(Opcodes.POP));
        guard.add(join);

        method.instructions.insertBefore(first, guard);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/IllegalStateException"));
    }

    private void stackNoise(MethodNode method) {
        AbstractInsnNode first = firstExecutable(method);
        if (first == null) return;

        InsnList noise = new InsnList();
        noise.add(new LdcInsnNode(RANDOM.nextInt()));
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

    // region Heavy: flatten small methods

    private void flattenSmallMethods(MethodNode method) {
        if (AccessHelper.isInitializer(method)) return;
        if (method.instructions.size() > 80) return;
        if ((method.access & Opcodes.ACC_STATIC) == 0) return;
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return;

        List<Block> blocks = splitBlocks(method);
        blocks.removeIf(block -> block.first == null || block.end == null);
        if (blocks.size() < 3 || blocks.size() > 12) return;

        int keySlot = ASMHelper.allocateLocal(method, 1);
        LabelNode dispatch = new LabelNode(new Label());

        // Insert a label at the start of each block so the switch can target it.
        LabelNode[] labels = new LabelNode[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            labels[i] = new LabelNode(new Label());
            method.instructions.insertBefore(blocks.get(i).first, labels[i]);
            blocks.get(i).label = labels[i];
        }

        InsnList header = new InsnList();
        header.add(dispatch);
        header.add(new VarInsnNode(Opcodes.ILOAD, keySlot));
        header.add(new TableSwitchInsnNode(0, blocks.size() - 1, labels[0], labels));
        method.instructions.insertBefore(method.instructions.getFirst(), header);

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (i + 1 >= blocks.size()) {
                continue;
            }
            AbstractInsnNode insertPoint = block.end;
            if (insertPoint == null) insertPoint = method.instructions.getLast();

            int op = insertPoint.getOpcode();
            if (op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN
                    || op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN
                    || op == Opcodes.ATHROW || op == Opcodes.GOTO) {
                continue;
            }

            InsnList tail = new InsnList();
            tail.add(new LdcInsnNode(i + 1));
            tail.add(new VarInsnNode(Opcodes.ISTORE, keySlot));
            tail.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
            method.instructions.insert(insertPoint, tail);
        }

        InsnList init = new InsnList();
        init.add(new InsnNode(Opcodes.ICONST_0));
        init.add(new VarInsnNode(Opcodes.ISTORE, keySlot));
        method.instructions.insertBefore(dispatch, init);
    }

    private List<Block> splitBlocks(MethodNode method) {
        List<Block> blocks = new ArrayList<>();
        AbstractInsnNode insn = method.instructions.getFirst();
        AbstractInsnNode blockStart = null;
        AbstractInsnNode firstReal = null;
        List<AbstractInsnNode> current = new ArrayList<>();

        while (insn != null) {
            if (insn instanceof LabelNode && !current.isEmpty()) {
                blocks.add(new Block(blockStart, firstReal, current.get(current.size() - 1), new ArrayList<>(current)));
                current.clear();
                blockStart = insn;
                firstReal = null;
            }
            if (blockStart == null) blockStart = insn;
            if (!(insn instanceof LabelNode) && firstReal == null) firstReal = insn;
            current.add(insn);

            if (isBlockTerminator(insn)) {
                blocks.add(new Block(blockStart, firstReal, insn, new ArrayList<>(current)));
                current.clear();
                blockStart = null;
                firstReal = null;
            }
            insn = insn.getNext();
        }
        if (!current.isEmpty()) {
            blocks.add(new Block(blockStart, firstReal, current.get(current.size() - 1), new ArrayList<>(current)));
        }

        return blocks;
    }

    private boolean isBlockTerminator(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.GOTO || op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN
                || op == Opcodes.ATHROW || op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH;
    }

    private static class Block {
        final AbstractInsnNode start;
        final AbstractInsnNode first;
        AbstractInsnNode end;
        final List<AbstractInsnNode> insns;
        LabelNode label;

        Block(AbstractInsnNode start, AbstractInsnNode first, AbstractInsnNode end, List<AbstractInsnNode> insns) {
            this.start = start;
            this.first = first;
            this.end = end;
            this.insns = insns;
        }
    }

    // endregion

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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
