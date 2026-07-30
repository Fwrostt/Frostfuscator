package dev.frost.graph.bytecode;

import org.objectweb.asm.Type;

/** Lightweight method entry used by graph clients without retaining ASM trees. */
public record BytecodeMethodInfo(String owner, String name, String descriptor, int access) {
    public String displayName() {
        try {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            StringBuilder value = new StringBuilder(name).append('(');
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) value.append(", ");
                value.append(arguments[i].getClassName());
            }
            return value.append(')').toString();
        } catch (IllegalArgumentException ignored) {
            return name + descriptor;
        }
    }

    public String qualifiedName() {
        return owner + "." + name + descriptor;
    }
}
