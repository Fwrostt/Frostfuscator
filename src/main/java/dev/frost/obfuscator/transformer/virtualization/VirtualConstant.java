package dev.frost.obfuscator.transformer.virtualization;

import java.util.Objects;

public sealed interface VirtualConstant permits VirtualConstant.ClassRef, VirtualConstant.FieldRef, VirtualConstant.MethodRef {

    record ClassRef(String name) implements VirtualConstant {
        public ClassRef {
            Objects.requireNonNull(name, "name");
        }
    }

    record FieldRef(String className, String name, String desc) implements VirtualConstant {
        public FieldRef {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(desc, "desc");
        }
    }

    record MethodRef(String className, String name, String desc, boolean isInterface) implements VirtualConstant {
        public MethodRef {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(desc, "desc");
        }
    }
}
