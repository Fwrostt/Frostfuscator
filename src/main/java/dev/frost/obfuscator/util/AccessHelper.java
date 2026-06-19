package dev.frost.obfuscator.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class AccessHelper {

    private AccessHelper() {
    }

    public static boolean isPublic(int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    public static boolean isPrivate(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    public static boolean isProtected(int access) {
        return (access & Opcodes.ACC_PROTECTED) != 0;
    }

    public static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public static boolean isFinal(int access) {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    public static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    public static boolean isBridge(int access) {
        return (access & Opcodes.ACC_BRIDGE) != 0;
    }

    public static boolean isNative(int access) {
        return (access & Opcodes.ACC_NATIVE) != 0;
    }

    public static boolean isAbstract(int access) {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public static boolean isInterface(int access) {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public static boolean isEnum(int access) {
        return (access & Opcodes.ACC_ENUM) != 0;
    }

    public static boolean isAnnotation(int access) {
        return (access & Opcodes.ACC_ANNOTATION) != 0;
    }

    public static boolean isInterface(ClassNode node) {
        return isInterface(node.access);
    }

    public static boolean isEnum(ClassNode node) {
        return isEnum(node.access);
    }

    public static boolean isInitializer(MethodNode method) {
        return method.name.equals("<init>") || method.name.equals("<clinit>");
    }

    public static boolean isMainMethod(MethodNode method) {
        return method.name.equals("main")
                && method.desc.equals("([Ljava/lang/String;)V")
                && isPublic(method.access)
                && isStatic(method.access);
    }

    public static boolean isSerialVersionUID(FieldNode field) {
        return field.name.equals("serialVersionUID")
                && field.desc.equals("J")
                && isStatic(field.access)
                && isFinal(field.access);
    }

    public static boolean isEnumMethod(MethodNode method, ClassNode owner) {
        if (!isEnum(owner)) return false;
        if (method.name.equals("values") && method.desc.equals("()[L" + owner.name + ";")) return true;
        if (method.name.equals("valueOf") && method.desc.equals("(Ljava/lang/String;)L" + owner.name + ";"))
            return true;
        return false;
    }
}
