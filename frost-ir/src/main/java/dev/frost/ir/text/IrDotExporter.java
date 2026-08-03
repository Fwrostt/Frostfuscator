package dev.frost.ir.text;

import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.ControlEdge;
import dev.frost.ir.model.IrMethod;
import java.util.Objects;

public final class IrDotExporter {
    public String export(IrMethod method) {
        Objects.requireNonNull(method, "method");
        StringBuilder out = new StringBuilder("digraph frost_ir {\n  rankdir=TB;\n");
        for (BasicBlock block : method.blocks()) {
            out.append("  b").append(block.id()).append(" [shape=box,label=\"")
                    .append(escape(block.name())).append("\\nphis=").append(block.phis().size())
                    .append(" ops=").append(block.instructions().size()).append("\"];\n");
        }
        for (ControlEdge edge : method.edges()) {
            out.append("  b").append(edge.source().id()).append(" -> b").append(edge.target().id())
                    .append(" [label=\"").append(edge.kind().name().toLowerCase());
            if (!edge.label().isEmpty()) out.append(':').append(escape(edge.label()));
            out.append("\",color=").append(edge.kind().isExceptional() ? "red" : "black").append("];\n");
        }
        return out.append("}\n").toString();
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
