package dev.frost.ir.analysis;

import dev.frost.ir.model.ControlEdge;
import java.util.function.Predicate;

public enum EdgePolicy implements Predicate<ControlEdge> {
    NORMAL_ONLY {
        @Override public boolean test(ControlEdge edge) { return !edge.kind().isExceptional(); }
    },
    NORMAL_AND_EXCEPTIONAL {
        @Override public boolean test(ControlEdge edge) { return true; }
    },
    EXCEPTIONAL_ONLY {
        @Override public boolean test(ControlEdge edge) { return edge.kind().isExceptional(); }
    }
}
