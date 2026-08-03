package dev.frost.ir.snapshot;

import dev.frost.ir.core.IrId;
import dev.frost.ir.model.EdgeKind;
import dev.frost.ir.model.MethodSignature;
import dev.frost.ir.model.Operation;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.ReferenceType;
import java.util.List;
import java.util.Map;

/** Immutable, identity-free method view for plugins, caches, diagnostics, and serialization. */
public record FrozenMethod(MethodSignature signature, long sourceRevision, IrId entryBlock,
                           List<Parameter> parameters, List<Block> blocks, List<Edge> edges,
                           List<ExceptionRegionView> exceptionRegions, List<ValueView> values,
                           Map<String, String> metadata) {
    public FrozenMethod {
        parameters = List.copyOf(parameters);
        blocks = List.copyOf(blocks);
        edges = List.copyOf(edges);
        exceptionRegions = List.copyOf(exceptionRegions);
        values = List.copyOf(values);
        metadata = Map.copyOf(metadata);
    }

    public record Parameter(IrId id, int index, String name, IrId value) {}
    public record Block(IrId id, String name, List<Phi> phis, List<Instruction> instructions,
                        Map<String, String> metadata) {
        public Block { phis = List.copyOf(phis); instructions = List.copyOf(instructions); metadata = Map.copyOf(metadata); }
    }
    public record Phi(IrId id, IrId result, List<PhiInput> inputs, Map<String, String> metadata) {
        public Phi { inputs = List.copyOf(inputs); metadata = Map.copyOf(metadata); }
    }
    public record PhiInput(IrId edge, IrId value) {}
    public record Instruction(IrId id, Operation operation, List<IrId> operands, List<IrId> results,
                              Map<String, String> metadata) {
        public Instruction { operands = List.copyOf(operands); results = List.copyOf(results); metadata = Map.copyOf(metadata); }
    }
    public record Edge(IrId id, IrId source, IrId target, EdgeKind kind, String label,
                       ReferenceType catchType, int priority, List<EdgeValueView> values,
                       Map<String, String> metadata) {
        public Edge { values = List.copyOf(values); metadata = Map.copyOf(metadata); }
    }
    public record EdgeValueView(IrId id, IrId result, String role, Map<String, String> metadata) {
        public EdgeValueView { metadata = Map.copyOf(metadata); }
    }
    public record ExceptionRegionView(IrId id, List<IrId> protectedBlocks, IrId handler,
                                      ReferenceType catchType, int priority, Map<String, String> metadata) {
        public ExceptionRegionView { protectedBlocks = List.copyOf(protectedBlocks); metadata = Map.copyOf(metadata); }
    }
    public record ValueView(IrId id, IrType type, IrId definition, int resultIndex, String debugName,
                            List<UseView> uses, Map<String, String> metadata) {
        public ValueView { uses = List.copyOf(uses); metadata = Map.copyOf(metadata); }
    }
    public record UseView(IrId user, int index) {}
}
