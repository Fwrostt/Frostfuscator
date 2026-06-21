package dev.frost.obfuscator.jni.desugar;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites java.lang.runtime.ObjectMethods bootstrap calls to ordinary bytecode.
 */
final class ObjectMethodsDesugarer {
    DesugarResult desugar(MethodNode method, InvokeDynamicInsnNode dynamicInsn) {
        ObjectMethodsPlan plan = plan(dynamicInsn);
        if (plan == null) {
            return DesugarResult.unsupported();
        }
        Type methodType = Type.getMethodType(dynamicInsn.desc);
        InsnList replacement = new InsnList();
        int[] locals = storeArguments(method, methodType.getArgumentTypes(), replacement);
        switch (dynamicInsn.name) {
            case "toString" -> emitToString(replacement, plan, locals[0]);
            case "hashCode" -> emitHashCode(replacement, plan, locals[0]);
            case "equals" -> emitEquals(replacement, plan, locals[0], locals[1]);
            default -> {
                return DesugarResult.unsupported();
            }
        }
        method.instructions.insertBefore(dynamicInsn, replacement);
        method.instructions.remove(dynamicInsn);
        return DesugarResult.supported();
    }

    private ObjectMethodsPlan plan(InvokeDynamicInsnNode dynamicInsn) {
        if (dynamicInsn.bsmArgs.length < 2
                || !(dynamicInsn.bsmArgs[0] instanceof Type ownerType)
                || !(dynamicInsn.bsmArgs[1] instanceof String fieldNames)) {
            return null;
        }
        List<String> names = fieldNames.isBlank() ? List.of() : List.of(fieldNames.split(";"));
        List<Handle> fields = new ArrayList<>();
        for (int i = 2; i < dynamicInsn.bsmArgs.length; i++) {
            if (!(dynamicInsn.bsmArgs[i] instanceof Handle handle) || handle.getTag() != Opcodes.H_GETFIELD) {
                return null;
            }
            fields.add(handle);
        }
        return new ObjectMethodsPlan(ownerType.getInternalName(), names, fields);
    }

    private int[] storeArguments(MethodNode method, Type[] arguments, InsnList stores) {
        int[] locals = new int[arguments.length];
        int next = method.maxLocals;
        for (int i = arguments.length - 1; i >= 0; i--) {
            locals[i] = next;
            next += arguments[i].getSize();
            stores.add(new VarInsnNode(storeOpcode(arguments[i]), locals[i]));
        }
        method.maxLocals = Math.max(method.maxLocals, next);
        return locals;
    }

    private void emitToString(InsnList instructions, ObjectMethodsPlan plan, int recordLocal) {
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        appendLiteral(instructions, simpleName(plan.owner()) + "[");
        for (int i = 0; i < plan.fields().size(); i++) {
            if (i > 0) {
                appendLiteral(instructions, ", ");
            }
            String name = i < plan.names().size() ? plan.names().get(i) : plan.fields().get(i).getName();
            appendLiteral(instructions, name + "=");
            instructions.add(new VarInsnNode(Opcodes.ALOAD, recordLocal));
            Handle field = plan.fields().get(i);
            instructions.add(new FieldInsnNode(Opcodes.GETFIELD, field.getOwner(), field.getName(), field.getDesc()));
            appendValue(instructions, Type.getType(field.getDesc()));
        }
        appendLiteral(instructions, "]");
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
    }

    private void emitHashCode(InsnList instructions, ObjectMethodsPlan plan, int recordLocal) {
        pushInt(instructions, plan.fields().size());
        instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < plan.fields().size(); i++) {
            instructions.add(new InsnNode(Opcodes.DUP));
            pushInt(instructions, i);
            Handle field = plan.fields().get(i);
            instructions.add(new VarInsnNode(Opcodes.ALOAD, recordLocal));
            instructions.add(new FieldInsnNode(Opcodes.GETFIELD, field.getOwner(), field.getName(), field.getDesc()));
            boxIfNeeded(instructions, Type.getType(field.getDesc()));
            instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "hash", "([Ljava/lang/Object;)I", false));
    }

    private void emitEquals(InsnList instructions, ObjectMethodsPlan plan, int recordLocal, int otherLocal) {
        LabelNode notSameReference = new LabelNode();
        LabelNode compareFields = new LabelNode();
        LabelNode falseLabel = new LabelNode();
        LabelNode end = new LabelNode();

        instructions.add(new VarInsnNode(Opcodes.ALOAD, recordLocal));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, otherLocal));
        instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notSameReference));
        instructions.add(new InsnNode(Opcodes.ICONST_1));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, end));

        instructions.add(notSameReference);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, otherLocal));
        instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, plan.owner()));
        instructions.add(new JumpInsnNode(Opcodes.IFNE, compareFields));
        instructions.add(falseLabel);
        instructions.add(new InsnNode(Opcodes.ICONST_0));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, end));

        instructions.add(compareFields);
        int castLocal = otherLocal;
        instructions.add(new VarInsnNode(Opcodes.ALOAD, otherLocal));
        instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, plan.owner()));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, castLocal));
        for (Handle field : plan.fields()) {
            instructions.add(new VarInsnNode(Opcodes.ALOAD, recordLocal));
            instructions.add(new FieldInsnNode(Opcodes.GETFIELD, field.getOwner(), field.getName(), field.getDesc()));
            boxIfNeeded(instructions, Type.getType(field.getDesc()));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, castLocal));
            instructions.add(new FieldInsnNode(Opcodes.GETFIELD, field.getOwner(), field.getName(), field.getDesc()));
            boxIfNeeded(instructions, Type.getType(field.getDesc()));
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "equals",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            instructions.add(new JumpInsnNode(Opcodes.IFEQ, falseLabel));
        }
        instructions.add(new InsnNode(Opcodes.ICONST_1));
        instructions.add(end);
    }

    private void appendLiteral(InsnList instructions, String literal) {
        instructions.add(new LdcInsnNode(literal));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
    }

    private void appendValue(InsnList instructions, Type type) {
        String descriptor = switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case Type.CHAR -> "(C)Ljava/lang/StringBuilder;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;";
            case Type.LONG -> "(J)Ljava/lang/StringBuilder;";
            case Type.FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            default -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", descriptor, false));
    }

    private void boxIfNeeded(InsnList instructions, Type type) {
        switch (type.getSort()) {
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

    private void pushInt(InsnList instructions, int value) {
        if (value >= -1 && value <= 5) {
            instructions.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            instructions.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else {
            instructions.add(new LdcInsnNode(value));
        }
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

    private String simpleName(String owner) {
        int index = owner.lastIndexOf('/');
        return index < 0 ? owner : owner.substring(index + 1);
    }

    private record ObjectMethodsPlan(String owner, List<String> names, List<Handle> fields) {
    }

    record DesugarResult(boolean isSupported) {
        static DesugarResult supported() {
            return new DesugarResult(true);
        }

        static DesugarResult unsupported() {
            return new DesugarResult(false);
        }
    }
}
