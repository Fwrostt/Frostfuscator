package dev.frost.obfuscator.jni.desugar;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites LambdaMetafactory invokedynamic sites into small synthetic classes.
 */
final class LambdaMetafactoryDesugarer {
    private static final String OBJECT = "java/lang/Object";

    DesugarResult desugar(ClassNode owner, MethodNode method, InvokeDynamicInsnNode dynamicInsn, int lambdaIndex) {
        if (!(dynamicInsn.bsmArgs.length >= 3
                && dynamicInsn.bsmArgs[0] instanceof Type samMethodType
                && dynamicInsn.bsmArgs[1] instanceof Handle implementation
                && dynamicInsn.bsmArgs[2] instanceof Type instantiatedMethodType)) {
            return DesugarResult.unsupported();
        }

        Type dynamicType = Type.getMethodType(dynamicInsn.desc);
        Type[] captures = dynamicType.getArgumentTypes();
        Type functionalInterface = dynamicType.getReturnType();
        if (functionalInterface.getSort() != Type.OBJECT || !isSupportedHandle(implementation)) {
            return DesugarResult.unsupported();
        }

        String generatedName = owner.name + "$$FrostLambda" + lambdaIndex;
        ClassNode lambdaClass = generateLambdaClass(
                owner,
                generatedName,
                functionalInterface.getInternalName(),
                dynamicInsn.name,
                samMethodType,
                instantiatedMethodType,
                implementation,
                captures
        );

        InsnList replacement = instantiateLambda(method, generatedName, captures);
        method.instructions.insertBefore(dynamicInsn, replacement);
        method.instructions.remove(dynamicInsn);
        relaxTargetAccess(owner, implementation);
        return DesugarResult.generated(lambdaClass);
    }

    private ClassNode generateLambdaClass(
            ClassNode owner,
            String generatedName,
            String interfaceName,
            String samName,
            Type samMethodType,
            Type instantiatedMethodType,
            Handle implementation,
            Type[] captures
    ) {
        ClassNode node = new ClassNode();
        node.version = owner.version;
        node.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        node.name = generatedName;
        node.superName = OBJECT;
        node.interfaces = new ArrayList<>(List.of(interfaceName));

        for (int i = 0; i < captures.length; i++) {
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    captureName(i),
                    captures[i].getDescriptor(),
                    null,
                    null
            ));
        }

        node.methods.add(constructor(generatedName, captures));
        node.methods.add(samMethod(generatedName, samName, samMethodType, instantiatedMethodType, implementation, captures));
        return node;
    }

    private MethodNode constructor(String generatedName, Type[] captures) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "<init>",
                constructorDescriptor(captures),
                null,
                null
        );
        InsnList instructions = method.instructions;
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OBJECT, "<init>", "()V", false));
        int local = 1;
        for (int i = 0; i < captures.length; i++) {
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.add(new VarInsnNode(loadOpcode(captures[i]), local));
            instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, generatedName, captureName(i), captures[i].getDescriptor()));
            local += captures[i].getSize();
        }
        instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = local;
        method.maxStack = Math.max(2, captures.length + 2);
        return method;
    }

    private MethodNode samMethod(
            String generatedName,
            String samName,
            Type samMethodType,
            Type instantiatedMethodType,
            Handle implementation,
            Type[] captures
    ) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                samName,
                samMethodType.getDescriptor(),
                null,
                null
        );
        InvocationPlan plan = invocationPlan(implementation, captures, instantiatedMethodType.getArgumentTypes());
        InsnList instructions = method.instructions;
        for (ValueSource source : plan.sources()) {
            emitValue(instructions, generatedName, source, captures, samMethodType.getArgumentTypes(), instantiatedMethodType.getArgumentTypes());
        }
        emitInvocation(instructions, implementation);
        adaptReturn(instructions, Type.getReturnType(implementation.getDesc()), samMethodType.getReturnType());
        method.maxLocals = 1 + localWidth(samMethodType.getArgumentTypes());
        method.maxStack = 8 + captures.length + samMethodType.getArgumentTypes().length;
        return method;
    }

    private InvocationPlan invocationPlan(Handle implementation, Type[] captures, Type[] instantiatedArguments) {
        List<ValueSource> sources = new ArrayList<>();
        int captureIndex = 0;
        int samIndex = 0;

        if (implementation.getTag() == Opcodes.H_INVOKEVIRTUAL
                || implementation.getTag() == Opcodes.H_INVOKEINTERFACE
                || implementation.getTag() == Opcodes.H_INVOKESPECIAL) {
            if (captures.length > 0 && implementation.getOwner().equals(internalName(captures[0]))) {
                sources.add(ValueSource.capture(captureIndex++));
            } else {
                sources.add(ValueSource.sam(samIndex++));
            }
        }

        Type[] implementationArguments = Type.getArgumentTypes(implementation.getDesc());
        for (int i = 0; i < implementationArguments.length; i++) {
            if (captureIndex < captures.length) {
                sources.add(ValueSource.capture(captureIndex++));
            } else {
                sources.add(ValueSource.sam(samIndex++));
            }
        }
        return new InvocationPlan(sources);
    }

    private void emitValue(
            InsnList instructions,
            String generatedName,
            ValueSource source,
            Type[] captures,
            Type[] samArguments,
            Type[] instantiatedArguments
    ) {
        Type actualType;
        if (source.capture()) {
            actualType = captures[source.index()];
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.add(new FieldInsnNode(Opcodes.GETFIELD, generatedName, captureName(source.index()), actualType.getDescriptor()));
        } else {
            actualType = samArguments[source.index()];
            instructions.add(new VarInsnNode(loadOpcode(actualType), samLocal(samArguments, source.index())));
            Type instantiatedType = source.index() < instantiatedArguments.length ? instantiatedArguments[source.index()] : actualType;
            castIfNeeded(instructions, actualType, instantiatedType);
        }
    }

    private void emitInvocation(InsnList instructions, Handle implementation) {
        switch (implementation.getTag()) {
            case Opcodes.H_INVOKESTATIC -> instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    implementation.getOwner(),
                    implementation.getName(),
                    implementation.getDesc(),
                    false
            ));
            case Opcodes.H_INVOKEVIRTUAL -> instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    implementation.getOwner(),
                    implementation.getName(),
                    implementation.getDesc(),
                    false
            ));
            case Opcodes.H_INVOKEINTERFACE -> instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    implementation.getOwner(),
                    implementation.getName(),
                    implementation.getDesc(),
                    true
            ));
            case Opcodes.H_INVOKESPECIAL -> instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    implementation.getOwner(),
                    implementation.getName(),
                    implementation.getDesc(),
                    false
            ));
            default -> throw new IllegalArgumentException("Unsupported lambda implementation handle tag " + implementation.getTag());
        }
    }

    private InsnList instantiateLambda(MethodNode method, String generatedName, Type[] captures) {
        InsnList instructions = new InsnList();
        int[] locals = reserveCaptureLocals(method, captures);
        for (int i = captures.length - 1; i >= 0; i--) {
            instructions.add(new VarInsnNode(storeOpcode(captures[i]), locals[i]));
        }
        instructions.add(new TypeInsnNode(Opcodes.NEW, generatedName));
        instructions.add(new InsnNode(Opcodes.DUP));
        for (int i = 0; i < captures.length; i++) {
            instructions.add(new VarInsnNode(loadOpcode(captures[i]), locals[i]));
        }
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, generatedName, "<init>", constructorDescriptor(captures), false));
        return instructions;
    }

    private int[] reserveCaptureLocals(MethodNode method, Type[] captures) {
        int[] locals = new int[captures.length];
        int next = method.maxLocals;
        for (int i = 0; i < captures.length; i++) {
            locals[i] = next;
            next += captures[i].getSize();
        }
        method.maxLocals = Math.max(method.maxLocals, next);
        return locals;
    }

    private void adaptReturn(InsnList instructions, Type actual, Type expected) {
        if (expected.getSort() == Type.VOID) {
            if (actual.getSort() != Type.VOID) {
                instructions.add(new InsnNode(actual.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
            }
            instructions.add(new InsnNode(Opcodes.RETURN));
            return;
        }
        boxIfNeeded(instructions, actual, expected);
        castIfNeeded(instructions, actual, expected);
        instructions.add(new InsnNode(returnOpcode(expected)));
    }

    private void castIfNeeded(InsnList instructions, Type actual, Type expected) {
        if ((actual.getSort() == Type.OBJECT || actual.getSort() == Type.ARRAY)
                && (expected.getSort() == Type.OBJECT || expected.getSort() == Type.ARRAY)
                && !actual.getDescriptor().equals(expected.getDescriptor())) {
            instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, expected.getInternalName()));
        }
    }

    private void boxIfNeeded(InsnList instructions, Type actual, Type expected) {
        if (actual.getSort() == Type.VOID || expected.getSort() != Type.OBJECT || !OBJECT.equals(expected.getInternalName())) {
            return;
        }
        switch (actual.getSort()) {
            case Type.BOOLEAN -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.LONG -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.FLOAT -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.DOUBLE -> instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
            }
        }
    }

    private void relaxTargetAccess(ClassNode owner, Handle implementation) {
        if (!owner.name.equals(implementation.getOwner()) || !implementation.getName().startsWith("lambda$")) {
            return;
        }
        for (MethodNode method : owner.methods) {
            if (method.name.equals(implementation.getName()) && method.desc.equals(implementation.getDesc())) {
                method.access &= ~Opcodes.ACC_PRIVATE;
                method.access &= ~Opcodes.ACC_SYNTHETIC;
                method.access |= Opcodes.ACC_SYNTHETIC;
            }
        }
    }

    private int loadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    private int storeOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    private int returnOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    private String constructorDescriptor(Type[] captures) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Type capture : captures) {
            descriptor.append(capture.getDescriptor());
        }
        return descriptor.append(")V").toString();
    }

    private int localWidth(Type[] types) {
        int width = 0;
        for (Type type : types) {
            width += type.getSize();
        }
        return width;
    }

    private int samLocal(Type[] samArguments, int argumentIndex) {
        int local = 1;
        for (int i = 0; i < argumentIndex; i++) {
            local += samArguments[i].getSize();
        }
        return local;
    }

    private String captureName(int index) {
        return "capture" + index;
    }

    private String internalName(Type type) {
        return type.getSort() == Type.OBJECT ? type.getInternalName() : type.getDescriptor();
    }

    private boolean isSupportedHandle(Handle implementation) {
        return switch (implementation.getTag()) {
            case Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL -> true;
            default -> false;
        };
    }

    private record InvocationPlan(List<ValueSource> sources) {
    }

    private record ValueSource(boolean capture, int index) {
        static ValueSource capture(int index) {
            return new ValueSource(true, index);
        }

        static ValueSource sam(int index) {
            return new ValueSource(false, index);
        }
    }

    record DesugarResult(ClassNode generatedClass, boolean supported) {
        static DesugarResult generated(ClassNode generatedClass) {
            return new DesugarResult(generatedClass, true);
        }

        static DesugarResult unsupported() {
            return new DesugarResult(null, false);
        }
    }
}
