package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Hides constants and member references behind Java 11 constant-dynamic bootstraps.
 */
public class CondyIndirectionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOOTSTRAP_METHOD_NAME = "__frost$condy$bootstrap";
    private static final String BOOTSTRAP_METHOD_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String METHOD_HANDLE_DESC = "Ljava/lang/invoke/MethodHandle;";
    private static final String VAR_HANDLE_DESC = "Ljava/lang/invoke/VarHandle;";
    private static final String CONSTANT_BOOTSTRAPS = "java/lang/invoke/ConstantBootstraps";
    private static final Handle BOOTSTRAP_INVOKE = new Handle(
            Opcodes.H_INVOKESTATIC,
            CONSTANT_BOOTSTRAPS,
            "invoke",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;",
            false);
    private static final Handle METHOD_HANDLES_IDENTITY = new Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/MethodHandles",
            "identity",
            "(Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
            false);
    private static final Handle FIELD_VAR_HANDLE = new Handle(
            Opcodes.H_INVOKESTATIC,
            CONSTANT_BOOTSTRAPS,
            "fieldVarHandle",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
            false);
    private static final Handle STATIC_FIELD_VAR_HANDLE = new Handle(
            Opcodes.H_INVOKESTATIC,
            CONSTANT_BOOTSTRAPS,
            "staticFieldVarHandle",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
            false);
    private static final Handle PRIMITIVE_CLASS = new Handle(
            Opcodes.H_INVOKESTATIC,
            CONSTANT_BOOTSTRAPS,
            "primitiveClass",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;",
            false);

    @Override
    public String getName() {
        return "condy-indirection";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 60), 0, 100);
        boolean constants = getBooleanOption(config, "constants", true);
        boolean methodHandles = getBooleanOption(config, "method-handles", true);
        boolean varHandles = getBooleanOption(config, "var-handles", true);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            boolean modified = false;
            boolean constantBootstrapRequired = false;
            Handle valueBootstrap = new Handle(Opcodes.H_INVOKESTATIC, classNode.name,
                    BOOTSTRAP_METHOD_NAME, BOOTSTRAP_METHOD_DESC, false);

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || AccessHelper.isInitializer(method)) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (constants && insn instanceof LdcInsnNode ldc && ldc.cst != null && selected(probability)) {
                        ConstantDynamic condy = buildValueCondy(ldc.cst, valueBootstrap);
                        if (condy != null) {
                            method.instructions.set(ldc, new LdcInsnNode(condy));
                            modified = true;
                            constantBootstrapRequired = true;
                        }
                    } else if (methodHandles && insn instanceof MethodInsnNode call && selected(probability)) {
                        modified |= replaceMethodCall(method, call);
                    } else if (varHandles && insn instanceof FieldInsnNode field && selected(probability)) {
                        modified |= replaceFieldAccess(pool, classNode, method, field);
                    }
                    insn = next;
                }
            }

            if (modified) {
                if ((classNode.version & 0xFFFF) < Opcodes.V11) classNode.version = Opcodes.V11;
                if (constantBootstrapRequired) ensureBootstrapMethod(classNode);
                pool.markFramesDirty(classNode.name);
                detail("Applied Condy member and constant indirection in {}", classNode.name);
            }
        });
    }

    private boolean replaceMethodCall(MethodNode method, MethodInsnNode call) {
        if (call.name.equals("<init>") || call.name.equals("<clinit>")
                || call.getOpcode() == Opcodes.INVOKESPECIAL) {
            return false;
        }
        int handleTag = switch (call.getOpcode()) {
            case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKEVIRTUAL -> Opcodes.H_INVOKEVIRTUAL;
            case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
            default -> -1;
        };
        if (handleTag < 0) return false;

        Type originalType = Type.getMethodType(call.desc);
        Type[] arguments = originalType.getArgumentTypes();
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        Type receiverType = ownerType(call.owner);
        int receiverLocal = -1;
        int cursor = method.maxLocals;
        if (!isStatic) {
            receiverLocal = cursor++;
        }
        int[] argumentLocals = new int[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            argumentLocals[index] = cursor;
            cursor += arguments[index].getSize();
        }
        method.maxLocals = Math.max(method.maxLocals, cursor);

        InsnList replacement = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), argumentLocals[index]));
        }
        if (!isStatic) replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));

        Handle target = new Handle(handleTag, call.owner, call.name, call.desc, call.itf);
        replacement.add(new LdcInsnNode(methodHandleCondy(call.name, target)));
        if (!isStatic) replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        for (int index = 0; index < arguments.length; index++) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), argumentLocals[index]));
        }
        Type[] invocationArguments = isStatic ? arguments : prepend(receiverType, arguments);
        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle", "invokeExact",
                Type.getMethodDescriptor(originalType.getReturnType(), invocationArguments), false));
        method.instructions.insertBefore(call, replacement);
        method.instructions.remove(call);
        return true;
    }

    private boolean replaceFieldAccess(ClassPool pool, ClassNode caller, MethodNode method, FieldInsnNode field) {
        int opcode = field.getOpcode();
        boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        boolean write = opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD;
        if (!isStatic && opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD) return false;
        if (write && isFinalField(pool, field.owner, field.name, field.desc)) return false;

        Type fieldType = Type.getType(field.desc);
        Type receiverType = ownerType(field.owner);
        InsnList replacement = new InsnList();
        int cursor = method.maxLocals;
        int receiverLocal = -1;
        int valueLocal = -1;
        if (write) {
            valueLocal = cursor;
            cursor += fieldType.getSize();
            replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ISTORE), valueLocal));
        }
        if (!isStatic) {
            receiverLocal = cursor++;
            replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        }
        method.maxLocals = Math.max(method.maxLocals, cursor);

        Handle bootstrap = isStatic ? STATIC_FIELD_VAR_HANDLE : FIELD_VAR_HANDLE;
        replacement.add(new LdcInsnNode(new ConstantDynamic(field.name, VAR_HANDLE_DESC, bootstrap,
                ownerType(field.owner), classConstant(fieldType))));
        if (!isStatic) replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        if (write) replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), valueLocal));

        Type[] parameters;
        if (isStatic) {
            parameters = write ? new Type[]{fieldType} : new Type[0];
        } else {
            parameters = write ? new Type[]{receiverType, fieldType} : new Type[]{receiverType};
        }
        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/VarHandle", write ? "set" : "get",
                Type.getMethodDescriptor(write ? Type.VOID_TYPE : fieldType, parameters), false));
        method.instructions.insertBefore(field, replacement);
        method.instructions.remove(field);
        return true;
    }

    private boolean isFinalField(ClassPool pool, String owner, String name, String descriptor) {
        ClassNode target = pool.getClass(owner);
        if (target == null) return true;
        return target.fields.stream()
                .filter(field -> field.name.equals(name) && field.desc.equals(descriptor))
                .findFirst()
                .map(field -> (field.access & Opcodes.ACC_FINAL) != 0)
                .orElse(true);
    }

    private ConstantDynamic methodHandleCondy(String name, Handle target) {
        ConstantDynamic identity = new ConstantDynamic("identity", METHOD_HANDLE_DESC, BOOTSTRAP_INVOKE,
                METHOD_HANDLES_IDENTITY, Type.getType(METHOD_HANDLE_DESC));
        return new ConstantDynamic(safeCondyName(name), METHOD_HANDLE_DESC, BOOTSTRAP_INVOKE, identity, target);
    }

    private ConstantDynamic buildValueCondy(Object constant, Handle bootstrapHandle) {
        if (constant instanceof String value) {
            return new ConstantDynamic("c$str", "Ljava/lang/String;", bootstrapHandle, value);
        } else if (constant instanceof Integer value) {
            return new ConstantDynamic("c$int", "I", bootstrapHandle, value);
        } else if (constant instanceof Long value) {
            return new ConstantDynamic("c$long", "J", bootstrapHandle, value);
        } else if (constant instanceof Float value) {
            return new ConstantDynamic("c$float", "F", bootstrapHandle, value);
        } else if (constant instanceof Double value) {
            return new ConstantDynamic("c$double", "D", bootstrapHandle, value);
        }
        return null;
    }

    private void ensureBootstrapMethod(ClassNode classNode) {
        if (classNode.methods.stream().anyMatch(method -> method.name.equals(BOOTSTRAP_METHOD_NAME))) return;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                BOOTSTRAP_METHOD_NAME, BOOTSTRAP_METHOD_DESC, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 4;
        classNode.methods.add(method);
    }

    private static Type ownerType(String owner) {
        return owner.startsWith("[") ? Type.getType(owner) : Type.getObjectType(owner);
    }

    private static Object classConstant(Type type) {
        if (type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.DOUBLE) {
            return new ConstantDynamic(type.getDescriptor(), "Ljava/lang/Class;", PRIMITIVE_CLASS);
        }
        return type;
    }

    private static Type[] prepend(Type first, Type[] remainder) {
        Type[] result = Arrays.copyOf(remainder, remainder.length + 1);
        System.arraycopy(result, 0, result, 1, remainder.length);
        result[0] = first;
        return result;
    }

    private static String safeCondyName(String name) {
        if (name == null || name.isBlank() || name.indexOf('.') >= 0 || name.indexOf(';') >= 0
                || name.indexOf('[') >= 0 || name.indexOf('/') >= 0) {
            return "member";
        }
        return name;
    }

    private boolean selected(int probability) {
        return probability >= 100 || probability > 0 && RANDOM.nextInt(100) < probability;
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
