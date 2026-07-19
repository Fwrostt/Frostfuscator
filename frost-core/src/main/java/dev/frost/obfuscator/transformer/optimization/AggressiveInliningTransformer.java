package dev.frost.obfuscator.transformer.optimization;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Conservatively inlines tiny, private, static, no-argument, straight-line methods.
 * This deliberately refuses control flow, local variables, handlers, and recursion.
 */
public final class AggressiveInliningTransformer extends Transformer {
    @Override
    public String getName() {
        return "aggressive-inlining";
    }

    @Override
    public String getCategory() {
        return "Optimization";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int maxInstructions = intOption(context, "max-instructions", 12, 1, 64);
        boolean removeInlined = booleanOption(context, "remove-inlined-methods", true);
        int calls = 0;
        int removed = 0;

        for (ClassNode node : context.pool().getClasses()) {
            if (!shouldProcess(node.name, context.config(), context.pool().getGlobalExclusions(),
                    context.pool().getGlobalInclusions())) continue;
            Map<String, MethodNode> candidates = new HashMap<>();
            for (MethodNode method : node.methods) {
                if (eligible(method, maxInstructions)) {
                    candidates.put(method.name + method.desc, method);
                }
            }
            Set<MethodNode> used = new HashSet<>();
            for (MethodNode caller : node.methods) {
                if (caller.instructions == null || candidates.containsValue(caller)) continue;
                for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(node.name)) {
                        MethodNode callee = candidates.get(call.name + call.desc);
                        if (callee != null && !containsCall(callee, node.name, callee.name, callee.desc)) {
                            caller.instructions.insertBefore(call, cloneBody(callee));
                            caller.instructions.remove(call);
                            used.add(callee);
                            calls++;
                        }
                    }
                    insn = next;
                }
            }
            if (!used.isEmpty()) {
                if (removeInlined) {
                    for (MethodNode method : used) {
                        if (!hasCallSite(node, method)) {
                            node.methods.remove(method);
                            removed++;
                        }
                    }
                }
                context.pool().markDirty(node.name);
            }
        }
        context.stats().add("inlinedCalls", calls);
        context.stats().add("inlinedMethodsRemoved", removed);
        log("Inlined {} call sites and removed {} fully inlined methods", calls, removed);
    }

    private boolean eligible(MethodNode method, int maximum) {
        if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0
                || method.name.startsWith("<")
                || !method.desc.startsWith("()")
                || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()
                || method.instructions == null) return false;
        int meaningful = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
            meaningful++;
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode || insn instanceof VarInsnNode
                    || insn instanceof IincInsnNode || insn.getOpcode() == Opcodes.ATHROW) return false;
        }
        AbstractInsnNode last = lastMeaningful(method.instructions);
        return meaningful > 1 && meaningful <= maximum && last != null
                && last.getOpcode() >= Opcodes.IRETURN && last.getOpcode() <= Opcodes.RETURN;
    }

    private InsnList cloneBody(MethodNode method) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label) labels.put(label, new LabelNode());
        }
        InsnList result = new InsnList();
        AbstractInsnNode last = lastMeaningful(method.instructions);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn == last || insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
            result.add(insn.clone(labels));
        }
        return result;
    }

    private AbstractInsnNode lastMeaningful(InsnList instructions) {
        for (AbstractInsnNode insn = instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (!(insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)) return insn;
        }
        return null;
    }

    private boolean containsCall(MethodNode method, String owner, String name, String desc) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) return true;
        }
        return false;
    }

    private boolean hasCallSite(ClassNode node, MethodNode target) {
        for (MethodNode method : node.methods) {
            if (method == target || method.instructions == null) continue;
            if (containsCall(method, node.name, target.name, target.desc)) return true;
        }
        return false;
    }

    private int intOption(Context context, String key, int fallback, int min, int max) {
        try {
            Object value = context.config().getOptions().get(key);
            int result = value == null ? fallback : Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, result));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanOption(Context context, String key, boolean fallback) {
        Object value = context.config().getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
