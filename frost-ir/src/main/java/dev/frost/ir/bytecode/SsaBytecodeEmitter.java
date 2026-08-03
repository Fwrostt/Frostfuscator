package dev.frost.ir.bytecode;

import dev.frost.ir.analysis.EdgePolicy;
import dev.frost.ir.analysis.Liveness;
import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.core.SourcePosition;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.ExceptionRegion;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.MethodType;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Verification-first SSA destruction and JVM bytecode emission. */
final class SsaBytecodeEmitter {
    private final IrMethod method;
    private final BytecodeImportResult origin;
    private final MethodNode output;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<Value, Integer> locals = new IdentityHashMap<>();
    private final Map<BasicBlock, LabelNode> labels = new IdentityHashMap<>();
    private final List<BasicBlock> blockOrder = new ArrayList<>();
    private final List<LocalVariableRange> originalLocalVariables;
    private final List<LocalAnnotationRange> originalVisibleLocalAnnotations;
    private final List<LocalAnnotationRange> originalInvisibleLocalAnnotations;
    private final List<TryCatchBlockNode> originalTryCatchBlocks;
    private final NavigableMap<Integer, LabelNode> sourceAnchors = new TreeMap<>();
    private long emittedLine = -1;
    private int nextLocal;

    SsaBytecodeEmitter(IrMethod method, BytecodeImportResult origin) {
        this.method = Objects.requireNonNull(method, "method");
        this.origin = Objects.requireNonNull(origin, "origin");
        output = AsmMethodCloner.clone(origin.preservedSnapshot());
        originalLocalVariables = captureLocalVariables(output);
        originalVisibleLocalAnnotations = captureLocalAnnotations(output.visibleLocalVariableAnnotations, output);
        originalInvisibleLocalAnnotations = captureLocalAnnotations(output.invisibleLocalVariableAnnotations, output);
        originalTryCatchBlocks = output.tryCatchBlocks == null ? List.of() : List.copyOf(output.tryCatchBlocks);
        output.instructions = new InsnList();
        output.tryCatchBlocks = new ArrayList<>();
        output.localVariables = null;
        output.visibleLocalVariableAnnotations = null;
        output.invisibleLocalVariableAnnotations = null;
    }

    BytecodeLoweringResult emit() {
        try {
            establishBlockOrder();
            allocateLocals();
            blockOrder.forEach(block -> labels.put(block, new LabelNode()));
            LabelNode methodEnd = new LabelNode();
            for (BasicBlock block : blockOrder) emitBlock(block);
            output.instructions.add(methodEnd);
            emitLocalVariables(methodEnd);
            output.visibleLocalVariableAnnotations = emitLocalAnnotations(originalVisibleLocalAnnotations, methodEnd);
            output.invisibleLocalVariableAnnotations = emitLocalAnnotations(originalInvisibleLocalAnnotations, methodEnd);
            emitExceptionTable(methodEnd);
            removeUnreferencedTerminalLabel(methodEnd);
            output.maxLocals = nextLocal;
            output.maxStack = Math.max(16, maximumEdgeCopies() * 2 + 8);
            verify();
            return new BytecodeLoweringResult(output, diagnostics);
        } catch (EmissionException | IllegalArgumentException | IllegalStateException exception) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "lowering.emit", exception.getMessage(),
                    null, SourcePosition.UNKNOWN, Map.of("exception", exception.getClass().getName())));
            return new BytecodeLoweringResult(null, diagnostics);
        }
    }

    private void establishBlockOrder() {
        BasicBlock entry = method.entryBlock().orElseThrow(() -> new EmissionException("method has no entry block"));
        blockOrder.add(entry);
        method.blocks().stream().filter(block -> block != entry).forEach(blockOrder::add);
    }

    private void removeUnreferencedTerminalLabel(LabelNode methodEnd) {
        for (AbstractInsnNode instruction : output.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == methodEnd) return;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == methodEnd || table.labels.contains(methodEnd))) return;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == methodEnd || lookup.labels.contains(methodEnd))) return;
            if (instruction instanceof LineNumberNode line && line.start == methodEnd) return;
        }
        if (output.tryCatchBlocks != null && output.tryCatchBlocks.stream().anyMatch(range ->
                range.start == methodEnd || range.end == methodEnd || range.handler == methodEnd)) return;
        if (output.localVariables != null && output.localVariables.stream().anyMatch(local ->
                local.start == methodEnd || local.end == methodEnd)) return;
        if (referencesLocalAnnotation(output.visibleLocalVariableAnnotations, methodEnd)
                || referencesLocalAnnotation(output.invisibleLocalVariableAnnotations, methodEnd)) return;
        output.instructions.remove(methodEnd);
    }

    private boolean referencesLocalAnnotation(List<LocalVariableAnnotationNode> annotations, LabelNode label) {
        if (annotations == null) return false;
        return annotations.stream().anyMatch(annotation ->
                annotation.start.contains(label) || annotation.end.contains(label));
    }

    private void allocateLocals() {
        int metadataLimit = metadataLocalLimit();
        int slot = 0;
        for (var parameter : method.parameters()) {
            locals.put(parameter.value(), slot);
            slot += Math.max(1, parameter.value().type().slots());
        }
        // Do not inherit the previous physical spill high-water mark.  Every IR lowering assigns
        // fresh slots, so using snapshot.maxLocals as the base made maxLocals grow by roughly the
        // SSA value count on every transformer.  Preserve only slots that bytecode metadata or
        // pinned local phis actually require.
        nextLocal = Math.max(slot, metadataLimit);
        for (BasicBlock block : method.blocks()) {
            for (PhiNode phi : block.phis()) {
                String kind = phi.metadata().get(AsmMetadataKeys.PHI_SLOT_KIND).orElse("");
                if (kind.equals("local")) {
                    int local = Math.toIntExact(phi.metadata().get(AsmMetadataKeys.PHI_SLOT_INDEX)
                            .orElseThrow(() -> new EmissionException("local phi lacks slot index")));
                    locals.put(phi.result(), local);
                    nextLocal = Math.max(nextLocal, local + Math.max(1, phi.result().type().slots()));
                }
            }
        }
        int allocationBase = nextLocal;

        Map<Value, Set<Value>> interference = interferenceGraph();
        List<Value> pending = new ArrayList<>();
        for (BasicBlock block : method.blocks()) {
            for (PhiNode phi : block.phis()) if (!locals.containsKey(phi.result())) pending.add(phi.result());
            for (IrInstruction instruction : block.instructions()) {
                for (Value result : instruction.results()) if (!locals.containsKey(result)) pending.add(result);
            }
        }
        pending.sort(Comparator.comparingInt((Value value) ->
                interference.getOrDefault(value, Set.of()).size()).reversed());
        for (Value value : pending) {
            int width = value.type().slots();
            if (width <= 0) throw new EmissionException(
                    "cannot allocate non-JVM value type " + value.type().displayName());
            int candidate = allocationBase;
            while (!slotAvailable(value, candidate, width, interference)) candidate++;
            locals.put(value, candidate);
            nextLocal = Math.max(nextLocal, candidate + width);
        }
        if (nextLocal > 65535) throw new EmissionException("lowering exceeds the JVM 65535-local limit");
    }

    private int metadataLocalLimit() {
        int limit = 0;
        for (LocalVariableRange local : originalLocalVariables) {
            limit = Math.max(limit, local.index + Type.getType(local.descriptor).getSize());
        }
        for (LocalAnnotationRange annotation : originalVisibleLocalAnnotations) {
            for (LocalRange range : annotation.ranges) limit = Math.max(limit, range.index + 1);
        }
        for (LocalAnnotationRange annotation : originalInvisibleLocalAnnotations) {
            for (LocalRange range : annotation.ranges) limit = Math.max(limit, range.index + 1);
        }
        return limit;
    }

    private Map<Value, Set<Value>> interferenceGraph() {
        Map<Value, Set<Value>> graph = new IdentityHashMap<>();
        Liveness liveness = Liveness.compute(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        for (var parameter : method.parameters()) graph.put(parameter.value(), identitySet());
        for (BasicBlock block : method.blocks()) {
            for (PhiNode phi : block.phis()) graph.putIfAbsent(phi.result(), identitySet());
            for (IrInstruction instruction : block.instructions()) {
                for (Value result : instruction.results()) graph.putIfAbsent(result, identitySet());
            }
        }
        for (BasicBlock block : method.blocks()) {
            addClique(graph, liveness.liveIn(block));
            addClique(graph, liveness.liveOut(block));
            List<Value> outgoingPhiDestinations = block.outgoingEdges().stream()
                    .filter(edge -> !edge.kind().isExceptional())
                    .flatMap(edge -> edge.target().phis().stream())
                    .map(PhiNode::result)
                    .distinct()
                    .toList();
            addClique(graph, outgoingPhiDestinations);
            List<Value> phis = block.phis().stream().map(PhiNode::result).toList();
            addClique(graph, phis);
            for (Value phi : phis) {
                for (Value live : liveness.liveIn(block)) addInterference(graph, phi, live);
            }
            for (IrInstruction instruction : block.instructions()) {
                addClique(graph, instruction.operands());
                addClique(graph, instruction.results());
                for (Value result : instruction.results()) {
                    for (Value live : liveness.liveAfter(instruction)) addInterference(graph, result, live);
                }
            }
        }
        return graph;
    }

    private Set<Value> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private void addClique(Map<Value, Set<Value>> graph, Iterable<Value> values) {
        List<Value> members = new ArrayList<>();
        values.forEach(members::add);
        for (int left = 0; left < members.size(); left++) {
            for (int right = left + 1; right < members.size(); right++) {
                addInterference(graph, members.get(left), members.get(right));
            }
        }
    }

    private void addInterference(Map<Value, Set<Value>> graph, Value left, Value right) {
        if (left == right) return;
        graph.computeIfAbsent(left, ignored -> identitySet()).add(right);
        graph.computeIfAbsent(right, ignored -> identitySet()).add(left);
    }

    private boolean slotAvailable(Value value, int candidate, int width,
                                  Map<Value, Set<Value>> interference) {
        for (Value neighbor : interference.getOrDefault(value, Set.of())) {
            Integer neighborSlot = locals.get(neighbor);
            if (neighborSlot == null) continue;
            int neighborWidth = Math.max(1, neighbor.type().slots());
            if (candidate < neighborSlot + neighborWidth && neighborSlot < candidate + width) return false;
        }
        return true;
    }

    private void emitBlock(BasicBlock block) {
        output.instructions.add(labels.get(block));
        emitHandlerEntry(block);
        List<IrInstruction> instructions = block.instructions();
        for (int index = 0; index < instructions.size(); index++) {
            IrInstruction instruction = instructions.get(index);
            emitSourceAnchor(instruction);
            AbstractInsnNode boundary = output.instructions.getLast();
            if (instruction.isTerminator()) {
                emitEdgeCopies(block);
                emitTerminator(block, instruction);
            } else {
                emitInstruction(instruction);
            }
            emitInstructionTypeAnnotations(instruction, boundary);
        }
    }

    private void emitSourceAnchor(IrInstruction instruction) {
        long sourceIndex = instruction.metadata().get(AsmMetadataKeys.INSTRUCTION_INDEX).orElse(-1L);
        LabelNode anchor = null;
        if (sourceIndex >= 0 && sourceIndex <= Integer.MAX_VALUE) {
            int index = (int) sourceIndex;
            anchor = sourceAnchors.get(index);
            if (anchor == null) {
                anchor = new LabelNode();
                sourceAnchors.put(index, anchor);
                output.instructions.add(anchor);
            }
        }
        long line = instruction.metadata().get(AsmMetadataKeys.LINE_NUMBER).orElse(-1L);
        if (line >= 0 && line <= Integer.MAX_VALUE && line != emittedLine) {
            if (anchor == null) {
                anchor = new LabelNode();
                output.instructions.add(anchor);
            }
            output.instructions.add(new LineNumberNode((int) line, anchor));
            emittedLine = line;
        }
    }

    private List<LocalVariableRange> captureLocalVariables(MethodNode method) {
        if (method.localVariables == null || method.localVariables.isEmpty()) return List.of();
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        AbstractInsnNode[] nodes = method.instructions.toArray();
        for (int index = 0; index < nodes.length; index++) indices.put(nodes[index], index);
        List<LocalVariableRange> result = new ArrayList<>();
        for (LocalVariableNode local : method.localVariables) {
            Integer start = indices.get(local.start), end = indices.get(local.end);
            if (start != null && end != null && start < end) {
                result.add(new LocalVariableRange(local.name, local.desc, local.signature, start, end, local.index));
            }
        }
        return List.copyOf(result);
    }

    private void emitLocalVariables(LabelNode methodEnd) {
        if (originalLocalVariables.isEmpty()) {
            output.localVariables = null;
            return;
        }
        output.localVariables = new ArrayList<>();
        for (LocalVariableRange local : originalLocalVariables) {
            LabelNode start = anchorAtOrAfter(local.start, methodEnd);
            LabelNode end = anchorAtOrAfter(local.end, methodEnd);
            if (start != end) output.localVariables.add(new LocalVariableNode(
                    local.name, local.descriptor, local.signature, start, end, local.index));
        }
        if (output.localVariables.isEmpty()) output.localVariables = null;
    }

    private List<LocalAnnotationRange> captureLocalAnnotations(List<LocalVariableAnnotationNode> annotations,
                                                               MethodNode method) {
        if (annotations == null || annotations.isEmpty()) return List.of();
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        AbstractInsnNode[] nodes = method.instructions.toArray();
        for (int index = 0; index < nodes.length; index++) indices.put(nodes[index], index);
        List<LocalAnnotationRange> result = new ArrayList<>();
        for (LocalVariableAnnotationNode annotation : annotations) {
            List<LocalRange> ranges = new ArrayList<>();
            for (int index = 0; index < annotation.start.size(); index++) {
                Integer start = indices.get(annotation.start.get(index)), end = indices.get(annotation.end.get(index));
                if (start != null && end != null && start < end) {
                    ranges.add(new LocalRange(start, end, annotation.index.get(index)));
                }
            }
            if (!ranges.isEmpty()) result.add(new LocalAnnotationRange(annotation, List.copyOf(ranges)));
        }
        return List.copyOf(result);
    }

    private List<LocalVariableAnnotationNode> emitLocalAnnotations(List<LocalAnnotationRange> annotations,
                                                                   LabelNode methodEnd) {
        if (annotations.isEmpty()) return null;
        List<LocalVariableAnnotationNode> result = new ArrayList<>();
        for (LocalAnnotationRange original : annotations) {
            List<LabelNode> starts = new ArrayList<>(), ends = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            for (LocalRange range : original.ranges) {
                LabelNode start = anchorAtOrAfter(range.start, methodEnd);
                LabelNode end = anchorAtOrAfter(range.end, methodEnd);
                if (start == end) continue;
                starts.add(start); ends.add(end); slots.add(range.index);
            }
            if (starts.isEmpty()) continue;
            LocalVariableAnnotationNode copy = new LocalVariableAnnotationNode(original.annotation.typeRef,
                    original.annotation.typePath, starts.toArray(LabelNode[]::new), ends.toArray(LabelNode[]::new),
                    slots.stream().mapToInt(Integer::intValue).toArray(), original.annotation.desc);
            original.annotation.accept(copy);
            result.add(copy);
        }
        return result.isEmpty() ? null : result;
    }

    private void emitInstructionTypeAnnotations(IrInstruction instruction, AbstractInsnNode boundary) {
        AbstractInsnNode original = source(instruction);
        if (original == null || (original.visibleTypeAnnotations == null && original.invisibleTypeAnnotations == null)) return;
        AbstractInsnNode candidate = boundary == null ? output.instructions.getFirst() : boundary.getNext();
        AbstractInsnNode fallback = null;
        while (candidate != null) {
            if (candidate.getOpcode() >= 0) {
                fallback = candidate;
                if (candidate.getOpcode() == original.getOpcode()) break;
            }
            candidate = candidate.getNext();
        }
        if (candidate == null || candidate.getOpcode() != original.getOpcode()) candidate = fallback;
        if (candidate == null) return;
        candidate.visibleTypeAnnotations = cloneTypeAnnotations(original.visibleTypeAnnotations);
        candidate.invisibleTypeAnnotations = cloneTypeAnnotations(original.invisibleTypeAnnotations);
    }

    private List<TypeAnnotationNode> cloneTypeAnnotations(List<TypeAnnotationNode> annotations) {
        if (annotations == null) return null;
        List<TypeAnnotationNode> result = new ArrayList<>(annotations.size());
        for (TypeAnnotationNode annotation : annotations) {
            TypeAnnotationNode copy = new TypeAnnotationNode(annotation.typeRef, annotation.typePath, annotation.desc);
            annotation.accept(copy);
            result.add(copy);
        }
        return result;
    }

    private void copyTryCatchAnnotations(int priority, TryCatchBlockNode target) {
        if (priority < 0 || priority >= originalTryCatchBlocks.size()) return;
        TryCatchBlockNode source = originalTryCatchBlocks.get(priority);
        target.visibleTypeAnnotations = cloneTypeAnnotations(source.visibleTypeAnnotations);
        target.invisibleTypeAnnotations = cloneTypeAnnotations(source.invisibleTypeAnnotations);
    }

    private LabelNode anchorAtOrAfter(int sourceIndex, LabelNode methodEnd) {
        Map.Entry<Integer, LabelNode> entry = sourceAnchors.ceilingEntry(sourceIndex);
        return entry == null ? methodEnd : entry.getValue();
    }

    private void emitHandlerEntry(BasicBlock block) {
        List<PhiNode> stackPhis = block.phis().stream()
                .filter(phi -> phi.metadata().get(AsmMetadataKeys.PHI_SLOT_KIND).orElse("").equals("stack"))
                .sorted(Comparator.comparingLong(phi -> phi.metadata().get(AsmMetadataKeys.PHI_SLOT_INDEX).orElse(0L)))
                .toList();
        boolean handler = block.incomingEdges().stream().anyMatch(edge -> edge.kind().isExceptional());
        if (!handler) return;
        if (stackPhis.size() != 1) throw new EmissionException("exception handler must expose one stack phi");
        store(stackPhis.getFirst().result());
    }

    private void emitEdgeCopies(BasicBlock block) {
        Map<Integer, Copy> copiesByDestination = new LinkedHashMap<>();
        for (ControlEdge edge : block.outgoingEdges()) {
            if (edge.kind().isExceptional()) continue;
            for (PhiNode phi : edge.target().phis()) {
                Value incoming = phi.input(edge).orElseThrow(() -> new EmissionException("phi lacks edge input"));
                Copy copy = new Copy(incoming, phi.result());
                int destination = local(phi.result());
                Copy previous = copiesByDestination.putIfAbsent(destination, copy);
                if (previous != null && previous.source != incoming) {
                    throw new EmissionException("parallel outgoing edges require conflicting values for local " + destination);
                }
            }
        }
        List<Copy> copies = new ArrayList<>(copiesByDestination.values());
        copies.forEach(copy -> load(copy.source));
        Collections.reverse(copies);
        copies.forEach(copy -> store(copy.destination));
    }

    private void emitTerminator(BasicBlock block, IrInstruction instruction) {
        OperationCode code = instruction.operation().code();
        if (code.equals(CoreOps.BRANCH)) {
            ControlEdge edge = onlyNormalEdge(block);
            if (nextBlock(block) != edge.target()) {
                output.instructions.add(new JumpInsnNode(Opcodes.GOTO, labels.get(edge.target())));
            }
        } else if (code.equals(CoreOps.CONDITIONAL_BRANCH)) {
            instruction.operands().forEach(this::load);
            int opcode = opcodeAttribute(instruction, "condition");
            ControlEdge onTrue = edge(block, EdgeKind.TRUE);
            ControlEdge onFalse = edge(block, EdgeKind.FALSE);
            BasicBlock fallthrough = nextBlock(block);
            if (fallthrough == onFalse.target()) {
                output.instructions.add(new JumpInsnNode(opcode, labels.get(onTrue.target())));
            } else if (fallthrough == onTrue.target()) {
                output.instructions.add(new JumpInsnNode(invertConditionalOpcode(opcode),
                        labels.get(onFalse.target())));
            } else {
                output.instructions.add(new JumpInsnNode(opcode, labels.get(onTrue.target())));
                output.instructions.add(new JumpInsnNode(Opcodes.GOTO, labels.get(onFalse.target())));
            }
        } else if (code.equals(CoreOps.SWITCH)) {
            load(instruction.operands().getFirst());
            ControlEdge defaultEdge = edge(block, EdgeKind.SWITCH_DEFAULT);
            List<ControlEdge> cases = block.outgoingEdges().stream().filter(edge -> edge.kind() == EdgeKind.SWITCH_CASE)
                    .sorted(Comparator.comparingInt(edge -> Integer.parseInt(edge.label()))).toList();
            int[] keys = cases.stream().mapToInt(edge -> Integer.parseInt(edge.label())).toArray();
            LabelNode[] targets = cases.stream().map(edge -> labels.get(edge.target())).toArray(LabelNode[]::new);
            output.instructions.add(new LookupSwitchInsnNode(labels.get(defaultEdge.target()), keys, targets));
        } else if (code.equals(CoreOps.RETURN)) {
            if (instruction.operands().isEmpty()) output.instructions.add(new InsnNode(Opcodes.RETURN));
            else {
                Value value = instruction.operands().getFirst();
                load(value);
                output.instructions.add(new InsnNode(returnOpcode(value.type())));
            }
        } else if (code.equals(CoreOps.THROW)) {
            load(instruction.operands().getFirst());
            output.instructions.add(new InsnNode(Opcodes.ATHROW));
        } else if (code.equals(CoreOps.UNREACHABLE)) {
            output.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"));
            output.instructions.add(new InsnNode(Opcodes.DUP));
            output.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false));
            output.instructions.add(new InsnNode(Opcodes.ATHROW));
        } else throw new EmissionException("unsupported terminator " + code.qualifiedName());
    }

    private void emitInstruction(IrInstruction instruction) {
        OperationCode code = instruction.operation().code();
        if (code.equals(CoreOps.NOP)) output.instructions.add(new InsnNode(Opcodes.NOP));
        else if (code.equals(CoreOps.CONSTANT)) emitConstant(instruction);
        else if (code.equals(CoreOps.CONSTANT_DYNAMIC)) emitConstantDynamic(instruction);
        else if (code.equals(CoreOps.COPY)) { load(instruction.operands().getFirst()); store(instruction.result()); }
        else if (code.equals(CoreOps.LOCAL_WRITE)) {
            load(instruction.operands().getFirst());
            int target = Math.toIntExact(longAttribute(instruction, "local"));
            output.instructions.add(new VarInsnNode(storeOpcode(instruction.operands().getFirst().type()), target));
        } else if (code.equals(CoreOps.STACK_PERMUTE)) {
            // Operand-stack permutations are provenance-only after every SSA value has a local home.
        } else if (isBinary(code)) emitBinary(instruction);
        else if (code.equals(CoreOps.NEG)) emitUnaryOpcode(instruction, negOpcode(instruction.result().type()));
        else if (code.equals(CoreOps.CONVERT)) emitUnaryOpcode(instruction,
                conversionOpcode(instruction.operands().getFirst().type(), instruction.result().type()));
        else if (code.equals(CoreOps.COMPARE)) emitCompare(instruction);
        else if (code.equals(CoreOps.ARRAY_LOAD)) emitArrayLoad(instruction);
        else if (code.equals(CoreOps.ARRAY_STORE)) emitArrayStore(instruction);
        else if (code.equals(CoreOps.ARRAY_LENGTH)) emitUnaryOpcode(instruction, Opcodes.ARRAYLENGTH);
        else if (code.equals(CoreOps.FIELD_LOAD) || code.equals(CoreOps.FIELD_STORE)
                || code.equals(CoreOps.STATIC_LOAD) || code.equals(CoreOps.STATIC_STORE)) emitField(instruction);
        else if (code.equals(CoreOps.INVOKE)) emitInvoke(instruction, false);
        else if (code.equals(CoreOps.INITIALIZE)) emitInitialize(instruction);
        else if (code.equals(CoreOps.INVOKE_DYNAMIC)) emitInvokeDynamic(instruction);
        else if (code.equals(CoreOps.NEW_OBJECT)) emitNewObject(instruction);
        else if (code.equals(CoreOps.NEW_ARRAY)) emitNewArray(instruction);
        else if (code.equals(CoreOps.CHECK_CAST)) emitTypeOperation(instruction, Opcodes.CHECKCAST);
        else if (code.equals(CoreOps.INSTANCE_OF)) emitTypeOperation(instruction, Opcodes.INSTANCEOF);
        else if (code.equals(CoreOps.MONITOR_ENTER)) emitVoidUnary(instruction, Opcodes.MONITORENTER);
        else if (code.equals(CoreOps.MONITOR_EXIT)) emitVoidUnary(instruction, Opcodes.MONITOREXIT);
        else throw new EmissionException("unsupported operation " + code.qualifiedName());
    }

    private void emitConstant(IrInstruction instruction) {
        IrType type = instruction.result().type();
        IrAttribute value = instruction.operation().attributes().get("value");
        if (type == SpecialType.NULL || value instanceof IrAttribute.StringValue string && string.value().equals("null")) {
            output.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        } else if (value instanceof IrAttribute.LongValue number) {
            Object constant;
            if (type == PrimitiveType.LONG) constant = Long.valueOf(number.value());
            else if (type == PrimitiveType.FLOAT) constant = Float.valueOf((float) number.value());
            else if (type == PrimitiveType.DOUBLE) constant = Double.valueOf(number.value());
            else constant = Integer.valueOf((int) number.value());
            output.instructions.add(new LdcInsnNode(constant));
        } else if (value instanceof IrAttribute.DoubleValue number) {
            Object constant;
            if (type == PrimitiveType.FLOAT) constant = Float.valueOf((float) number.value());
            else constant = Double.valueOf(number.value());
            output.instructions.add(new LdcInsnNode(constant));
        } else if (value instanceof IrAttribute.StringValue string) {
            output.instructions.add(new LdcInsnNode(string.value()));
        } else {
            AbstractInsnNode source = source(instruction);
            if (!(source instanceof LdcInsnNode ldc)) throw new EmissionException("constant lacks a lowerable payload");
            output.instructions.add(new LdcInsnNode(ldc.cst));
        }
        store(instruction.result());
    }

    private void emitConstantDynamic(IrInstruction instruction) {
        ConstantDynamic dynamic;
        try {
            dynamic = JvmBootstrapAttributes.constantDynamic(instruction.operation().attributes());
        } catch (IllegalArgumentException missingPayload) {
            AbstractInsnNode source = source(instruction);
            if (!(source instanceof LdcInsnNode ldc) || !(ldc.cst instanceof ConstantDynamic original)) {
                throw new EmissionException("ConstantDynamic lacks a lowerable bootstrap payload");
            }
            dynamic = original;
        }
        output.instructions.add(new LdcInsnNode(dynamic));
        store(instruction.result());
    }

    private void emitBinary(IrInstruction instruction) {
        load(instruction.operands().get(0));
        load(instruction.operands().get(1));
        output.instructions.add(new InsnNode(binaryOpcode(instruction.operation().code(), instruction.result().type())));
        store(instruction.result());
    }

    private void emitUnaryOpcode(IrInstruction instruction, int opcode) {
        load(instruction.operands().getFirst());
        output.instructions.add(new InsnNode(opcode));
        store(instruction.result());
    }

    private void emitCompare(IrInstruction instruction) {
        load(instruction.operands().get(0));
        load(instruction.operands().get(1));
        output.instructions.add(new InsnNode(opcodeAttribute(instruction, "mode")));
        store(instruction.result());
    }

    private void emitArrayLoad(IrInstruction instruction) {
        load(instruction.operands().get(0));
        load(instruction.operands().get(1));
        output.instructions.add(new InsnNode(opcodeAttribute(instruction, "kind")));
        store(instruction.result());
    }

    private void emitArrayStore(IrInstruction instruction) {
        instruction.operands().forEach(this::load);
        output.instructions.add(new InsnNode(opcodeAttribute(instruction, "kind")));
    }

    private void emitField(IrInstruction instruction) {
        instruction.operands().forEach(this::load);
        int opcode = instruction.operation().code().equals(CoreOps.STATIC_LOAD) ? Opcodes.GETSTATIC
                : instruction.operation().code().equals(CoreOps.STATIC_STORE) ? Opcodes.PUTSTATIC
                : instruction.operation().code().equals(CoreOps.FIELD_LOAD) ? Opcodes.GETFIELD : Opcodes.PUTFIELD;
        output.instructions.add(new FieldInsnNode(opcode, stringAttribute(instruction, "owner"),
                stringAttribute(instruction, "name"), stringAttribute(instruction, "descriptor")));
        if (!instruction.results().isEmpty()) store(instruction.result());
    }

    private void emitInvoke(IrInstruction instruction, boolean constructor) {
        instruction.operands().forEach(this::load);
        int opcode = opcodeAttribute(instruction, "invoke_kind");
        boolean itf = booleanAttribute(instruction, "interface");
        output.instructions.add(new MethodInsnNode(opcode, stringAttribute(instruction, "owner"),
                stringAttribute(instruction, "name"), stringAttribute(instruction, "descriptor"), itf));
        if (!instruction.results().isEmpty()) store(instruction.result());
    }

    private void emitInitialize(IrInstruction instruction) {
        instruction.operands().forEach(this::load);
        output.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, stringAttribute(instruction, "owner"),
                "<init>", stringAttribute(instruction, "descriptor"), false));
        load(instruction.operands().getFirst());
        store(instruction.result());
    }

    private void emitInvokeDynamic(IrInstruction instruction) {
        instruction.operands().forEach(this::load);
        try {
            output.instructions.add(new InvokeDynamicInsnNode(stringAttribute(instruction, "name"),
                    stringAttribute(instruction, "descriptor"),
                    JvmBootstrapAttributes.bootstrapHandle(instruction.operation().attributes()),
                    JvmBootstrapAttributes.bootstrapArguments(instruction.operation().attributes())));
        } catch (IllegalArgumentException missingPayload) {
            AbstractInsnNode source = source(instruction);
            if (!(source instanceof InvokeDynamicInsnNode dynamic)) {
                throw new EmissionException("invokedynamic lacks a lowerable bootstrap payload");
            }
            output.instructions.add(new InvokeDynamicInsnNode(stringAttribute(instruction, "name"),
                    stringAttribute(instruction, "descriptor"), dynamic.bsm, dynamic.bsmArgs));
        }
        if (!instruction.results().isEmpty()) store(instruction.result());
    }

    private void emitNewObject(IrInstruction instruction) {
        IrType type = typeAttribute(instruction, "type");
        if (!(type instanceof ReferenceType reference)) throw new EmissionException("new_object type is not a class");
        output.instructions.add(new TypeInsnNode(Opcodes.NEW, reference.internalName()));
        store(instruction.result());
    }

    private void emitNewArray(IrInstruction instruction) {
        instruction.operands().forEach(this::load);
        AbstractInsnNode source = source(instruction);
        if (source instanceof IntInsnNode intInsn && intInsn.getOpcode() == Opcodes.NEWARRAY) {
            output.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, intInsn.operand));
        } else if (source instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.ANEWARRAY) {
            output.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, typeInsn.desc));
        } else if (source instanceof MultiANewArrayInsnNode multi) {
            output.instructions.add(new MultiANewArrayInsnNode(multi.desc, instruction.operands().size()));
        } else {
            IrType type = typeAttribute(instruction, "type");
            if (!(type instanceof ArrayType array)) throw new EmissionException("new_array type is not an array");
            if (instruction.operands().size() > 1) {
                output.instructions.add(new MultiANewArrayInsnNode(array.displayName(), instruction.operands().size()));
            } else if (array.dimensions() > 1 || array.elementType() instanceof ReferenceType) {
                String component = array.dimensions() > 1 ? array.displayName().substring(1)
                        : ((ReferenceType) array.elementType()).internalName();
                output.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, component));
            } else output.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, newArrayCode(array.elementType())));
        }
        store(instruction.result());
    }

    private void emitTypeOperation(IrInstruction instruction, int opcode) {
        load(instruction.operands().getFirst());
        IrType type = typeAttribute(instruction, "type");
        String descriptor = type instanceof ReferenceType reference ? reference.internalName() : type.displayName();
        output.instructions.add(new TypeInsnNode(opcode, descriptor));
        store(instruction.result());
    }

    private void emitVoidUnary(IrInstruction instruction, int opcode) {
        load(instruction.operands().getFirst());
        output.instructions.add(new InsnNode(opcode));
    }

    private void emitExceptionTable(LabelNode methodEnd) {
        Map<BasicBlock, Integer> order = new IdentityHashMap<>();
        for (int index = 0; index < blockOrder.size(); index++) order.put(blockOrder.get(index), index);
        method.exceptionRegions().stream().sorted(Comparator.comparingInt(ExceptionRegion::priority)).forEach(region -> {
            List<Integer> protectedIndices = region.protectedBlocks().stream().map(order::get).filter(Objects::nonNull).sorted().toList();
            if (protectedIndices.isEmpty()) return;
            int runStart = protectedIndices.getFirst();
            int previous = runStart;
            for (int offset = 1; offset <= protectedIndices.size(); offset++) {
                boolean endRun = offset == protectedIndices.size() || protectedIndices.get(offset) != previous + 1;
                if (endRun) {
                    LabelNode start = labels.get(blockOrder.get(runStart));
                    LabelNode end = previous + 1 < blockOrder.size() ? labels.get(blockOrder.get(previous + 1)) : methodEnd;
                    TryCatchBlockNode emitted = new TryCatchBlockNode(start, end, labels.get(region.handler()),
                            region.catchType().map(ReferenceType::internalName).orElse(null));
                    copyTryCatchAnnotations(region.priority(), emitted);
                    output.tryCatchBlocks.add(emitted);
                    if (offset < protectedIndices.size()) runStart = protectedIndices.get(offset);
                }
                if (offset < protectedIndices.size()) previous = protectedIndices.get(offset);
            }
        });
    }

    private void verify() {
        try {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(method.signature().owner(), output);
        } catch (AnalyzerException | RuntimeException exception) {
            throw new EmissionException("ASM verification failed: " + exception.getMessage());
        }
    }

    private BasicBlock nextBlock(BasicBlock block) {
        int index = blockOrder.indexOf(block);
        return index >= 0 && index + 1 < blockOrder.size() ? blockOrder.get(index + 1) : null;
    }

    private int invertConditionalOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new EmissionException("cannot invert conditional opcode " + opcode);
        };
    }

    private int maximumEdgeCopies() {
        return method.blocks().stream().mapToInt(block -> (int) block.outgoingEdges().stream()
                .filter(edge -> !edge.kind().isExceptional()).flatMap(edge -> edge.target().phis().stream())
                .count()).max().orElse(0);
    }

    private ControlEdge onlyNormalEdge(BasicBlock block) {
        List<ControlEdge> edges = block.normalSuccessors();
        if (edges.size() != 1) throw new EmissionException("branch does not have exactly one normal edge");
        return edges.getFirst();
    }

    private ControlEdge edge(BasicBlock block, EdgeKind kind) {
        return block.outgoingEdges().stream().filter(edge -> edge.kind() == kind).findFirst()
                .orElseThrow(() -> new EmissionException("block lacks " + kind + " edge"));
    }

    private int local(Value value) {
        Integer local = locals.get(value);
        if (local == null) throw new EmissionException("value has no allocated local: " + value);
        return local;
    }

    private void load(Value value) { output.instructions.add(new VarInsnNode(loadOpcode(value.type()), local(value))); }
    private void store(Value value) { output.instructions.add(new VarInsnNode(storeOpcode(value.type()), local(value))); }

    private int loadOpcode(IrType type) {
        if (type == PrimitiveType.LONG) return Opcodes.LLOAD;
        if (type == PrimitiveType.FLOAT) return Opcodes.FLOAD;
        if (type == PrimitiveType.DOUBLE) return Opcodes.DLOAD;
        if (type instanceof PrimitiveType) return Opcodes.ILOAD;
        return Opcodes.ALOAD;
    }

    private int storeOpcode(IrType type) {
        if (type == PrimitiveType.LONG) return Opcodes.LSTORE;
        if (type == PrimitiveType.FLOAT) return Opcodes.FSTORE;
        if (type == PrimitiveType.DOUBLE) return Opcodes.DSTORE;
        if (type instanceof PrimitiveType) return Opcodes.ISTORE;
        return Opcodes.ASTORE;
    }

    private int returnOpcode(IrType type) {
        if (type == PrimitiveType.LONG) return Opcodes.LRETURN;
        if (type == PrimitiveType.FLOAT) return Opcodes.FRETURN;
        if (type == PrimitiveType.DOUBLE) return Opcodes.DRETURN;
        if (type instanceof PrimitiveType) return Opcodes.IRETURN;
        return Opcodes.ARETURN;
    }

    private boolean isBinary(OperationCode code) {
        return Set.of(CoreOps.ADD, CoreOps.SUB, CoreOps.MUL, CoreOps.DIV, CoreOps.REM, CoreOps.AND,
                CoreOps.OR, CoreOps.XOR, CoreOps.SHL, CoreOps.SHR, CoreOps.USHR).contains(code);
    }

    private int binaryOpcode(OperationCode code, IrType type) {
        int offset = type == PrimitiveType.LONG ? 1 : type == PrimitiveType.FLOAT ? 2 : type == PrimitiveType.DOUBLE ? 3 : 0;
        if (code.equals(CoreOps.ADD)) return Opcodes.IADD + offset;
        if (code.equals(CoreOps.SUB)) return Opcodes.ISUB + offset;
        if (code.equals(CoreOps.MUL)) return Opcodes.IMUL + offset;
        if (code.equals(CoreOps.DIV)) return Opcodes.IDIV + offset;
        if (code.equals(CoreOps.REM)) return Opcodes.IREM + offset;
        if (code.equals(CoreOps.AND)) return type == PrimitiveType.LONG ? Opcodes.LAND : Opcodes.IAND;
        if (code.equals(CoreOps.OR)) return type == PrimitiveType.LONG ? Opcodes.LOR : Opcodes.IOR;
        if (code.equals(CoreOps.XOR)) return type == PrimitiveType.LONG ? Opcodes.LXOR : Opcodes.IXOR;
        if (code.equals(CoreOps.SHL)) return type == PrimitiveType.LONG ? Opcodes.LSHL : Opcodes.ISHL;
        if (code.equals(CoreOps.SHR)) return type == PrimitiveType.LONG ? Opcodes.LSHR : Opcodes.ISHR;
        if (code.equals(CoreOps.USHR)) return type == PrimitiveType.LONG ? Opcodes.LUSHR : Opcodes.IUSHR;
        throw new EmissionException("unknown binary operation");
    }

    private int negOpcode(IrType type) {
        return type == PrimitiveType.LONG ? Opcodes.LNEG : type == PrimitiveType.FLOAT ? Opcodes.FNEG
                : type == PrimitiveType.DOUBLE ? Opcodes.DNEG : Opcodes.INEG;
    }

    private int conversionOpcode(IrType source, IrType target) {
        if (source instanceof PrimitiveType sourcePrimitive && target instanceof PrimitiveType targetPrimitive
                && sourcePrimitive.computationalType() == targetPrimitive.computationalType()) {
            if (target == PrimitiveType.BYTE) return Opcodes.I2B;
            if (target == PrimitiveType.CHAR) return Opcodes.I2C;
            if (target == PrimitiveType.SHORT) return Opcodes.I2S;
        }
        if (source == PrimitiveType.INT || source == PrimitiveType.BYTE || source == PrimitiveType.CHAR
                || source == PrimitiveType.SHORT || source == PrimitiveType.BOOLEAN) {
            if (target == PrimitiveType.LONG) return Opcodes.I2L;
            if (target == PrimitiveType.FLOAT) return Opcodes.I2F;
            if (target == PrimitiveType.DOUBLE) return Opcodes.I2D;
        } else if (source == PrimitiveType.LONG) {
            if (target == PrimitiveType.INT) return Opcodes.L2I;
            if (target == PrimitiveType.FLOAT) return Opcodes.L2F;
            if (target == PrimitiveType.DOUBLE) return Opcodes.L2D;
        } else if (source == PrimitiveType.FLOAT) {
            if (target == PrimitiveType.INT) return Opcodes.F2I;
            if (target == PrimitiveType.LONG) return Opcodes.F2L;
            if (target == PrimitiveType.DOUBLE) return Opcodes.F2D;
        } else if (source == PrimitiveType.DOUBLE) {
            if (target == PrimitiveType.INT) return Opcodes.D2I;
            if (target == PrimitiveType.LONG) return Opcodes.D2L;
            if (target == PrimitiveType.FLOAT) return Opcodes.D2F;
        }
        throw new EmissionException("unsupported conversion " + source.displayName() + " -> " + target.displayName());
    }

    private int newArrayCode(IrType type) {
        if (type == PrimitiveType.BOOLEAN) return Opcodes.T_BOOLEAN;
        if (type == PrimitiveType.CHAR) return Opcodes.T_CHAR;
        if (type == PrimitiveType.FLOAT) return Opcodes.T_FLOAT;
        if (type == PrimitiveType.DOUBLE) return Opcodes.T_DOUBLE;
        if (type == PrimitiveType.BYTE) return Opcodes.T_BYTE;
        if (type == PrimitiveType.SHORT) return Opcodes.T_SHORT;
        if (type == PrimitiveType.INT) return Opcodes.T_INT;
        if (type == PrimitiveType.LONG) return Opcodes.T_LONG;
        throw new EmissionException("invalid primitive array element " + type.displayName());
    }

    private int opcodeAttribute(IrInstruction instruction, String name) {
        return opcode(stringAttribute(instruction, name));
    }

    private int opcode(String mnemonic) {
        for (int opcode = 0; opcode < org.objectweb.asm.util.Printer.OPCODES.length; opcode++) {
            if (mnemonic.equals(org.objectweb.asm.util.Printer.OPCODES[opcode])) return opcode;
        }
        throw new EmissionException("unknown JVM opcode mnemonic " + mnemonic);
    }

    private String stringAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        if (value instanceof IrAttribute.StringValue string) return string.value();
        throw new EmissionException(instruction.operation().code().qualifiedName() + " lacks string attribute " + name);
    }

    private long longAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        if (value instanceof IrAttribute.LongValue number) return number.value();
        throw new EmissionException(instruction.operation().code().qualifiedName() + " lacks integer attribute " + name);
    }

    private boolean booleanAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        if (value instanceof IrAttribute.BooleanValue bool) return bool.value();
        throw new EmissionException(instruction.operation().code().qualifiedName() + " lacks boolean attribute " + name);
    }

    private IrType typeAttribute(IrInstruction instruction, String name) {
        IrAttribute value = instruction.operation().attributes().get(name);
        if (value instanceof IrAttribute.TypeValue type) return type.value();
        throw new EmissionException(instruction.operation().code().qualifiedName() + " lacks type attribute " + name);
    }

    private AbstractInsnNode source(IrInstruction instruction) {
        return origin.sourceMap().source(instruction).orElse(null);
    }

    private record Copy(Value source, Value destination) {}
    private record LocalVariableRange(String name, String descriptor, String signature,
                                      int start, int end, int index) {}
    private record LocalRange(int start, int end, int index) {}
    private record LocalAnnotationRange(LocalVariableAnnotationNode annotation, List<LocalRange> ranges) {}
    private static final class EmissionException extends RuntimeException {
        private EmissionException(String message) { super(message); }
    }
}
