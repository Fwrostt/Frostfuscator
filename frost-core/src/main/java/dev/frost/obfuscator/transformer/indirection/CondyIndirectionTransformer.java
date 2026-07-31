package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Replaces constants and member references with authenticated, caller-bound Java 11
 * ConstantDynamic cipher streams. Each value is chained through a nested key Condy.
 */
public final class CondyIndirectionTransformer extends Transformer {
    private static final String RUNTIME_CLASS = "dev/frost/runtime/CondyCipherBootstrap";
    private static final String KEY_BOOTSTRAP_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;JJ)Ljava/lang/Object;";
    private static final String VALUE_BOOTSTRAP_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                    + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJI)Ljava/lang/Object;";
    private static final String METHOD_HANDLE_DESC = "Ljava/lang/invoke/MethodHandle;";
    private static final String VAR_HANDLE_DESC = "Ljava/lang/invoke/VarHandle;";
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final int PAYLOAD_CHUNK_SIZE = 48_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int KIND_STRING = 1;
    private static final int KIND_INT = 2;
    private static final int KIND_LONG = 3;
    private static final int KIND_FLOAT = 4;
    private static final int KIND_DOUBLE = 5;
    private static final int KIND_CLASS = 6;
    private static final int KIND_METHOD_TYPE = 7;
    private static final int KIND_METHOD_HANDLE = 8;
    private static final int KIND_VAR_HANDLE = 9;

    @Override
    public String getName() {
        return "condy-indirection";
    }

    @Override
    public String getCategory() {
        return "Indirection";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public Priority priority() {
        return Priority.FINAL;
    }

    @Override
    public int orderWeight() {
        return 10_000;
    }

    @Override
    public void transform(Context context) {
        Counts counts = transformInternal(context.pool(), context.config());
        context.stats().add("condyConstants", counts.constants.sum());
        context.stats().add("condyMethodHandles", counts.methodHandles.sum());
        context.stats().add("condyVarHandles", counts.varHandles.sum());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        transformInternal(pool, config);
    }

    private Counts transformInternal(ClassPool pool, TransformerConfig config) {
        int probability = clamp(intOption(config, "probability", 60), 0, 100);
        boolean constants = booleanOption(config, "constants", true);
        boolean immediateNumbers = booleanOption(config, "immediate-numbers", true);
        boolean classLiterals = booleanOption(config, "class-literals", true);
        boolean bootstrapArguments = booleanOption(config, "bootstrap-arguments", true);
        boolean methodHandles = booleanOption(config, "method-handles", true);
        boolean varHandles = booleanOption(config, "var-handles", true);
        Carrier carrier = carrier(pool);
        Counts counts = new Counts();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || classNode.name.equals(carrier.owner) || isCipherCarrier(classNode)) {
                return;
            }

            boolean modified = false;
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                boolean initializer = method.name.equals("<init>") || method.name.equals("<clinit>");
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                    AbstractInsnNode next = instruction.getNext();
                    int protectedArguments = bootstrapArguments
                            ? protectBootstrapArguments(classNode.name, instruction, carrier, probability,
                            constants, classLiterals, methodHandles) : 0;
                    if (protectedArguments > 0) {
                        counts.constants.add(protectedArguments);
                        modified = true;
                    }
                    ConstantValue constant = constantValue(instruction, constants, immediateNumbers,
                            classLiterals, methodHandles);
                    if (!(instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic)
                            && constant != null && selected(probability)) {
                        ConstantDynamic dynamic = encryptedCondy(classNode.name, constant, carrier);
                        method.instructions.set(instruction, new LdcInsnNode(dynamic));
                        counts.constants.increment();
                        modified = true;
                    } else if (!initializer && methodHandles && instruction instanceof MethodInsnNode call
                            && selected(probability) && replaceMethodCall(classNode, method, call, carrier)) {
                        counts.methodHandles.increment();
                        modified = true;
                    } else if (!initializer && varHandles && instruction instanceof FieldInsnNode field
                            && selected(probability) && replaceFieldAccess(pool, classNode, method, field, carrier)) {
                        counts.varHandles.increment();
                        modified = true;
                    }
                    instruction = next;
                }
            }

            if (modified) {
                if ((classNode.version & 0xffff) < Opcodes.V11) classNode.version = Opcodes.V11;
                pool.markFramesDirty(classNode.name);
                detail("Applied encrypted Condy cipher streams in {}", classNode.name);
            }
        });

        if (counts.total() > 0) {
            injectCarrier(pool, carrier);
            log("Protected {} constants, {} method calls, and {} field accesses with encrypted Condy chains.",
                    counts.constants.sum(), counts.methodHandles.sum(), counts.varHandles.sum());
        }
        return counts;
    }

    private boolean replaceMethodCall(ClassNode caller, MethodNode method,
                                      MethodInsnNode call, Carrier carrier) {
        if (call.name.equals("<init>") || call.name.equals("<clinit>")) return false;
        int handleTag = switch (call.getOpcode()) {
            case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKEVIRTUAL -> Opcodes.H_INVOKEVIRTUAL;
            case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
            case Opcodes.INVOKESPECIAL -> Opcodes.H_INVOKESPECIAL;
            default -> -1;
        };
        if (handleTag < 0) return false;

        Type methodType = Type.getMethodType(call.desc);
        Type[] arguments = methodType.getArgumentTypes();
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        Type receiverType = call.getOpcode() == Opcodes.INVOKESPECIAL
                ? Type.getObjectType(caller.name) : ownerType(call.owner);
        int cursor = method.maxLocals;
        int receiverLocal = -1;
        if (!isStatic) receiverLocal = cursor++;
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
        replacement.add(new LdcInsnNode(encryptedHandleCondy(caller.name, target, carrier)));
        if (!isStatic) replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        for (int index = 0; index < arguments.length; index++) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), argumentLocals[index]));
        }
        Type[] invocationArguments = isStatic ? arguments : prepend(receiverType, arguments);
        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle", "invokeExact",
                Type.getMethodDescriptor(methodType.getReturnType(), invocationArguments), false));
        method.instructions.insertBefore(call, replacement);
        method.instructions.remove(call);
        return true;
    }

    private boolean replaceFieldAccess(ClassPool pool, ClassNode caller, MethodNode method,
                                       FieldInsnNode field, Carrier carrier) {
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

        String metadata = metadata(field.owner, field.name, field.desc, isStatic ? "1" : "0");
        ConstantValue target = new ConstantValue(KIND_VAR_HANDLE, VAR_HANDLE_DESC, metadata);
        replacement.add(new LdcInsnNode(encryptedCondy(caller.name, target, carrier)));
        if (!isStatic) replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        if (write) replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), valueLocal));

        Type[] parameters;
        if (isStatic) parameters = write ? new Type[]{fieldType} : new Type[0];
        else parameters = write ? new Type[]{receiverType, fieldType} : new Type[]{receiverType};
        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/VarHandle", write ? "set" : "get",
                Type.getMethodDescriptor(write ? Type.VOID_TYPE : fieldType, parameters), false));
        method.instructions.insertBefore(field, replacement);
        method.instructions.remove(field);
        return true;
    }

    private ConstantDynamic encryptedHandleCondy(String caller, Handle handle, Carrier carrier) {
        String metadata = metadata(Integer.toString(handle.getTag()), handle.getOwner(),
                handle.getName(), handle.getDesc(), handle.isInterface() ? "1" : "0");
        return encryptedCondy(caller,
                new ConstantValue(KIND_METHOD_HANDLE, METHOD_HANDLE_DESC, metadata), carrier);
    }

    private ConstantDynamic encryptedCondy(String caller, ConstantValue value, Carrier carrier) {
        String valueName = randomIdentifier("v");
        String keyName = randomIdentifier("k");
        long nonce = RANDOM.nextLong();
        long key = RANDOM.nextLong();
        long keyMask = keyMask(runtimeClassName(caller), keyName, nonce);
        ConstantDynamic keyDynamic = new ConstantDynamic(keyName, "J", carrier.keyHandle,
                key ^ keyMask, nonce);

        byte[] plain = modifiedUtf8(value.payload);
        long tag = authenticationTag(plain, key, nonce, runtimeClassName(caller), value.kind);
        applyStream(plain, streamSeed(runtimeClassName(caller), valueName,
                runtimeTypeName(value.descriptor), key, nonce, value.kind));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(plain);
        Arrays.fill(plain, (byte) 0);
        String[] chunks = chunks(encoded);
        long encodedTag = tag ^ mix64(key + nonce + 0x6a09e667f3bcc909L);
        return new ConstantDynamic(valueName, value.descriptor, carrier.valueHandle,
                chunks[0], chunks[1], chunks[2], chunks[3], nonce, encodedTag,
                keyDynamic, value.kind ^ (int) nonce);
    }

    private ConstantValue constantValue(AbstractInsnNode instruction, boolean constants,
                                        boolean immediateNumbers, boolean classLiterals,
                                        boolean methodHandles) {
        if (instruction instanceof LdcInsnNode ldc) {
            return objectConstant(ldc.cst, constants, classLiterals, methodHandles);
        }
        if (!constants || !immediateNumbers) return null;
        if (instruction instanceof IntInsnNode integer
                && (integer.getOpcode() == Opcodes.BIPUSH || integer.getOpcode() == Opcodes.SIPUSH)) {
            return new ConstantValue(KIND_INT, "I", Integer.toString(integer.operand));
        }
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return new ConstantValue(KIND_INT, "I", Integer.toString(opcode - Opcodes.ICONST_0));
        }
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) {
            return new ConstantValue(KIND_LONG, "J", Long.toString(opcode - Opcodes.LCONST_0));
        }
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) {
            float value = opcode - Opcodes.FCONST_0;
            return new ConstantValue(KIND_FLOAT, "F",
                    Integer.toUnsignedString(Float.floatToRawIntBits(value), 16));
        }
        if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) {
            double value = opcode - Opcodes.DCONST_0;
            return new ConstantValue(KIND_DOUBLE, "D",
                    Long.toUnsignedString(Double.doubleToRawLongBits(value), 16));
        }
        return null;
    }

    private int protectBootstrapArguments(String caller, AbstractInsnNode instruction, Carrier carrier,
                                            int probability, boolean constants,
                                            boolean classLiterals, boolean methodHandles) {
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            return protectArguments(caller, dynamic.bsmArgs, carrier, probability,
                    constants, classLiterals, methodHandles, 0);
        }
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic dynamic) {
            RewriteResult rewritten = rewriteDynamic(caller, dynamic, carrier, probability,
                    constants, classLiterals, methodHandles, 0);
            if (rewritten.count > 0) ldc.cst = rewritten.dynamic;
            return rewritten.count;
        }
        return 0;
    }

    private int protectArguments(String caller, Object[] arguments, Carrier carrier, int probability,
                                 boolean constants, boolean classLiterals, boolean methodHandles, int depth) {
        if (depth >= 8) return 0;
        int changed = 0;
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            if (argument instanceof ConstantDynamic dynamic) {
                RewriteResult rewritten = rewriteDynamic(caller, dynamic, carrier, probability,
                        constants, classLiterals, methodHandles, depth + 1);
                arguments[index] = rewritten.dynamic;
                changed += rewritten.count;
                continue;
            }
            ConstantValue value = objectConstant(argument, constants, classLiterals, methodHandles);
            if (value != null && selected(probability)) {
                arguments[index] = encryptedCondy(caller, value, carrier);
                changed++;
            }
        }
        return changed;
    }

    private RewriteResult rewriteDynamic(String caller, ConstantDynamic dynamic, Carrier carrier,
                                         int probability, boolean constants,
                                         boolean classLiterals, boolean methodHandles, int depth) {
        Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
        for (int index = 0; index < arguments.length; index++) {
            arguments[index] = dynamic.getBootstrapMethodArgument(index);
        }
        int changed = protectArguments(caller, arguments, carrier, probability,
                constants, classLiterals, methodHandles, depth);
        return changed == 0 ? new RewriteResult(dynamic, 0)
                : new RewriteResult(new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(),
                dynamic.getBootstrapMethod(), arguments), changed);
    }

    private ConstantValue objectConstant(Object constant, boolean constants,
                                         boolean classLiterals, boolean methodHandles) {
        if (constant == null || constant instanceof ConstantDynamic) return null;
        if (constants && constant instanceof String value) {
            return new ConstantValue(KIND_STRING, "Ljava/lang/String;", value);
        }
        if (constants && constant instanceof Integer value) {
            return new ConstantValue(KIND_INT, "I", value.toString());
        }
        if (constants && constant instanceof Long value) {
            return new ConstantValue(KIND_LONG, "J", value.toString());
        }
        if (constants && constant instanceof Float value) {
            return new ConstantValue(KIND_FLOAT, "F",
                    Integer.toUnsignedString(Float.floatToRawIntBits(value), 16));
        }
        if (constants && constant instanceof Double value) {
            return new ConstantValue(KIND_DOUBLE, "D",
                    Long.toUnsignedString(Double.doubleToRawLongBits(value), 16));
        }
        if (classLiterals && constant instanceof Type type && type.getSort() != Type.METHOD) {
            return new ConstantValue(KIND_CLASS, "Ljava/lang/Class;", type.getDescriptor());
        }
        if (methodHandles && constant instanceof Type type && type.getSort() == Type.METHOD) {
            return new ConstantValue(KIND_METHOD_TYPE, "Ljava/lang/invoke/MethodType;", type.getDescriptor());
        }
        if (methodHandles && constant instanceof Handle handle) {
            String metadata = metadata(Integer.toString(handle.getTag()), handle.getOwner(),
                    handle.getName(), handle.getDesc(), handle.isInterface() ? "1" : "0");
            return new ConstantValue(KIND_METHOD_HANDLE, METHOD_HANDLE_DESC, metadata);
        }
        return null;
    }

    private Carrier carrier(ClassPool pool) {
        String prefix = pool.getClasses().stream()
                .map(node -> node.name)
                .filter(name -> !name.startsWith("dev/frost/"))
                .map(name -> name.contains("/") ? name.substring(0, name.lastIndexOf('/')) : "")
                .findFirst().orElse("__frost");
        String owner;
        do {
            owner = (prefix.isBlank() ? "" : prefix + "/") + randomIdentifier("c");
        } while (pool.contains(owner));
        return new Carrier(owner, randomIdentifier("b"), randomIdentifier("b"));
    }

    private void injectCarrier(ClassPool pool, Carrier carrier) {
        try (InputStream input = CondyIndirectionTransformer.class
                .getResourceAsStream("/" + RUNTIME_CLASS + ".class")) {
            if (input == null) throw new IllegalStateException("Missing embedded Condy bootstrap runtime");
            ClassNode original = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(original, ClassReader.EXPAND_FRAMES);
            Map<String, String> methods = new HashMap<>();
            Map<String, String> fields = new HashMap<>();
            for (MethodNode method : original.methods) {
                if (method.name.equals("<init>")) continue;
                methods.putIfAbsent(method.name, method.name.equals("key") ? carrier.keyMethod
                        : method.name.equals("value") ? carrier.valueMethod : randomIdentifier("m"));
            }
            for (FieldNode field : original.fields) fields.put(field.name, randomIdentifier("f"));
            ClassNode relocated = new ClassNode();
            original.accept(new ClassRemapper(relocated, new Remapper() {
                @Override
                public String map(String internalName) {
                    return RUNTIME_CLASS.equals(internalName) ? carrier.owner : internalName;
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return RUNTIME_CLASS.equals(owner) ? methods.getOrDefault(name, name) : name;
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return RUNTIME_CLASS.equals(owner) ? fields.getOrDefault(name, name) : name;
                }
            }));
            relocated.version = Opcodes.V11;
            relocated.access |= Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
            relocated.sourceFile = null;
            relocated.sourceDebug = null;
            pool.addClass(relocated.name, relocated);
            pool.markFramesDirty(relocated.name);
            pool.markGeneratedDecoy(relocated.name);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not inject Condy bootstrap runtime", exception);
        }
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

    private static boolean isCipherCarrier(ClassNode node) {
        boolean key = false;
        boolean value = false;
        for (MethodNode method : node.methods) {
            key |= method.desc.equals(KEY_BOOTSTRAP_DESC);
            value |= method.desc.equals(VALUE_BOOTSTRAP_DESC);
        }
        return key && value;
    }

    private static byte[] modifiedUtf8(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            length += character >= 0x0001 && character <= 0x007f ? 1
                    : character <= 0x07ff ? 2 : 3;
        }
        byte[] bytes = new byte[length];
        int cursor = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 0x0001 && character <= 0x007f) {
                bytes[cursor++] = (byte) character;
            } else if (character <= 0x07ff) {
                bytes[cursor++] = (byte) (0xc0 | ((character >>> 6) & 0x1f));
                bytes[cursor++] = (byte) (0x80 | (character & 0x3f));
            } else {
                bytes[cursor++] = (byte) (0xe0 | ((character >>> 12) & 0x0f));
                bytes[cursor++] = (byte) (0x80 | ((character >>> 6) & 0x3f));
                bytes[cursor++] = (byte) (0x80 | (character & 0x3f));
            }
        }
        return bytes;
    }

    private static String metadata(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.length()).append(':').append(value);
        return result.toString();
    }

    private static String[] chunks(String value) {
        String[] chunks = {"", "", "", ""};
        int count = (value.length() + PAYLOAD_CHUNK_SIZE - 1) / PAYLOAD_CHUNK_SIZE;
        if (count > chunks.length) throw new IllegalArgumentException("Condy payload exceeds class-file limits");
        for (int index = 0; index < count; index++) {
            int start = index * PAYLOAD_CHUNK_SIZE;
            chunks[index] = value.substring(start, Math.min(value.length(), start + PAYLOAD_CHUNK_SIZE));
        }
        return chunks;
    }

    private static void applyStream(byte[] data, long seed) {
        long state = seed;
        long block = 0L;
        for (int index = 0; index < data.length; index++) {
            if ((index & 7) == 0) {
                state += GOLDEN_GAMMA;
                block = mix64(state);
            }
            data[index] ^= (byte) (block >>> ((index & 7) << 3));
        }
    }

    private static long streamSeed(String owner, String name, String type,
                                   long key, long nonce, int kind) {
        long seed = key ^ Long.rotateLeft(nonce, 17) ^ ((long) kind * GOLDEN_GAMMA);
        seed ^= hash64(owner);
        seed = mix64(seed ^ hash64(name));
        return mix64(seed ^ hash64(type));
    }

    private static long keyMask(String owner, String name, long nonce) {
        return mix64(hash64(owner) ^ Long.rotateLeft(hash64(name), 23)
                ^ nonce ^ 0x243f6a8885a308d3L);
    }

    private static long authenticationTag(byte[] data, long key, long nonce,
                                          String owner, int kind) {
        long hash = mix64(key ^ nonce ^ hash64(owner) ^ ((long) kind * GOLDEN_GAMMA));
        for (byte value : data) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
            hash = Long.rotateLeft(hash, 11);
        }
        return mix64(hash ^ data.length);
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static String runtimeClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String runtimeTypeName(String descriptor) {
        Type type = Type.getType(descriptor);
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "boolean";
            case Type.CHAR -> "char";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            case Type.ARRAY -> descriptor.replace('/', '.');
            default -> type.getClassName();
        };
    }

    private static Type ownerType(String owner) {
        return owner.startsWith("[") ? Type.getType(owner) : Type.getObjectType(owner);
    }

    private static Type[] prepend(Type first, Type[] remainder) {
        Type[] result = new Type[remainder.length + 1];
        result[0] = first;
        System.arraycopy(remainder, 0, result, 1, remainder.length);
        return result;
    }

    private static String randomIdentifier(String prefix) {
        return "_$" + prefix + Long.toUnsignedString(RANDOM.nextLong(), 36);
    }

    private static boolean selected(int probability) {
        return probability >= 100 || probability > 0 && RANDOM.nextInt(100) < probability;
    }

    private static int intOption(TransformerConfig config, String key, int fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ConstantValue(int kind, String descriptor, String payload) {
    }

    private record RewriteResult(ConstantDynamic dynamic, int count) {
    }

    private record Carrier(String owner, String keyMethod, String valueMethod,
                           Handle keyHandle, Handle valueHandle) {
        private Carrier(String owner, String keyMethod, String valueMethod) {
            this(owner, keyMethod, valueMethod,
                    new Handle(Opcodes.H_INVOKESTATIC, owner, keyMethod, KEY_BOOTSTRAP_DESC, false),
                    new Handle(Opcodes.H_INVOKESTATIC, owner, valueMethod, VALUE_BOOTSTRAP_DESC, false));
        }
    }

    private static final class Counts {
        private final LongAdder constants = new LongAdder();
        private final LongAdder methodHandles = new LongAdder();
        private final LongAdder varHandles = new LongAdder();

        private long total() {
            return constants.sum() + methodHandles.sum() + varHandles.sum();
        }
    }
}
