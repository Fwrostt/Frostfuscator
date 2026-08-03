package dev.frost.ir.bytecode;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

final class AsmMethodCloner {
    private AsmMethodCloner() {}

    static MethodNode clone(MethodNode source) {
        List<String> exceptions = source.exceptions == null ? List.of() : source.exceptions;
        MethodNode copy = new MethodNode(Opcodes.ASM9, source.access, source.name, source.desc,
                source.signature, exceptions.toArray(String[]::new));
        source.accept(copy);
        return copy;
    }
}
