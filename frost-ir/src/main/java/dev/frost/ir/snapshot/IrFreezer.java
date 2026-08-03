package dev.frost.ir.snapshot;

import dev.frost.ir.core.MetadataKey;
import dev.frost.ir.core.MetadataMap;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ExceptionRegion;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IrFreezer {
    public FrozenMethod freeze(IrMethod method) {
        Objects.requireNonNull(method, "method");
        List<FrozenMethod.Parameter> parameters = method.parameters().stream()
                .map(parameter -> new FrozenMethod.Parameter(parameter.id(), parameter.index(), parameter.name(), parameter.value().id()))
                .toList();
        List<FrozenMethod.Block> blocks = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        method.parameters().forEach(parameter -> values.add(parameter.value()));
        for (BasicBlock block : method.blocks()) {
            List<FrozenMethod.Phi> phis = new ArrayList<>();
            for (PhiNode phi : block.phis()) {
                List<FrozenMethod.PhiInput> inputs = phi.inputs().entrySet().stream()
                        .map(entry -> new FrozenMethod.PhiInput(entry.getKey().id(), entry.getValue().id())).toList();
                phis.add(new FrozenMethod.Phi(phi.id(), phi.result().id(), inputs, metadata(phi.metadata())));
                values.add(phi.result());
            }
            List<FrozenMethod.Instruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                instructions.add(new FrozenMethod.Instruction(instruction.id(), instruction.operation(),
                        instruction.operands().stream().map(Value::id).toList(),
                        instruction.results().stream().map(Value::id).toList(), metadata(instruction.metadata())));
                values.addAll(instruction.results());
            }
            blocks.add(new FrozenMethod.Block(block.id(), block.name(), phis, instructions, metadata(block.metadata())));
        }
        List<FrozenMethod.Edge> edges = method.edges().stream().map(edge -> new FrozenMethod.Edge(
                edge.id(), edge.source().id(), edge.target().id(), edge.kind(), edge.label(),
                edge.catchType().orElse(null), edge.priority(),
                edge.values().stream().map(value -> new FrozenMethod.EdgeValueView(value.id(), value.result().id(),
                        value.role(), metadata(value.metadata()))).toList(), metadata(edge.metadata()))).toList();
        method.edges().forEach(edge -> edge.values().forEach(value -> values.add(value.result())));
        List<FrozenMethod.ExceptionRegionView> regions = method.exceptionRegions().stream().map(region ->
                new FrozenMethod.ExceptionRegionView(region.id(), region.protectedBlocks().stream().map(BasicBlock::id).toList(),
                        region.handler().id(), region.catchType().orElse(null), region.priority(), metadata(region.metadata()))).toList();
        List<FrozenMethod.ValueView> valueViews = values.stream().sorted(Comparator.comparing(Value::id)).map(value ->
                new FrozenMethod.ValueView(value.id(), value.type(), value.definition().id(), value.resultIndex(), value.debugName(),
                        value.uses().stream().map(use -> new FrozenMethod.UseView(use.user().id(), use.index())).toList(),
                        metadata(value.metadata()))).toList();
        return new FrozenMethod(method.signature(), method.revision(), method.entryBlock().map(BasicBlock::id).orElse(null),
                parameters, blocks, edges, regions, valueViews, metadata(method.metadata()));
    }

    private Map<String, String> metadata(MetadataMap metadata) {
        Map<String, String> result = new LinkedHashMap<>();
        metadata.persistentView().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(MetadataKey::qualifiedName)))
                .forEach(entry -> result.put(entry.getKey().qualifiedName(), stableValue(entry.getValue())));
        return Map.copyOf(result);
    }

    private String stableValue(Object value) {
        if (value instanceof byte[] bytes) return java.util.HexFormat.of().formatHex(bytes);
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(String.valueOf(item)));
            return values.toString();
        }
        return String.valueOf(value);
    }
}
