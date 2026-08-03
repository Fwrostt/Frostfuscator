package dev.frost.ir.text;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.PhiNode;
import dev.frost.ir.model.Value;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/** Canonical human-readable form intended for golden tests and pass debugging. */
public final class IrTextPrinter {
    public String print(IrMethod method) {
        Objects.requireNonNull(method, "method");
        StringBuilder out = new StringBuilder();
        out.append("func @").append(method.signature().owner()).append('.').append(method.signature().name())
                .append(method.signature().type().displayName()).append(" revision ").append(method.revision()).append(" {\n");
        if (!method.parameters().isEmpty()) {
            out.append("  params ");
            StringJoiner params = new StringJoiner(", ");
            method.parameters().forEach(parameter -> params.add(value(parameter.value()) + ":" + parameter.value().type().displayName()));
            out.append(params).append('\n');
        }
        for (BasicBlock block : method.blocks()) {
            out.append(block == method.entryBlock().orElse(null) ? " entry " : " ")
                    .append('^').append(block.name()).append(" [").append(block.id()).append("]:\n");
            for (PhiNode phi : block.phis()) {
                out.append("    ").append(value(phi.result())).append(':').append(phi.result().type().displayName()).append(" = phi ");
                StringJoiner inputs = new StringJoiner(", ");
                phi.inputs().forEach((edge, incoming) -> inputs.add("[e" + edge.id() + ": " + value(incoming) + "]"));
                out.append(inputs).append('\n');
            }
            for (IrInstruction instruction : block.instructions()) {
                out.append("    ");
                if (!instruction.results().isEmpty()) {
                    StringJoiner results = new StringJoiner(", ");
                    instruction.results().forEach(result -> results.add(value(result) + ":" + result.type().displayName()));
                    out.append(results).append(" = ");
                }
                out.append(instruction.operation().code().qualifiedName());
                if (!instruction.operands().isEmpty()) {
                    StringJoiner operands = new StringJoiner(", ", " ", "");
                    instruction.operands().forEach(operand -> operands.add(value(operand)));
                    out.append(operands);
                }
                if (!instruction.operation().attributes().isEmpty()) out.append(' ').append(attributes(instruction.operation().attributes()));
                out.append("  ; i").append(instruction.id()).append('\n');
            }
            for (ControlEdge edge : block.outgoingEdges()) {
                out.append("      -> e").append(edge.id()).append(' ').append(edge.kind().name().toLowerCase())
                        .append(" ^").append(edge.target().name());
                if (!edge.label().isEmpty()) out.append(" [").append(edge.label()).append(']');
                edge.catchType().ifPresent(type -> out.append(" catch ").append(type.displayName()));
                out.append('\n');
            }
        }
        return out.append("}\n").toString();
    }

    private String value(Value value) { return "%" + (value.debugName() == null ? value.id() : value.debugName()); }

    private String attributes(Map<String, IrAttribute> attributes) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        attributes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "=" + entry.getValue()));
        return joiner.toString();
    }
}
