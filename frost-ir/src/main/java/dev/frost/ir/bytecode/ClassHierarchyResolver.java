package dev.frost.ir.bytecode;

import java.util.Objects;

/** Linkage-free hierarchy service used during stack-map reconstruction. */
public interface ClassHierarchyResolver {
    String commonSuperClass(String leftInternalName, String rightInternalName);

    static ClassHierarchyResolver conservative() {
        return (left, right) -> Objects.equals(left, right) ? left : "java/lang/Object";
    }
}
