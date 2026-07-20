package dev.frost.obfuscator.transformer.flow;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Rewrites integer and long arithmetic into equivalent mixed boolean-arithmetic
 * identities. All identities operate with normal JVM modular overflow semantics.
 */
public final class MixedBooleanArithmeticTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "mixed-boolean-arithmetic";
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
    public void transform(Context context) {
        int changed = apply(context.pool(), context.config());
        context.stats().add("mbaOperations", changed);
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private int apply(ClassPool pool, TransformerConfig config) {
        int probability = intOption(config, "probability", 70, 0, 100);
        int rounds = intOption(config, "rounds", 1, 1, 3);
        int maximumPerMethod = intOption(config, "max-per-method", 64, 0, 512);
        int maximumPerClass = intOption(config, "max-per-class", 256, 0, 4_096);
        int maximumMethodInstructions = intOption(config, "max-method-instructions", 6_000, 64, 50_000);
        int maximumOutputInstructions = intOption(
                config,
                "max-output-method-instructions",
                12_000,
                maximumMethodInstructions,
                65_000
        );
        boolean includeSynthetic = booleanOption(config, "include-synthetic", false);
        Set<String> operations = operations(config);
        long configuredSeed = longOption(config, "seed", 0L);
        Random random = new Random(configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed);

        int totalChanged = 0;
        for (ClassNode owner : pool.getClasses()) {
            if (!shouldProcess(owner.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())) {
                continue;
            }
            int changedInClass = 0;
            for (MethodNode method : owner.methods) {
                if (method.instructions == null
                        || method.instructions.size() == 0
                        || method.instructions.size() > maximumMethodInstructions
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || (!includeSynthetic && (method.access & Opcodes.ACC_SYNTHETIC) != 0)
                        || changedInClass >= maximumPerClass) {
                    continue;
                }

                int nextLocal = ASMHelper.nextFreeLocal(method);
                int changedInMethod = 0;

                for (int round = 0;
                     round < rounds
                             && changedInMethod < maximumPerMethod
                             && changedInClass < maximumPerClass;
                     round++) {
                    // Each recursive MBA round needs independent scratch slots:
                    // inner identities must not overwrite locals still used by
                    // an outer identity from an earlier round.
                    LocalPair intLocals = null;
                    LocalPair longLocals = null;
                    List<AbstractInsnNode> candidates = candidates(method, operations);
                    if (candidates.isEmpty()) {
                        break;
                    }
                    for (AbstractInsnNode instruction : candidates) {
                        if (changedInMethod >= maximumPerMethod
                                || changedInClass >= maximumPerClass
                                || method.instructions.size() >= maximumOutputInstructions) {
                            break;
                        }
                        if (random.nextInt(100) >= probability) {
                            continue;
                        }

                        int opcode = instruction.getOpcode();
                        boolean wide = isLongOpcode(opcode);
                        LocalPair locals;
                        if (wide) {
                            if (longLocals == null) {
                                longLocals = new LocalPair(nextLocal, nextLocal + 2);
                                nextLocal += 4;
                                method.maxLocals = Math.max(method.maxLocals, nextLocal);
                            }
                            locals = longLocals;
                        } else {
                            if (intLocals == null) {
                                intLocals = new LocalPair(nextLocal, nextLocal + 1);
                                nextLocal += 2;
                                method.maxLocals = Math.max(method.maxLocals, nextLocal);
                            }
                            locals = intLocals;
                        }

                        InsnList replacement = rewrite(opcode, locals, random);
                        if (replacement == null
                                || method.instructions.size() + replacement.size() - 1 > maximumOutputInstructions) {
                            continue;
                        }
                        method.instructions.insertBefore(instruction, replacement);
                        method.instructions.remove(instruction);
                        changedInMethod++;
                        changedInClass++;
                        totalChanged++;
                    }
                }
            }
            if (changedInClass > 0) {
                pool.markDirty(owner.name);
                log("Rewrote {} arithmetic operations in {}", changedInClass, owner.name);
            }
        }
        return totalChanged;
    }

    private List<AbstractInsnNode> candidates(MethodNode method, Set<String> operations) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (operationEnabled(instruction.getOpcode(), operations)) {
                result.add(instruction);
            }
        }
        return result;
    }

    private boolean operationEnabled(int opcode, Set<String> operations) {
        return switch (opcode) {
            case Opcodes.IADD, Opcodes.LADD -> operations.contains("add");
            case Opcodes.ISUB, Opcodes.LSUB -> operations.contains("sub");
            case Opcodes.IAND, Opcodes.LAND -> operations.contains("and");
            case Opcodes.IOR, Opcodes.LOR -> operations.contains("or");
            case Opcodes.IXOR, Opcodes.LXOR -> operations.contains("xor");
            case Opcodes.INEG, Opcodes.LNEG -> operations.contains("neg");
            default -> false;
        };
    }

    private InsnList rewrite(int opcode, LocalPair locals, Random random) {
        boolean wide = isLongOpcode(opcode);
        return switch (opcode) {
            case Opcodes.IADD, Opcodes.LADD -> rewriteAdd(locals, wide, random.nextInt(2));
            case Opcodes.ISUB, Opcodes.LSUB -> rewriteSubtract(locals, wide, random.nextInt(2));
            case Opcodes.IAND, Opcodes.LAND -> rewriteAnd(locals, wide, random.nextInt(2));
            case Opcodes.IOR, Opcodes.LOR -> rewriteOr(locals, wide, random.nextInt(2));
            case Opcodes.IXOR, Opcodes.LXOR -> rewriteXor(locals, wide, random.nextInt(2));
            case Opcodes.INEG, Opcodes.LNEG -> rewriteNegate(locals.left(), wide);
            default -> null;
        };
    }

    private InsnList rewriteAdd(LocalPair locals, boolean wide, int variant) {
        InsnList list = storeBinary(locals, wide);
        if (variant == 0) {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LXOR : Opcodes.IXOR));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            pushOne(list, wide);
            list.add(new InsnNode(wide ? Opcodes.LSHL : Opcodes.ISHL));
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
        } else {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LOR : Opcodes.IOR));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
        }
        return list;
    }

    private InsnList rewriteSubtract(LocalPair locals, boolean wide, int variant) {
        InsnList list = storeBinary(locals, wide);
        if (variant == 0) {
            load(list, locals.left(), wide);
            loadNegated(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LXOR : Opcodes.IXOR));
            load(list, locals.left(), wide);
            loadNegated(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            pushOne(list, wide);
            list.add(new InsnNode(wide ? Opcodes.LSHL : Opcodes.ISHL));
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
        } else {
            load(list, locals.left(), wide);
            loadNegated(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LOR : Opcodes.IOR));
            load(list, locals.left(), wide);
            loadNegated(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
        }
        return list;
    }

    private InsnList rewriteAnd(LocalPair locals, boolean wide, int variant) {
        InsnList list = storeBinary(locals, wide);
        if (variant == 0) {
            loadNot(list, locals.left(), wide);
            loadNot(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LOR : Opcodes.IOR));
            appendNot(list, wide);
        } else {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LOR : Opcodes.IOR));
            list.add(new InsnNode(wide ? Opcodes.LSUB : Opcodes.ISUB));
        }
        return list;
    }

    private InsnList rewriteOr(LocalPair locals, boolean wide, int variant) {
        InsnList list = storeBinary(locals, wide);
        if (variant == 0) {
            loadNot(list, locals.left(), wide);
            loadNot(list, locals.right(), wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            appendNot(list, wide);
        } else {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            list.add(new InsnNode(wide ? Opcodes.LSUB : Opcodes.ISUB));
        }
        return list;
    }

    private InsnList rewriteXor(LocalPair locals, boolean wide, int variant) {
        InsnList list = storeBinary(locals, wide);
        if (variant == 0) {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LOR : Opcodes.IOR));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            list.add(new InsnNode(wide ? Opcodes.LSUB : Opcodes.ISUB));
        } else {
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
            loadBoth(list, locals, wide);
            list.add(new InsnNode(wide ? Opcodes.LAND : Opcodes.IAND));
            pushOne(list, wide);
            list.add(new InsnNode(wide ? Opcodes.LSHL : Opcodes.ISHL));
            list.add(new InsnNode(wide ? Opcodes.LSUB : Opcodes.ISUB));
        }
        return list;
    }

    private InsnList rewriteNegate(int local, boolean wide) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, local));
        loadNot(list, local, wide);
        list.add(new InsnNode(wide ? Opcodes.LCONST_1 : Opcodes.ICONST_1));
        list.add(new InsnNode(wide ? Opcodes.LADD : Opcodes.IADD));
        return list;
    }

    private InsnList storeBinary(LocalPair locals, boolean wide) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, locals.right()));
        list.add(new VarInsnNode(wide ? Opcodes.LSTORE : Opcodes.ISTORE, locals.left()));
        return list;
    }

    private void loadBoth(InsnList list, LocalPair locals, boolean wide) {
        load(list, locals.left(), wide);
        load(list, locals.right(), wide);
    }

    private void load(InsnList list, int local, boolean wide) {
        list.add(new VarInsnNode(wide ? Opcodes.LLOAD : Opcodes.ILOAD, local));
    }

    private void loadNegated(InsnList list, int local, boolean wide) {
        load(list, local, wide);
        list.add(new InsnNode(wide ? Opcodes.LNEG : Opcodes.INEG));
    }

    private void loadNot(InsnList list, int local, boolean wide) {
        load(list, local, wide);
        appendNot(list, wide);
    }

    private void appendNot(InsnList list, boolean wide) {
        if (wide) {
            list.add(new org.objectweb.asm.tree.LdcInsnNode(-1L));
        } else {
            list.add(new InsnNode(Opcodes.ICONST_M1));
        }
        list.add(new InsnNode(wide ? Opcodes.LXOR : Opcodes.IXOR));
    }

    private void pushOne(InsnList list, boolean wide) {
        // Shift counts are always int, including LSHL.
        list.add(new InsnNode(Opcodes.ICONST_1));
    }

    private boolean isLongOpcode(int opcode) {
        return opcode == Opcodes.LADD
                || opcode == Opcodes.LSUB
                || opcode == Opcodes.LAND
                || opcode == Opcodes.LOR
                || opcode == Opcodes.LXOR
                || opcode == Opcodes.LNEG;
    }

    private Set<String> operations(TransformerConfig config) {
        String configured = config.getOption("operations", "add,sub,and,or,xor,neg");
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,;\\s]+")) {
            if (!value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private int intOption(TransformerConfig config, String key, int fallback, int minimum, int maximum) {
        Object value = config.getOptions().get(key);
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private long longOption(TransformerConfig config, String key, long fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private record LocalPair(int left, int right) {
    }
}
