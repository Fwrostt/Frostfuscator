package dev.frost.ir.bytecode;

import dev.frost.ir.analysis.ControlFlow;
import dev.frost.ir.analysis.EdgePolicy;
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
import dev.frost.ir.model.Operation;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import dev.frost.ir.type.ArrayType;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.Nullability;
import dev.frost.ir.type.PrimitiveType;
import dev.frost.ir.type.ReferenceType;
import dev.frost.ir.type.SpecialType;
import dev.frost.ir.type.UninitializedType;
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
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.Printer;

/** Converts verified JVM operand-stack/local state into typed, edge-phi Frost SSA. */
public final class BytecodeSsaImporter {
    private final IrContext context;

    public BytecodeSsaImporter(IrContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public BytecodeImportResult importMethod(String owner, MethodNode source) {
        return importMethod(owner, source, BytecodeImportOptions.defaults());
    }

    public BytecodeImportResult importMethod(String owner, MethodNode source, BytecodeImportOptions options) {
        Objects.requireNonNull(source, "source");
        MethodNode original = source;
        boolean inlinedSubroutines = containsLegacySubroutine(source);
        if (inlinedSubroutines) {
            try {
                source = inlineLegacySubroutines(source);
            } catch (RuntimeException exception) {
                return fallback(owner, original, options, List.of(), new Diagnostic(Diagnostic.Severity.ERROR,
                        "ssa.jsr-ret-inline", "JSR/RET inlining failed: " + exception.getMessage(), null,
                        SourcePosition.UNKNOWN, Map.of("exception", exception.getClass().getName())));
            }
        }
        BytecodeImportResult cfg = new BytecodeCfgImporter(context).importMethod(owner, source, options);
        if (hasErrors(cfg.diagnostics()) || source.instructions == null || source.instructions.size() == 0) {
            if (!inlinedSubroutines) return cfg;
            return fallback(owner, original, options, cfg.diagnostics(), new Diagnostic(Diagnostic.Severity.ERROR,
                    "ssa.jsr-ret-cfg", "Inlined JSR/RET CFG construction failed", null,
                    SourcePosition.UNKNOWN, Map.of()));
        }
        AbstractInsnNode[] nodes = source.instructions.toArray();

        Frame<BasicValue>[] verifierFrames;
        try {
            verifierFrames = new Analyzer<>(new BasicInterpreter()).analyze(owner, source);
        } catch (AnalyzerException | RuntimeException exception) {
            return fallback(owner, original, options, cfg.diagnostics(), new Diagnostic(Diagnostic.Severity.ERROR,
                    "ssa.frame-analysis", "ASM frame analysis failed: " + exception.getMessage(), null,
                    SourcePosition.UNKNOWN, Map.of("exception", exception.getClass().getName())));
        }

        try {
            BytecodeImportResult result = lift(owner, source, options, cfg, nodes, verifierFrames);
            if (!inlinedSubroutines) return result;
            EnumSet<ImportCapability> capabilities = result.capabilities().isEmpty()
                    ? EnumSet.noneOf(ImportCapability.class) : EnumSet.copyOf(result.capabilities());
            capabilities.add(ImportCapability.LEGACY_SUBROUTINES_INLINED);
            List<Diagnostic> diagnostics = new ArrayList<>(result.diagnostics());
            diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, "ssa.jsr-ret-inlined",
                    "Legacy JSR/RET subroutines were normalized before SSA construction", null,
                    SourcePosition.UNKNOWN, Map.of()));
            return new BytecodeImportResult(result.method(), result.sourceMap(), AsmMethodCloner.clone(original),
                    result.importedRevision(), capabilities, diagnostics, result.frameStates());
        } catch (LiftException | IllegalArgumentException | IllegalStateException exception) {
            int position = exception instanceof LiftException lift ? lift.position : -1;
            return fallback(owner, original, options, cfg.diagnostics(), new Diagnostic(Diagnostic.Severity.ERROR,
                    "ssa.lift", "Typed SSA lifting failed: " + exception.getMessage(), null,
                    position < 0 ? SourcePosition.UNKNOWN : new SourcePosition(position, -1, -1),
                    Map.of("exception", exception.getClass().getName())));
        }
    }

    private boolean containsLegacySubroutine(MethodNode source) {
        if (source.instructions == null) return false;
        for (AbstractInsnNode instruction : source.instructions) {
            if (instruction.getOpcode() == Opcodes.JSR || instruction.getOpcode() == Opcodes.RET) return true;
        }
        return false;
    }

    private MethodNode inlineLegacySubroutines(MethodNode source) {
        MethodNode output = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc,
                source.signature, source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        JSRInlinerAdapter inliner = new JSRInlinerAdapter(output, source.access, source.name, source.desc,
                source.signature, source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        source.accept(inliner);
        return output;
    }

    private BytecodeImportResult lift(String owner, MethodNode source, BytecodeImportOptions options,
                                      BytecodeImportResult cfg, AbstractInsnNode[] nodes,
                                      Frame<BasicValue>[] verifierFrames) {
        IrMethod method = cfg.method();
        Set<BasicBlock> reachable = ControlFlow.reachable(method, EdgePolicy.NORMAL_AND_EXCEPTIONAL);
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        for (int index = 0; index < nodes.length; index++) indices.put(nodes[index], index);
        Map<AbstractInsnNode, Long> lineNumbers = lineNumbers(nodes);
        Map<BasicBlock, BlockLayout> layouts = layouts(method, nodes);
        Map<BasicBlock, Set<Integer>> liveInLocals = liveInLocals(method, nodes, layouts, reachable);
        for (BasicBlock block : reachable) {
            BlockLayout layout = layouts.get(block);
            if (layout == null || layout.firstExecutable < 0 || verifierFrames[layout.firstExecutable] == null) {
                throw new LiftException(layout == null ? -1 : layout.start,
                        "reachable block " + block.name() + " has no verifier frame");
            }
        }

        Map<AbstractInsnNode, List<IrInstruction>> nodeInstructions = new IdentityHashMap<>();
        Map<IrInstruction, AbstractInsnNode> instructionNodes = new IdentityHashMap<>();
        cfg.sourceMap().nodeInstructions().forEach((node, instruction) -> {
            BasicBlock block = cfg.sourceMap().block(node).orElse(null);
            if (block != null && !reachable.contains(block)) {
                nodeInstructions.put(node, List.of(instruction));
                instructionNodes.put(instruction, node);
            }
        });
        Map<BasicBlock, MutableFrame> entries = new IdentityHashMap<>();
        Map<BasicBlock, MutableFrame> exits = new IdentityHashMap<>();
        Map<BasicBlock, MutableFrame> exceptionalBases = new IdentityHashMap<>();
        Map<BasicBlock, PhiLayout> phiLayouts = new IdentityHashMap<>();
        Map<BasicBlock, JvmFrameState> entryViews = new IdentityHashMap<>();
        Map<BasicBlock, JvmFrameState> exitViews = new IdentityHashMap<>();
        Map<AbstractInsnNode, JvmFrameState> beforeViews = new IdentityHashMap<>();
        Map<AbstractInsnNode, JvmFrameState> afterViews = new IdentityHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>(cfg.diagnostics());

        try (IrMethod.Mutation ignored = method.beginMutation("typed-stack-ssa")) {
            for (BasicBlock block : reachable) {
                List<IrInstruction> old = new ArrayList<>(block.instructions());
                Collections.reverse(old);
                old.forEach(block::remove);
            }
            BasicBlock entryBlock = method.entryBlock().orElseThrow();
            MutableFrame initial = initialFrame(method, source);
            entries.put(entryBlock, initial);
            entryViews.put(entryBlock, initial.view());

            for (BasicBlock block : reachable) {
                if (block == entryBlock) continue;
                Frame<BasicValue> verifier = verifierFrames[layouts.get(block).firstExecutable];
                PhiLayout phis = createEntryPhis(block, verifier, liveInLocals.getOrDefault(block, Set.of()));
                phiLayouts.put(block, phis);
                MutableFrame frame = phis.frame();
                entries.put(block, frame);
                entryViews.put(block, frame.view());
            }

            for (BasicBlock block : method.blocks()) {
                if (!reachable.contains(block)) continue;
                MutableFrame frame = entries.get(block).copy();
                BlockLayout layout = layouts.get(block);
                for (int index = layout.start; index < layout.end; index++) {
                    AbstractInsnNode node = nodes[index];
                    if (node.getOpcode() < 0) continue;
                    beforeViews.put(node, frame.view());
                    if (canThrow(node) && !block.exceptionalSuccessors().isEmpty()) {
                        if (exceptionalBases.putIfAbsent(block, frame.copy()) != null) {
                            throw new LiftException(index, "protected throwing instructions were not isolated into distinct blocks");
                        }
                    }
                    List<IrInstruction> emitted = translate(method, block, node, index, frame);
                    if (!emitted.isEmpty()) {
                        nodeInstructions.put(node, List.copyOf(emitted));
                        for (IrInstruction instruction : emitted) {
                            instruction.metadata().put(AsmMetadataKeys.INSTRUCTION_INDEX, (long) index);
                            instruction.metadata().put(AsmMetadataKeys.OPCODE, (long) node.getOpcode());
                            instruction.metadata().put(AsmMetadataKeys.OPCODE_NAME, opcodeName(node.getOpcode()));
                            Long line = lineNumbers.get(node);
                            if (line != null) instruction.metadata().put(AsmMetadataKeys.LINE_NUMBER, line);
                            instructionNodes.put(instruction, node);
                        }
                    }
                    afterViews.put(node, frame.view());
                }
                if (block.terminator().isEmpty()) {
                    block.append(method.createInstruction(CoreOps.BRANCH, List.of(), List.of()));
                }
                exits.put(block, frame.copy());
                exitViews.put(block, frame.view());
            }

            populatePhiInputs(method, reachable, entries, exits, exceptionalBases, phiLayouts, diagnostics);
        }

        ValidationProfile profile = reachable.size() == method.blocks().size()
                ? ValidationProfile.STRICT : ValidationProfile.STRUCTURAL;
        diagnostics.addAll(new IrValidator().validate(method, profile).diagnostics());
        if (hasErrors(diagnostics)) {
            return fallback(owner, source, options, diagnostics, new Diagnostic(Diagnostic.Severity.ERROR,
                    "ssa.validation", "Lifted SSA failed validation", null, SourcePosition.UNKNOWN, Map.of()));
        }
        EnumSet<ImportCapability> capabilities = EnumSet.copyOf(cfg.capabilities());
        if (reachable.size() == method.blocks().size()) {
            capabilities.add(ImportCapability.TYPED_STACK_SSA);
            capabilities.add(ImportCapability.LOWERABLE_AFTER_MUTATION);
        }
        capabilities.add(ImportCapability.FRAME_STATES);
        FrameStateMap frameStates = new FrameStateMap(entryViews, exitViews, beforeViews, afterViews);
        return new BytecodeImportResult(method,
                new AsmSourceMap(cfg.sourceMap().nodeBlocks(), nodeInstructions, instructionNodes, true),
                cfg.preservedSnapshot(), method.revision(), capabilities, diagnostics, frameStates);
    }

    private Map<BasicBlock, BlockLayout> layouts(IrMethod method, AbstractInsnNode[] nodes) {
        Map<BasicBlock, BlockLayout> result = new IdentityHashMap<>();
        for (BasicBlock block : method.blocks()) {
            int start = Math.toIntExact(block.metadata().get(AsmMetadataKeys.BLOCK_START).orElseThrow());
            int end = Math.toIntExact(block.metadata().get(AsmMetadataKeys.BLOCK_END).orElseThrow());
            int first = -1;
            for (int index = start; index < Math.min(end, nodes.length); index++) {
                if (nodes[index].getOpcode() >= 0) { first = index; break; }
            }
            result.put(block, new BlockLayout(start, end, first));
        }
        return result;
    }

    /**
     * Computes pruned physical-local liveness before constructing SSA phis.  The lowerer spills
     * SSA values to JVM locals, but most of those slots are defined and consumed inside one basic
     * block.  Creating entry phis for every verifier local would make those temporary slots live
     * forever and add another generation of phis on each IR round trip.
     */
    private Map<BasicBlock, Set<Integer>> liveInLocals(IrMethod method,
                                                       AbstractInsnNode[] nodes,
                                                       Map<BasicBlock, BlockLayout> layouts,
                                                       Set<BasicBlock> reachable) {
        Map<BasicBlock, Set<Integer>> uses = new IdentityHashMap<>();
        Map<BasicBlock, Set<Integer>> definitions = new IdentityHashMap<>();
        Map<BasicBlock, Set<Integer>> liveIn = new IdentityHashMap<>();
        Map<BasicBlock, Set<Integer>> liveOut = new IdentityHashMap<>();
        for (BasicBlock block : reachable) {
            Set<Integer> blockUses = new LinkedHashSet<>();
            Set<Integer> blockDefinitions = new LinkedHashSet<>();
            BlockLayout layout = layouts.get(block);
            for (int index = layout.start; index < layout.end; index++) {
                AbstractInsnNode node = nodes[index];
                if (node instanceof VarInsnNode variable) {
                    if (isLocalLoad(variable.getOpcode()) && !blockDefinitions.contains(variable.var)) {
                        blockUses.add(variable.var);
                    } else if (isLocalStore(variable.getOpcode())) {
                        blockDefinitions.add(variable.var);
                    }
                } else if (node instanceof IincInsnNode increment) {
                    if (!blockDefinitions.contains(increment.var)) blockUses.add(increment.var);
                    blockDefinitions.add(increment.var);
                }
            }
            uses.put(block, blockUses);
            definitions.put(block, blockDefinitions);
            liveIn.put(block, new LinkedHashSet<>());
            liveOut.put(block, new LinkedHashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            List<BasicBlock> reverse = new ArrayList<>(method.blocks());
            Collections.reverse(reverse);
            for (BasicBlock block : reverse) {
                if (!reachable.contains(block)) continue;
                Set<Integer> nextOut = new LinkedHashSet<>();
                for (ControlEdge edge : block.outgoingEdges()) {
                    if (reachable.contains(edge.target())) nextOut.addAll(liveIn.get(edge.target()));
                }
                Set<Integer> nextIn = new LinkedHashSet<>(nextOut);
                nextIn.removeAll(definitions.get(block));
                nextIn.addAll(uses.get(block));
                if (!nextOut.equals(liveOut.get(block))) {
                    liveOut.put(block, nextOut);
                    changed = true;
                }
                if (!nextIn.equals(liveIn.get(block))) {
                    liveIn.put(block, nextIn);
                    changed = true;
                }
            }
        } while (changed);
        return liveIn;
    }

    private boolean isLocalLoad(int opcode) {
        return opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD
                || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD;
    }

    private boolean isLocalStore(int opcode) {
        return opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE
                || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
    }

    private MutableFrame initialFrame(IrMethod method, MethodNode source) {
        int parameterSlots = 0;
        for (var parameter : method.parameters()) parameterSlots += Math.max(1, parameter.value().type().slots());
        MutableFrame frame = new MutableFrame(Math.max(source.maxLocals, parameterSlots));
        int local = 0;
        for (var parameter : method.parameters()) {
            frame.writeLocal(local, parameter.value());
            local += Math.max(1, parameter.value().type().slots());
        }
        return frame;
    }

    private PhiLayout createEntryPhis(BasicBlock block, Frame<BasicValue> verifier,
                                      Set<Integer> liveLocals) {
        MutableFrame frame = new MutableFrame(verifier.getLocals());
        Map<Integer, PhiNode> localPhis = new LinkedHashMap<>();
        Map<Integer, PhiNode> stackPhis = new LinkedHashMap<>();
        for (int local = 0; local < verifier.getLocals(); local++) {
            IrType type = verifierType(verifier.getLocal(local));
            if (type == SpecialType.TOP || !liveLocals.contains(local)) continue;
            PhiNode phi = block.addPhi(type, "l" + local + "_" + block.name());
            phi.metadata().put(AsmMetadataKeys.PHI_SLOT_KIND, "local");
            phi.metadata().put(AsmMetadataKeys.PHI_SLOT_INDEX, (long) local);
            localPhis.put(local, phi);
            frame.setRawLocal(local, new Slot(type, phi.result()));
            if (type.isCategory2() && local + 1 < verifier.getLocals()) {
                frame.setRawLocal(++local, Slot.TOP);
            }
        }
        for (int stack = 0; stack < verifier.getStackSize(); stack++) {
            IrType type = verifierType(verifier.getStack(stack));
            if (type == SpecialType.TOP) throw new LiftException(-1, "TOP value appears on verifier operand stack");
            PhiNode phi = block.addPhi(type, "s" + stack + "_" + block.name());
            phi.metadata().put(AsmMetadataKeys.PHI_SLOT_KIND, "stack");
            phi.metadata().put(AsmMetadataKeys.PHI_SLOT_INDEX, (long) stack);
            stackPhis.put(stack, phi);
            frame.stack.add(phi.result());
        }
        return new PhiLayout(frame, localPhis, stackPhis);
    }

    private void populatePhiInputs(IrMethod method, Set<BasicBlock> reachable,
                                   Map<BasicBlock, MutableFrame> entries,
                                   Map<BasicBlock, MutableFrame> exits,
                                   Map<BasicBlock, MutableFrame> exceptionalBases,
                                   Map<BasicBlock, PhiLayout> layouts,
                                   List<Diagnostic> diagnostics) {
        for (ControlEdge edge : method.edges()) {
            if (!reachable.contains(edge.source()) || !reachable.contains(edge.target())) continue;
            PhiLayout target = layouts.get(edge.target());
            if (target == null) {
                throw new LiftException(-1, "control flows into entry block without an entry phi model");
            }
            MutableFrame outgoing;
            if (edge.kind().isExceptional()) {
                MutableFrame base = exceptionalBases.get(edge.source());
                if (base == null) throw new LiftException(-1, "exceptional edge has no throwing program point");
                outgoing = base.copy();
                outgoing.stack.clear();
                IrType exceptionType = edge.catchType().<IrType>map(value -> value)
                        .orElse(ReferenceType.THROWABLE);
                Value exception = edge.addValue("exception", exceptionType).result();
                outgoing.stack.add(exception);
            } else {
                outgoing = Objects.requireNonNull(exits.get(edge.source()), "source exit frame").copy();
            }
            for (Map.Entry<Integer, PhiNode> entry : target.localPhis.entrySet()) {
                Slot slot = outgoing.local(entry.getKey());
                if (slot.value == null) throw new LiftException(-1,
                        "incoming local " + entry.getKey() + " is TOP for phi " + entry.getValue().id());
                entry.getValue().putInput(edge, slot.value);
            }
            if (outgoing.stack.size() != target.stackPhis.size()) {
                throw new LiftException(-1, "stack height mismatch on edge " + edge.id() + ": "
                        + outgoing.stack.size() + " != " + target.stackPhis.size());
            }
            for (Map.Entry<Integer, PhiNode> entry : target.stackPhis.entrySet()) {
                entry.getValue().putInput(edge, outgoing.stack.get(entry.getKey()));
            }
        }
    }

    private List<IrInstruction> translate(IrMethod method, BasicBlock block, AbstractInsnNode node,
                                          int position, MutableFrame frame) {
        int opcode = node.getOpcode();
        List<IrInstruction> emitted = new ArrayList<>();
        switch (opcode) {
            case Opcodes.NOP -> emit(method, block, node, CoreOps.NOP, List.of(), List.of(), Map.of(), emitted);
            case Opcodes.ACONST_NULL -> frame.push(emitResult(method, block, node, CoreOps.CONSTANT, List.of(),
                    SpecialType.NULL, Map.of("value", IrAttribute.of("null")), emitted));
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                    Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> frame.push(emitResult(method, block,
                    node, CoreOps.CONSTANT, List.of(), PrimitiveType.INT,
                    Map.of("value", IrAttribute.of((long) (opcode - Opcodes.ICONST_0))), emitted));
            case Opcodes.LCONST_0, Opcodes.LCONST_1 -> frame.push(emitResult(method, block, node,
                    CoreOps.CONSTANT, List.of(), PrimitiveType.LONG,
                    Map.of("value", IrAttribute.of((long) (opcode - Opcodes.LCONST_0))), emitted));
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> frame.push(emitResult(method, block,
                    node, CoreOps.CONSTANT, List.of(), PrimitiveType.FLOAT,
                    Map.of("value", IrAttribute.of((double) (opcode - Opcodes.FCONST_0))), emitted));
            case Opcodes.DCONST_0, Opcodes.DCONST_1 -> frame.push(emitResult(method, block, node,
                    CoreOps.CONSTANT, List.of(), PrimitiveType.DOUBLE,
                    Map.of("value", IrAttribute.of((double) (opcode - Opcodes.DCONST_0))), emitted));
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> frame.push(emitResult(method, block, node,
                    CoreOps.CONSTANT, List.of(), PrimitiveType.INT,
                    Map.of("value", IrAttribute.of((long) ((IntInsnNode) node).operand)), emitted));
            case Opcodes.LDC -> frame.push(liftLdc(method, block, (LdcInsnNode) node, emitted));

            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                int local = ((VarInsnNode) node).var;
                // JVM local traffic is an implementation detail, not an SSA operation.  Keeping
                // every load as COPY made a lower/import/lower cycle materialize the emitter's
                // spill code as new values on every transformer, causing exponential bytecode and
                // max-local growth.  The frame already carries the exact reaching SSA definition.
                frame.push(frame.readLocal(local));
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                int local = ((VarInsnNode) node).var;
                Value value = frame.pop();
                // Likewise, a store only updates the importer frame.  Local phis preserve merges
                // and the lowerer chooses physical slots when publishing bytecode.
                frame.writeLocal(local, value);
            }
            case Opcodes.IINC -> liftIncrement(method, block, (IincInsnNode) node, frame, emitted);

            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                    Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                Value index = frame.pop();
                Value array = frame.pop();
                IrType resultType = arrayLoadType(opcode, array.type());
                frame.push(emitResult(method, block, node, CoreOps.ARRAY_LOAD, List.of(array, index), resultType,
                        Map.of("kind", IrAttribute.of(opcodeName(opcode))), emitted));
            }
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                    Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                Value value = frame.pop();
                Value index = frame.pop();
                Value array = frame.pop();
                emit(method, block, node, CoreOps.ARRAY_STORE, List.of(array, index, value), List.of(),
                        Map.of("kind", IrAttribute.of(opcodeName(opcode))), emitted);
            }

            case Opcodes.POP, Opcodes.POP2, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
                    Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP ->
                    liftStackPermutation(method, block, node, frame, emitted);

            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
                    Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                    Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
                    Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
                    Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
                    Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR,
                    Opcodes.IXOR, Opcodes.LXOR -> liftBinary(method, block, node, frame, emitted);
            case Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR, Opcodes.IUSHR, Opcodes.LUSHR ->
                    liftBinary(method, block, node, frame, emitted);
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> {
                Value value = frame.pop();
                frame.push(emitResult(method, block, node, CoreOps.NEG, List.of(value), numericType(opcode), Map.of(), emitted));
            }
            case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                    Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                    Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> {
                Value value = frame.pop();
                IrType target = conversionType(opcode);
                frame.push(emitResult(method, block, node, CoreOps.CONVERT, List.of(value), target,
                        Map.of("to", IrAttribute.of(target)), emitted));
            }
            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                Value right = frame.pop();
                Value left = frame.pop();
                frame.push(emitResult(method, block, node, CoreOps.COMPARE, List.of(left, right), PrimitiveType.INT,
                        Map.of("mode", IrAttribute.of(opcodeName(opcode))), emitted));
            }

            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                    Opcodes.IFNULL, Opcodes.IFNONNULL -> emit(method, block, node, CoreOps.CONDITIONAL_BRANCH,
                    List.of(frame.pop()), List.of(), Map.of("condition", IrAttribute.of(opcodeName(opcode))), emitted);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                    Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                Value right = frame.pop();
                Value left = frame.pop();
                emit(method, block, node, CoreOps.CONDITIONAL_BRANCH, List.of(left, right), List.of(),
                        Map.of("condition", IrAttribute.of(opcodeName(opcode))), emitted);
            }
            case Opcodes.GOTO -> emit(method, block, node, CoreOps.BRANCH, List.of(), List.of(), Map.of(), emitted);
            case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> emit(method, block, node, CoreOps.SWITCH,
                    List.of(frame.pop()), List.of(), Map.of("kind", IrAttribute.of(opcodeName(opcode))), emitted);
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN ->
                    emit(method, block, node, CoreOps.RETURN, List.of(frame.pop()), List.of(), Map.of(), emitted);
            case Opcodes.RETURN -> emit(method, block, node, CoreOps.RETURN, List.of(), List.of(), Map.of(), emitted);

            case Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD ->
                    liftField(method, block, (FieldInsnNode) node, frame, emitted);
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                    liftInvoke(method, block, (MethodInsnNode) node, frame, emitted);
            case Opcodes.INVOKEDYNAMIC -> liftInvokeDynamic(method, block, (InvokeDynamicInsnNode) node, frame, emitted);

            case Opcodes.NEW -> {
                TypeInsnNode allocation = (TypeInsnNode) node;
                ReferenceType initialized = new ReferenceType(allocation.desc, Nullability.NON_NULL);
                IrInstruction instruction = method.createInstruction(
                        new Operation(CoreOps.NEW_OBJECT, Map.of("type", IrAttribute.of(initialized))), List.of(),
                        id -> List.of(new UninitializedType(id, initialized)));
                block.append(instruction);
                emitted.add(instruction);
                frame.push(instruction.result());
            }
            case Opcodes.NEWARRAY -> {
                Value length = frame.pop();
                IrType element = primitiveArrayElement(((IntInsnNode) node).operand);
                ArrayType type = new ArrayType(element, 1, Nullability.NON_NULL);
                frame.push(emitResult(method, block, node, CoreOps.NEW_ARRAY, List.of(length), type,
                        Map.of("type", IrAttribute.of(type)), emitted));
            }
            case Opcodes.ANEWARRAY -> {
                Value length = frame.pop();
                ArrayType type = arrayType(((TypeInsnNode) node).desc);
                frame.push(emitResult(method, block, node, CoreOps.NEW_ARRAY, List.of(length), type,
                        Map.of("type", IrAttribute.of(type)), emitted));
            }
            case Opcodes.MULTIANEWARRAY -> {
                MultiANewArrayInsnNode multi = (MultiANewArrayInsnNode) node;
                List<Value> dimensions = frame.popArguments(multi.dims);
                ArrayType type = (ArrayType) JvmTypeAdapter.fromAsm(Type.getType(multi.desc));
                type = type.withNullability(Nullability.NON_NULL);
                frame.push(emitResult(method, block, node, CoreOps.NEW_ARRAY, dimensions, type,
                        Map.of("type", IrAttribute.of(type), "dimensions", IrAttribute.of((long) multi.dims)), emitted));
            }
            case Opcodes.ARRAYLENGTH -> {
                Value array = frame.pop();
                frame.push(emitResult(method, block, node, CoreOps.ARRAY_LENGTH, List.of(array), PrimitiveType.INT,
                        Map.of(), emitted));
            }
            case Opcodes.ATHROW -> emit(method, block, node, CoreOps.THROW, List.of(frame.pop()), List.of(), Map.of(), emitted);
            case Opcodes.CHECKCAST -> {
                Value value = frame.pop();
                IrType type = referenceOrArray(((TypeInsnNode) node).desc, Nullability.UNKNOWN);
                frame.push(emitResult(method, block, node, CoreOps.CHECK_CAST, List.of(value), type,
                        Map.of("type", IrAttribute.of(type)), emitted));
            }
            case Opcodes.INSTANCEOF -> {
                Value value = frame.pop();
                IrType type = referenceOrArray(((TypeInsnNode) node).desc, Nullability.UNKNOWN);
                frame.push(emitResult(method, block, node, CoreOps.INSTANCE_OF, List.of(value), PrimitiveType.INT,
                        Map.of("type", IrAttribute.of(type)), emitted));
            }
            case Opcodes.MONITORENTER -> emit(method, block, node, CoreOps.MONITOR_ENTER,
                    List.of(frame.pop()), List.of(), Map.of(), emitted);
            case Opcodes.MONITOREXIT -> emit(method, block, node, CoreOps.MONITOR_EXIT,
                    List.of(frame.pop()), List.of(), Map.of(), emitted);
            default -> throw new LiftException(position, "unsupported opcode " + opcodeName(opcode));
        }
        return emitted;
    }

    private Value liftLdc(IrMethod method, BasicBlock block, LdcInsnNode node, List<IrInstruction> emitted) {
        Object constant = node.cst;
        IrType type;
        OperationCode code = CoreOps.CONSTANT;
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        if (constant instanceof Integer value) { type = PrimitiveType.INT; attributes.put("value", IrAttribute.of(value.longValue())); }
        else if (constant instanceof Float value) { type = PrimitiveType.FLOAT; attributes.put("value", IrAttribute.of(value.doubleValue())); }
        else if (constant instanceof Long value) { type = PrimitiveType.LONG; attributes.put("value", IrAttribute.of(value)); }
        else if (constant instanceof Double value) { type = PrimitiveType.DOUBLE; attributes.put("value", IrAttribute.of(value)); }
        else if (constant instanceof String value) {
            type = new ReferenceType("java/lang/String", Nullability.NON_NULL); attributes.put("value", IrAttribute.of(value));
        } else if (constant instanceof Type value) {
            type = new ReferenceType(value.getSort() == Type.METHOD ? "java/lang/invoke/MethodType" : "java/lang/Class",
                    Nullability.NON_NULL);
            attributes.put("descriptor", IrAttribute.of(value.getDescriptor()));
        } else if (constant instanceof Handle value) {
            type = new ReferenceType("java/lang/invoke/MethodHandle", Nullability.NON_NULL);
            attributes.put("handle", IrAttribute.of(value.toString()));
        } else if (constant instanceof ConstantDynamic value) {
            type = JvmTypeAdapter.fromAsm(Type.getType(value.getDescriptor()));
            code = CoreOps.CONSTANT_DYNAMIC;
            attributes.putAll(JvmBootstrapAttributes.dynamicConstant(value));
        } else throw new LiftException(-1, "unsupported LDC constant " + constant);
        return emitResult(method, block, node, code, List.of(), type, attributes, emitted);
    }

    private void liftIncrement(IrMethod method, BasicBlock block, IincInsnNode node,
                               MutableFrame frame, List<IrInstruction> emitted) {
        Value original = frame.readLocal(node.var);
        Value amount = emitResult(method, block, node, CoreOps.CONSTANT, List.of(), PrimitiveType.INT,
                Map.of("value", IrAttribute.of((long) node.incr)), emitted);
        Value incremented = emitResult(method, block, node, CoreOps.ADD, List.of(original, amount), PrimitiveType.INT,
                Map.of(), emitted);
        frame.writeLocal(node.var, incremented);
    }

    private void liftBinary(IrMethod method, BasicBlock block, AbstractInsnNode node,
                            MutableFrame frame, List<IrInstruction> emitted) {
        Value right = frame.pop();
        Value left = frame.pop();
        OperationCode code = switch (node.getOpcode()) {
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD -> CoreOps.ADD;
            case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB -> CoreOps.SUB;
            case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL -> CoreOps.MUL;
            case Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV -> CoreOps.DIV;
            case Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> CoreOps.REM;
            case Opcodes.IAND, Opcodes.LAND -> CoreOps.AND;
            case Opcodes.IOR, Opcodes.LOR -> CoreOps.OR;
            case Opcodes.IXOR, Opcodes.LXOR -> CoreOps.XOR;
            case Opcodes.ISHL, Opcodes.LSHL -> CoreOps.SHL;
            case Opcodes.ISHR, Opcodes.LSHR -> CoreOps.SHR;
            case Opcodes.IUSHR, Opcodes.LUSHR -> CoreOps.USHR;
            default -> throw new LiftException(-1, "not a binary opcode");
        };
        frame.push(emitResult(method, block, node, code, List.of(left, right), numericType(node.getOpcode()), Map.of(), emitted));
    }

    private void liftField(IrMethod method, BasicBlock block, FieldInsnNode node,
                           MutableFrame frame, List<IrInstruction> emitted) {
        IrType fieldType = JvmTypeAdapter.fromAsm(Type.getType(node.desc));
        Map<String, IrAttribute> attributes = memberAttributes(node.owner, node.name, node.desc);
        switch (node.getOpcode()) {
            case Opcodes.GETSTATIC -> frame.push(emitResult(method, block, node, CoreOps.STATIC_LOAD,
                    List.of(), fieldType, attributes, emitted));
            case Opcodes.PUTSTATIC -> emit(method, block, node, CoreOps.STATIC_STORE,
                    List.of(frame.pop()), List.of(), attributes, emitted);
            case Opcodes.GETFIELD -> {
                Value receiver = frame.pop();
                frame.push(emitResult(method, block, node, CoreOps.FIELD_LOAD,
                        List.of(receiver), fieldType, attributes, emitted));
            }
            case Opcodes.PUTFIELD -> {
                Value value = frame.pop();
                Value receiver = frame.pop();
                emit(method, block, node, CoreOps.FIELD_STORE, List.of(receiver, value), List.of(), attributes, emitted);
            }
            default -> throw new LiftException(-1, "invalid field opcode");
        }
    }

    private void liftInvoke(IrMethod method, BasicBlock block, MethodInsnNode node,
                            MutableFrame frame, List<IrInstruction> emitted) {
        Type methodType = Type.getMethodType(node.desc);
        List<Value> arguments = frame.popArguments(methodType.getArgumentTypes().length);
        Value receiver = null;
        if (node.getOpcode() != Opcodes.INVOKESTATIC) receiver = frame.pop();
        List<Value> operands = new ArrayList<>();
        if (receiver != null) operands.add(receiver);
        operands.addAll(arguments);
        Map<String, IrAttribute> attributes = memberAttributes(node.owner, node.name, node.desc);
        attributes.put("invoke_kind", IrAttribute.of(opcodeName(node.getOpcode())));
        attributes.put("interface", IrAttribute.of(node.itf));
        if (node.getOpcode() == Opcodes.INVOKESPECIAL && node.name.equals("<init>")) {
            if (receiver == null) throw new LiftException(-1, "constructor invocation has no receiver");
            IrType initialized = initializedType(method, receiver, node.owner);
            Value result = emitResult(method, block, node, CoreOps.INITIALIZE, operands, initialized, attributes, emitted);
            frame.replaceAliases(receiver, result);
            return;
        }
        IrType returnType = JvmTypeAdapter.fromAsm(methodType.getReturnType());
        List<IrType> results = returnType == PrimitiveType.VOID ? List.of() : List.of(returnType);
        IrInstruction invoke = emit(method, block, node, CoreOps.INVOKE, operands, results, attributes, emitted);
        if (!results.isEmpty()) frame.push(invoke.result());
    }

    private void liftInvokeDynamic(IrMethod method, BasicBlock block, InvokeDynamicInsnNode node,
                                   MutableFrame frame, List<IrInstruction> emitted) {
        Type type = Type.getMethodType(node.desc);
        List<Value> arguments = frame.popArguments(type.getArgumentTypes().length);
        Map<String, IrAttribute> attributes = new LinkedHashMap<>(
                JvmBootstrapAttributes.dynamicCallSite(node.name, node.desc, node.bsm, node.bsmArgs));
        IrType returnType = JvmTypeAdapter.fromAsm(type.getReturnType());
        List<IrType> results = returnType == PrimitiveType.VOID ? List.of() : List.of(returnType);
        IrInstruction invoke = emit(method, block, node, CoreOps.INVOKE_DYNAMIC, arguments, results, attributes, emitted);
        if (!results.isEmpty()) frame.push(invoke.result());
    }

    private void liftStackPermutation(IrMethod method, BasicBlock block, AbstractInsnNode node,
                                      MutableFrame frame, List<IrInstruction> emitted) {
        List<Value> touched = new ArrayList<>();
        switch (node.getOpcode()) {
            case Opcodes.POP -> touched.add(frame.popCategory1());
            case Opcodes.POP2 -> {
                Value first = frame.pop(); touched.add(first);
                if (!first.type().isCategory2()) touched.add(frame.popCategory1());
            }
            case Opcodes.DUP -> {
                Value a = frame.popCategory1(); touched.add(a); frame.push(a); frame.push(a);
            }
            case Opcodes.DUP_X1 -> {
                Value a = frame.popCategory1(), b = frame.popCategory1();
                Collections.addAll(touched, b, a); frame.push(a); frame.push(b); frame.push(a);
            }
            case Opcodes.DUP_X2 -> {
                Value a = frame.popCategory1(), b = frame.pop(); touched.add(b); touched.add(a);
                if (b.type().isCategory2()) { frame.push(a); frame.push(b); frame.push(a); }
                else { Value c = frame.popCategory1(); touched.addFirst(c); frame.push(a); frame.push(c); frame.push(b); frame.push(a); }
            }
            case Opcodes.DUP2 -> {
                Value a = frame.pop(); touched.add(a);
                if (a.type().isCategory2()) { frame.push(a); frame.push(a); }
                else { Value b = frame.popCategory1(); touched.addFirst(b); frame.push(b); frame.push(a); frame.push(b); frame.push(a); }
            }
            case Opcodes.DUP2_X1 -> {
                Value a = frame.pop();
                if (a.type().isCategory2()) {
                    Value b = frame.popCategory1(); Collections.addAll(touched, b, a);
                    frame.push(a); frame.push(b); frame.push(a);
                } else {
                    Value b = frame.popCategory1(), c = frame.popCategory1(); Collections.addAll(touched, c, b, a);
                    frame.push(b); frame.push(a); frame.push(c); frame.push(b); frame.push(a);
                }
            }
            case Opcodes.DUP2_X2 -> duplicateTwoUnderTwo(frame, touched);
            case Opcodes.SWAP -> {
                Value a = frame.popCategory1(), b = frame.popCategory1(); Collections.addAll(touched, b, a);
                frame.push(a); frame.push(b);
            }
            default -> throw new LiftException(-1, "not a stack permutation");
        }
        emit(method, block, node, CoreOps.STACK_PERMUTE, touched, List.of(),
                Map.of("kind", IrAttribute.of(opcodeName(node.getOpcode()))), emitted);
    }

    private void duplicateTwoUnderTwo(MutableFrame frame, List<Value> touched) {
        Value a = frame.pop();
        if (a.type().isCategory2()) {
            Value b = frame.pop();
            if (b.type().isCategory2()) {
                Collections.addAll(touched, b, a); frame.push(a); frame.push(b); frame.push(a);
            } else {
                Value c = frame.popCategory1(); Collections.addAll(touched, c, b, a);
                frame.push(a); frame.push(c); frame.push(b); frame.push(a);
            }
        } else {
            Value b = frame.popCategory1();
            Value c = frame.pop();
            if (c.type().isCategory2()) {
                Collections.addAll(touched, c, b, a); frame.push(b); frame.push(a); frame.push(c); frame.push(b); frame.push(a);
            } else {
                Value d = frame.popCategory1(); Collections.addAll(touched, d, c, b, a);
                frame.push(b); frame.push(a); frame.push(d); frame.push(c); frame.push(b); frame.push(a);
            }
        }
    }

    private IrInstruction emit(IrMethod method, BasicBlock block, AbstractInsnNode source,
                               OperationCode code, List<Value> operands, List<IrType> results,
                               Map<String, IrAttribute> attributes, List<IrInstruction> emitted) {
        IrInstruction instruction = method.createInstruction(new Operation(code, attributes), operands, results);
        block.append(instruction);
        emitted.add(instruction);
        return instruction;
    }

    private Value emitResult(IrMethod method, BasicBlock block, AbstractInsnNode source,
                             OperationCode code, List<Value> operands, IrType result,
                             Map<String, IrAttribute> attributes, List<IrInstruction> emitted) {
        return emit(method, block, source, code, operands, List.of(result), attributes, emitted).result();
    }

    private IrType verifierType(BasicValue value) {
        if (value == null || value == BasicValue.UNINITIALIZED_VALUE) return SpecialType.TOP;
        if (value == BasicValue.RETURNADDRESS_VALUE) return SpecialType.RETURN_ADDRESS;
        Type type = value.getType();
        if (type == null) return SpecialType.TOP;
        if (type.getSort() == Type.OBJECT && type.getInternalName().equals("null")) return SpecialType.NULL;
        return JvmTypeAdapter.fromAsm(type);
    }

    private IrType numericType(int opcode) {
        return switch (opcode) {
            case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LNEG,
                    Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> PrimitiveType.LONG;
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG -> PrimitiveType.FLOAT;
            case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM, Opcodes.DNEG -> PrimitiveType.DOUBLE;
            default -> PrimitiveType.INT;
        };
    }

    private IrType conversionType(int opcode) {
        return switch (opcode) {
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> PrimitiveType.LONG;
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> PrimitiveType.FLOAT;
            case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> PrimitiveType.DOUBLE;
            case Opcodes.I2B -> PrimitiveType.BYTE;
            case Opcodes.I2C -> PrimitiveType.CHAR;
            case Opcodes.I2S -> PrimitiveType.SHORT;
            default -> PrimitiveType.INT;
        };
    }

    private IrType arrayLoadType(int opcode, IrType arrayType) {
        return switch (opcode) {
            case Opcodes.LALOAD -> PrimitiveType.LONG;
            case Opcodes.FALOAD -> PrimitiveType.FLOAT;
            case Opcodes.DALOAD -> PrimitiveType.DOUBLE;
            case Opcodes.AALOAD -> arrayType instanceof ArrayType array
                    ? array.dimensions() == 1 ? array.elementType()
                    : new ArrayType(array.elementType(), array.dimensions() - 1, Nullability.UNKNOWN)
                    : ReferenceType.OBJECT;
            default -> PrimitiveType.INT;
        };
    }

    private IrType initializedType(IrMethod method, Value receiver, String invokedOwner) {
        if (receiver.type() == SpecialType.UNINITIALIZED_THIS) {
            return new ReferenceType(method.signature().owner(), Nullability.NON_NULL);
        }
        if (receiver.type() instanceof UninitializedType uninitialized) {
            return uninitialized.initializedType().withNullability(Nullability.NON_NULL);
        }
        return new ReferenceType(invokedOwner, Nullability.NON_NULL);
    }

    private IrType primitiveArrayElement(int operand) {
        return switch (operand) {
            case Opcodes.T_BOOLEAN -> PrimitiveType.BOOLEAN;
            case Opcodes.T_CHAR -> PrimitiveType.CHAR;
            case Opcodes.T_FLOAT -> PrimitiveType.FLOAT;
            case Opcodes.T_DOUBLE -> PrimitiveType.DOUBLE;
            case Opcodes.T_BYTE -> PrimitiveType.BYTE;
            case Opcodes.T_SHORT -> PrimitiveType.SHORT;
            case Opcodes.T_INT -> PrimitiveType.INT;
            case Opcodes.T_LONG -> PrimitiveType.LONG;
            default -> throw new LiftException(-1, "invalid NEWARRAY element code " + operand);
        };
    }

    private ArrayType arrayType(String componentDescriptor) {
        Type component = componentDescriptor.startsWith("[") ? Type.getType(componentDescriptor) : Type.getObjectType(componentDescriptor);
        IrType componentType = JvmTypeAdapter.fromAsm(component);
        if (componentType instanceof ArrayType array) {
            return new ArrayType(array.elementType(), array.dimensions() + 1, Nullability.NON_NULL);
        }
        return new ArrayType(componentType, 1, Nullability.NON_NULL);
    }

    private IrType referenceOrArray(String descriptor, Nullability nullability) {
        if (descriptor.startsWith("[")) {
            return ((ArrayType) JvmTypeAdapter.fromAsm(Type.getType(descriptor))).withNullability(nullability);
        }
        return new ReferenceType(descriptor, nullability);
    }

    private Map<String, IrAttribute> memberAttributes(String owner, String name, String descriptor) {
        Map<String, IrAttribute> attributes = new LinkedHashMap<>();
        attributes.put("owner", IrAttribute.of(owner));
        attributes.put("name", IrAttribute.of(name));
        attributes.put("descriptor", IrAttribute.of(descriptor));
        return attributes;
    }

    private String bootstrapArguments(ConstantDynamic dynamic) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
            values.add(String.valueOf(dynamic.getBootstrapMethodArgument(index)));
        }
        return values.toString();
    }

    private boolean canThrow(AbstractInsnNode node) {
        int opcode = node.getOpcode();
        if (node instanceof FieldInsnNode || node instanceof MethodInsnNode || node instanceof InvokeDynamicInsnNode
                || node instanceof MultiANewArrayInsnNode || node instanceof TypeInsnNode) return true;
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

    private String opcodeName(int opcode) {
        return opcode >= 0 && opcode < Printer.OPCODES.length && Printer.OPCODES[opcode] != null
                ? Printer.OPCODES[opcode] : "OP_" + opcode;
    }

    private BytecodeImportResult fallback(String owner, MethodNode source, BytecodeImportOptions options,
                                          List<Diagnostic> prior, Diagnostic extra) {
        BytecodeImportResult clean = new BytecodeCfgImporter(context).importMethod(owner, source, options);
        List<Diagnostic> diagnostics = new ArrayList<>(clean.diagnostics());
        prior.stream().filter(diagnostic -> !diagnostics.contains(diagnostic)).forEach(diagnostics::add);
        diagnostics.add(extra);
        return new BytecodeImportResult(clean.method(), clean.sourceMap(), clean.preservedSnapshot(),
                clean.importedRevision(), clean.capabilities(), diagnostics, null);
    }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity() == Diagnostic.Severity.ERROR
                || value.severity() == Diagnostic.Severity.FATAL);
    }

    private Map<AbstractInsnNode, Long> lineNumbers(AbstractInsnNode[] nodes) {
        Map<AbstractInsnNode, Long> result = new IdentityHashMap<>();
        long current = -1;
        for (AbstractInsnNode node : nodes) {
            if (node instanceof LineNumberNode line) current = line.line;
            else if (node.getOpcode() >= 0 && current >= 0) result.put(node, current);
        }
        return result;
    }

    private record BlockLayout(int start, int end, int firstExecutable) {}
    private record PhiLayout(MutableFrame frame, Map<Integer, PhiNode> localPhis,
                             Map<Integer, PhiNode> stackPhis) {}
    private record Slot(IrType type, Value value) {
        private static final Slot TOP = new Slot(SpecialType.TOP, null);
    }

    private static final class MutableFrame {
        private final List<Slot> locals;
        private final List<Value> stack;

        MutableFrame(int localCount) {
            locals = new ArrayList<>(Collections.nCopies(localCount, Slot.TOP));
            stack = new ArrayList<>();
        }

        private MutableFrame(List<Slot> locals, List<Value> stack) {
            this.locals = new ArrayList<>(locals);
            this.stack = new ArrayList<>(stack);
        }

        MutableFrame copy() { return new MutableFrame(locals, stack); }
        Slot local(int index) { return index < locals.size() ? locals.get(index) : Slot.TOP; }
        void setRawLocal(int index, Slot value) { ensureLocal(index); locals.set(index, value); }
        Value readLocal(int index) {
            Slot slot = local(index);
            if (slot.value == null) throw new LiftException(-1, "read of TOP local " + index);
            return slot.value;
        }
        void writeLocal(int index, Value value) {
            ensureLocal(index + Math.max(1, value.type().slots()) - 1);
            if (index > 0 && locals.get(index).value == null) {
                Slot previous = locals.get(index - 1);
                if (previous.value != null && previous.type.isCategory2()) locals.set(index - 1, Slot.TOP);
            }
            Slot replaced = locals.get(index);
            locals.set(index, new Slot(value.type(), value));
            if (replaced.value != null && replaced.type.isCategory2() && index + 1 < locals.size()) locals.set(index + 1, Slot.TOP);
            if (value.type().isCategory2()) locals.set(index + 1, Slot.TOP);
        }
        void push(Value value) { stack.add(Objects.requireNonNull(value)); }
        Value pop() {
            if (stack.isEmpty()) throw new LiftException(-1, "operand stack underflow");
            return stack.removeLast();
        }
        Value popCategory1() {
            Value value = pop();
            if (value.type().isCategory2()) throw new LiftException(-1, "expected category-1 stack value");
            return value;
        }
        List<Value> popArguments(int count) {
            List<Value> arguments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) arguments.addFirst(pop());
            return arguments;
        }
        void replaceAliases(Value oldValue, Value replacement) {
            for (int index = 0; index < locals.size(); index++) {
                Slot slot = locals.get(index);
                if (sameInitializationIdentity(slot.value, oldValue)) {
                    locals.set(index, new Slot(replacement.type(), replacement));
                }
            }
            for (int index = 0; index < stack.size(); index++) {
                if (sameInitializationIdentity(stack.get(index), oldValue)) stack.set(index, replacement);
            }
        }
        JvmFrameState view() {
            return new JvmFrameState(locals.stream().map(slot -> new JvmFrameState.Slot(slot.type, slot.value)).toList(), stack);
        }
        private void ensureLocal(int index) { while (locals.size() <= index) locals.add(Slot.TOP); }

        private boolean sameInitializationIdentity(Value candidate, Value initialized) {
            if (candidate == null) return false;
            if (candidate == initialized) return true;
            if (candidate.type() == SpecialType.UNINITIALIZED_THIS
                    && initialized.type() == SpecialType.UNINITIALIZED_THIS) return true;
            return candidate.type() instanceof UninitializedType left
                    && initialized.type() instanceof UninitializedType right && left.equals(right);
        }
    }

    private static final class LiftException extends RuntimeException {
        private final int position;
        private LiftException(int position, String message) { super(message); this.position = position; }
    }
}
