package dev.frost.ir.bytecode;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.core.SourcePosition;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.Operation;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;

/**
 * Exact block/edge importer with preserved ASM payloads. Stack operations intentionally remain
 * opaque until the typed SSA lifter replaces this capability level.
 */
public final class BytecodeCfgImporter {
    private final IrContext context;

    public BytecodeCfgImporter(IrContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public BytecodeImportResult importMethod(String owner, MethodNode source) {
        return importMethod(owner, source, BytecodeImportOptions.defaults());
    }

    public BytecodeImportResult importMethod(String owner, MethodNode source, BytecodeImportOptions options) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        MethodSignature signature = new MethodSignature(owner, source.name, JvmTypeAdapter.methodType(source.desc),
                source.access, source.signature, source.exceptions == null ? List.of() : source.exceptions);
        IrMethod method = new IrMethod(context, signature);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<AbstractInsnNode, BasicBlock> nodeBlocks = new IdentityHashMap<>();
        Map<AbstractInsnNode, IrInstruction> nodeInstructions = new IdentityHashMap<>();
        Map<IrInstruction, AbstractInsnNode> instructionNodes = new IdentityHashMap<>();
        MethodNode snapshot = AsmMethodCloner.clone(source);

        try (IrMethod.Mutation ignored = method.beginMutation("asm-cfg-import")) {
            addParameters(method, owner, source);
            AbstractInsnNode[] nodes = source.instructions == null ? new AbstractInsnNode[0] : source.instructions.toArray();
            if (nodes.length > 0) buildBody(method, source, nodes, options, nodeBlocks,
                    nodeInstructions, instructionNodes, diagnostics);
        }

        if (options.validateResult()) {
            diagnostics.addAll(new IrValidator().validate(method, ValidationProfile.STRICT).diagnostics());
        }
        Set<ImportCapability> capabilities = EnumSet.of(ImportCapability.CONTROL_FLOW,
                ImportCapability.EXCEPTIONAL_CONTROL_FLOW, ImportCapability.METHOD_PARAMETERS,
                ImportCapability.BIDIRECTIONAL_SOURCE_MAP, ImportCapability.PRESERVED_ASM_SNAPSHOT);
        return new BytecodeImportResult(method, new AsmSourceMap(nodeBlocks, nodeInstructions, instructionNodes),
                snapshot, method.revision(), capabilities, diagnostics, null);
    }

    private void addParameters(IrMethod method, String owner, MethodNode source) {
        if ((source.access & Opcodes.ACC_STATIC) == 0) {
            method.addParameter("this", source.name.equals("<init>") ? SpecialType.UNINITIALIZED_THIS
                    : new ReferenceType(owner, Nullability.NON_NULL));
        }
        Type[] arguments = Type.getArgumentTypes(source.desc);
        for (int index = 0; index < arguments.length; index++) {
            method.addParameter("arg" + index, JvmTypeAdapter.fromAsm(arguments[index]));
        }
    }

    private void buildBody(IrMethod method, MethodNode source, AbstractInsnNode[] nodes,
                           BytecodeImportOptions options, Map<AbstractInsnNode, BasicBlock> nodeBlocks,
                           Map<AbstractInsnNode, IrInstruction> nodeInstructions,
                           Map<IrInstruction, AbstractInsnNode> instructionNodes,
                           List<Diagnostic> diagnostics) {
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        for (int index = 0; index < nodes.length; index++) indices.put(nodes[index], index);
        int first = nextExecutable(nodes, 0);
        if (first < 0) return;

        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(first);
        for (int index = 0; index < nodes.length; index++) {
            AbstractInsnNode node = nodes[index];
            if (node instanceof JumpInsnNode jump) {
                addTargetLeader(leaders, nodes, indices.get(jump.label));
                addTargetLeader(leaders, nodes, index + 1);
            } else if (node instanceof TableSwitchInsnNode table) {
                addTargetLeader(leaders, nodes, indices.get(table.dflt));
                table.labels.forEach(label -> addTargetLeader(leaders, nodes, indices.get(label)));
                addTargetLeader(leaders, nodes, index + 1);
            } else if (node instanceof LookupSwitchInsnNode lookup) {
                addTargetLeader(leaders, nodes, indices.get(lookup.dflt));
                lookup.labels.forEach(label -> addTargetLeader(leaders, nodes, indices.get(label)));
                addTargetLeader(leaders, nodes, index + 1);
            } else if (isExit(node.getOpcode())) {
                addTargetLeader(leaders, nodes, index + 1);
            }
        }
        for (TryCatchBlockNode region : source.tryCatchBlocks) {
            addTargetLeader(leaders, nodes, indices.get(region.start));
            addTargetLeader(leaders, nodes, indices.get(region.end));
            addTargetLeader(leaders, nodes, indices.get(region.handler));
            if (options.splitPotentiallyThrowingInstructions()) {
                int start = indices.get(region.start);
                int end = indices.get(region.end);
                for (int index = start; index < end; index++) {
                    if (nodes[index].getOpcode() >= 0 && canThrow(nodes[index])) {
                        leaders.add(index);
                        addTargetLeader(leaders, nodes, index + 1);
                    }
                }
            }
        }

        List<Integer> starts = new ArrayList<>(leaders);
        Map<Integer, BasicBlock> blockAtInstruction = new LinkedHashMap<>();
        List<BlockRange> ranges = new ArrayList<>();
        for (int blockIndex = 0; blockIndex < starts.size(); blockIndex++) {
            int start = starts.get(blockIndex);
            int end = blockIndex + 1 < starts.size() ? starts.get(blockIndex + 1) : nodes.length;
            BasicBlock block = method.createBlock("b" + blockIndex);
            block.metadata().put(AsmMetadataKeys.BLOCK_START, (long) start);
            block.metadata().put(AsmMetadataKeys.BLOCK_END, (long) end);
            ranges.add(new BlockRange(block, start, end));
            for (int index = start; index < end; index++) if (nodes[index].getOpcode() >= 0) blockAtInstruction.put(index, block);
        }

        for (int index = 0; index < nodes.length; index++) {
            BasicBlock block = blockForIndex(nodes, blockAtInstruction, index);
            if (block != null) nodeBlocks.put(nodes[index], block);
        }
        for (BlockRange range : ranges) {
            List<Integer> executable = new ArrayList<>();
            for (int index = range.start; index < range.end; index++) if (nodes[index].getOpcode() >= 0) executable.add(index);
            for (int position = 0; position < executable.size(); position++) {
                int index = executable.get(position);
                AbstractInsnNode node = nodes[index];
                boolean terminal = position == executable.size() - 1 && isControlTransfer(node);
                var code = terminal ? CoreOps.OPAQUE_TERMINATOR
                        : canThrow(node) ? CoreOps.OPAQUE_BYTECODE : CoreOps.OPAQUE_PURE_BYTECODE;
                Map<String, IrAttribute> attributes = new LinkedHashMap<>();
                attributes.put("opcode", IrAttribute.of((long) node.getOpcode()));
                attributes.put("mnemonic", IrAttribute.of(opcodeName(node.getOpcode())));
                attributes.put("node_kind", IrAttribute.of((long) node.getType()));
                String operand = operandText(node, indices);
                if (!operand.isEmpty()) attributes.put("operand", IrAttribute.of(operand));
                IrInstruction instruction = method.createInstruction(new Operation(code, attributes), List.of(), List.of());
                instruction.metadata().put(AsmMetadataKeys.INSTRUCTION_INDEX, (long) index);
                instruction.metadata().put(AsmMetadataKeys.OPCODE, (long) node.getOpcode());
                instruction.metadata().put(AsmMetadataKeys.OPCODE_NAME, opcodeName(node.getOpcode()));
                range.block.append(instruction);
                nodeInstructions.put(node, instruction);
                instructionNodes.put(instruction, node);
            }
            if (range.block.terminator().isEmpty()) {
                range.block.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
            }
        }

        connectNormalEdges(method, nodes, indices, blockAtInstruction, ranges, diagnostics);
        connectExceptionEdges(method, source, nodes, indices, blockAtInstruction, ranges, diagnostics);
    }

    private void connectNormalEdges(IrMethod method, AbstractInsnNode[] nodes, Map<AbstractInsnNode, Integer> indices,
                                    Map<Integer, BasicBlock> blockAtInstruction, List<BlockRange> ranges,
                                    List<Diagnostic> diagnostics) {
        for (BlockRange range : ranges) {
            int last = previousExecutable(nodes, range.end - 1);
            if (last < range.start) continue;
            AbstractInsnNode node = nodes[last];
            if (node instanceof JumpInsnNode jump) {
                BasicBlock target = blockAtInstruction.get(nextExecutable(nodes, indices.get(jump.label)));
                if (target == null) { importError(diagnostics, "asm.missing-jump-target", "Jump target has no executable block", last); continue; }
                if (node.getOpcode() == Opcodes.GOTO || node.getOpcode() == Opcodes.JSR) {
                    method.connect(range.block, target, EdgeKind.NORMAL, node.getOpcode() == Opcodes.JSR ? "jsr" : "goto", null, 0);
                } else {
                    method.connect(range.block, target, EdgeKind.TRUE, opcodeName(node.getOpcode()), null, 0);
                    BasicBlock fallthrough = blockAtInstruction.get(nextExecutable(nodes, last + 1));
                    if (fallthrough != null) method.connect(range.block, fallthrough, EdgeKind.FALSE, "fallthrough", null, 0);
                }
            } else if (node instanceof TableSwitchInsnNode table) {
                connectSwitch(method, range.block, nodes, indices, blockAtInstruction, table.dflt, table.labels,
                        java.util.stream.IntStream.rangeClosed(table.min, table.max).boxed().map(String::valueOf).toList());
            } else if (node instanceof LookupSwitchInsnNode lookup) {
                connectSwitch(method, range.block, nodes, indices, blockAtInstruction, lookup.dflt, lookup.labels,
                        lookup.keys.stream().map(String::valueOf).toList());
            } else if (node.getOpcode() == Opcodes.RET) {
                importWarning(diagnostics, "asm.legacy-ret", "RET has dynamic successors; JSR inlining is required for full SSA", last);
            } else if (!isExit(node.getOpcode())) {
                BasicBlock target = blockAtInstruction.get(nextExecutable(nodes, last + 1));
                if (target != null) method.connect(range.block, target, EdgeKind.FALLTHROUGH, "", null, 0);
            }
        }
    }

    private void connectSwitch(IrMethod method, BasicBlock source, AbstractInsnNode[] nodes,
                               Map<AbstractInsnNode, Integer> indices, Map<Integer, BasicBlock> blocks,
                               LabelNode defaultLabel, List<LabelNode> labels, List<String> keys) {
        BasicBlock defaultTarget = blocks.get(nextExecutable(nodes, indices.get(defaultLabel)));
        if (defaultTarget != null) method.connect(source, defaultTarget, EdgeKind.SWITCH_DEFAULT, "default", null, 0);
        for (int index = 0; index < labels.size(); index++) {
            BasicBlock target = blocks.get(nextExecutable(nodes, indices.get(labels.get(index))));
            if (target != null) method.connect(source, target, EdgeKind.SWITCH_CASE, keys.get(index), null, 0);
        }
    }

    private void connectExceptionEdges(IrMethod method, MethodNode source, AbstractInsnNode[] nodes,
                                       Map<AbstractInsnNode, Integer> indices, Map<Integer, BasicBlock> blockAtInstruction,
                                       List<BlockRange> ranges, List<Diagnostic> diagnostics) {
        for (int priority = 0; priority < source.tryCatchBlocks.size(); priority++) {
            TryCatchBlockNode region = source.tryCatchBlocks.get(priority);
            int start = indices.get(region.start);
            int end = indices.get(region.end);
            BasicBlock handler = blockAtInstruction.get(nextExecutable(nodes, indices.get(region.handler)));
            if (handler == null) {
                importError(diagnostics, "asm.missing-handler", "Exception handler has no executable block", indices.get(region.handler));
                continue;
            }
            ReferenceType catchType = region.type == null ? null : new ReferenceType(region.type, Nullability.NON_NULL);
            Set<BasicBlock> protectedBlocks = new LinkedHashSet<>();
            for (BlockRange range : ranges) {
                boolean throwing = false;
                for (int index = Math.max(start, range.start); index < Math.min(end, range.end); index++) {
                    if (nodes[index].getOpcode() >= 0 && canThrow(nodes[index])) { throwing = true; break; }
                }
                if (!throwing) continue;
                protectedBlocks.add(range.block);
                EdgeKind kind = catchType == null ? EdgeKind.FINALLY : EdgeKind.EXCEPTION;
                method.connect(range.block, handler, kind, catchType == null ? "finally" : catchType.internalName(), catchType, priority);
            }
            if (!protectedBlocks.isEmpty()) method.addExceptionRegion(protectedBlocks, handler, catchType, priority);
        }
    }

    private BasicBlock blockForIndex(AbstractInsnNode[] nodes, Map<Integer, BasicBlock> blocks, int index) {
        int next = nextExecutable(nodes, index);
        if (next >= 0 && blocks.containsKey(next)) return blocks.get(next);
        int previous = previousExecutable(nodes, index);
        return blocks.get(previous);
    }

    private void addTargetLeader(Set<Integer> leaders, AbstractInsnNode[] nodes, int rawIndex) {
        int target = nextExecutable(nodes, rawIndex);
        if (target >= 0) leaders.add(target);
    }

    private int nextExecutable(AbstractInsnNode[] nodes, int start) {
        for (int index = Math.max(0, start); index < nodes.length; index++) if (nodes[index].getOpcode() >= 0) return index;
        return -1;
    }

    private int previousExecutable(AbstractInsnNode[] nodes, int start) {
        for (int index = Math.min(start, nodes.length - 1); index >= 0; index--) if (nodes[index].getOpcode() >= 0) return index;
        return -1;
    }

    private boolean isControlTransfer(AbstractInsnNode node) {
        return node instanceof JumpInsnNode || node instanceof TableSwitchInsnNode || node instanceof LookupSwitchInsnNode
                || isExit(node.getOpcode());
    }

    private boolean isExit(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW || opcode == Opcodes.RET;
    }

    private boolean canThrow(AbstractInsnNode node) {
        int opcode = node.getOpcode();
        if (opcode < 0) return false;
        if (node instanceof FieldInsnNode || node instanceof MethodInsnNode || node instanceof InvokeDynamicInsnNode
                || node instanceof MultiANewArrayInsnNode) return true;
        if (node instanceof TypeInsnNode) return true;
        if (node instanceof LdcInsnNode ldc) {
            return ldc.cst instanceof ConstantDynamic || ldc.cst instanceof Type || ldc.cst instanceof Handle;
        }
        return switch (opcode) {
            case Opcodes.IDIV, Opcodes.LDIV, Opcodes.IREM, Opcodes.LREM,
                    Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                    Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                    Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                    Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                    Opcodes.ARRAYLENGTH, Opcodes.NEWARRAY, Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                    Opcodes.ATHROW -> true;
            default -> false;
        };
    }

    private String operandText(AbstractInsnNode node, Map<AbstractInsnNode, Integer> indices) {
        if (node instanceof IntInsnNode value) return Integer.toString(value.operand);
        if (node instanceof VarInsnNode value) return "local " + value.var;
        if (node instanceof TypeInsnNode value) return value.desc;
        if (node instanceof FieldInsnNode value) return value.owner + "." + value.name + ":" + value.desc;
        if (node instanceof MethodInsnNode value) return value.owner + "." + value.name + value.desc + (value.itf ? " interface" : "");
        if (node instanceof InvokeDynamicInsnNode value) return value.name + value.desc + " bootstrap=" + value.bsm + " args=" + List.of(value.bsmArgs);
        if (node instanceof JumpInsnNode value) return "target " + indices.get(value.label);
        if (node instanceof LdcInsnNode value) return constantText(value.cst);
        if (node instanceof IincInsnNode value) return "local " + value.var + " by " + value.incr;
        if (node instanceof TableSwitchInsnNode value) return value.min + ".." + value.max + " default=" + indices.get(value.dflt);
        if (node instanceof LookupSwitchInsnNode value) return "keys=" + value.keys + " default=" + indices.get(value.dflt);
        if (node instanceof MultiANewArrayInsnNode value) return value.desc + " dimensions=" + value.dims;
        if (node instanceof InsnNode) return "";
        return node.getClass().getSimpleName();
    }

    private String constantText(Object value) {
        if (value instanceof String string) return "string:" + string;
        if (value instanceof Type type) return "type:" + type.getDescriptor();
        if (value instanceof Handle handle) return "handle:" + handle;
        if (value instanceof ConstantDynamic dynamic) return "condy:" + dynamic.getName() + dynamic.getDescriptor()
                + " bootstrap=" + dynamic.getBootstrapMethod();
        return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
    }

    private String opcodeName(int opcode) {
        return opcode >= 0 && opcode < Printer.OPCODES.length && Printer.OPCODES[opcode] != null
                ? Printer.OPCODES[opcode] : "OP_" + opcode;
    }

    private void importError(List<Diagnostic> diagnostics, String code, String message, int index) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, code, message, null,
                new SourcePosition(index, -1, -1), Map.of()));
    }

    private void importWarning(List<Diagnostic> diagnostics, String code, String message, int index) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, code, message, null,
                new SourcePosition(index, -1, -1), Map.of()));
    }

    private record BlockRange(BasicBlock block, int start, int end) {}
}
