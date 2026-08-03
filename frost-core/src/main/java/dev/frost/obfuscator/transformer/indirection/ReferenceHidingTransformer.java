package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.ir.IrMethodPassAdapter;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.Operation;
import dev.frost.ir.pass.TypedInvocationRewritePass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReferenceHidingTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "reference-hiding";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 45), 0, 100);
        int maxPerClass = Math.max(0, getIntOption(config, "max-per-class", 96));
        int maxMethodInstructions = getIntOption(config, "max-method-instructions", 6000);
        Map<String, Map<String, Integer>> applicationMethodAccess = snapshotApplicationMethods(pool);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            List<ProxyRequest> requests = new ArrayList<>();
            List<FieldProxyRequest> fieldRequests = new ArrayList<>();
            Set<String> usedNames = usedMethodNames(classNode);
            IrMethodPassAdapter adapter = new IrMethodPassAdapter();
            for (MethodNode method : List.copyOf(classNode.methods)) {
                if (method.instructions == null || AccessHelper.isInitializer(method)) continue;
                if (method.instructions.size() > maxMethodInstructions) continue;
                if (requests.size() + fieldRequests.size() >= maxPerClass) break;
                List<ProxyRequest> methodRequests = new ArrayList<>();
                List<FieldProxyRequest> methodFieldRequests = new ArrayList<>();
                var pass = new TypedInvocationRewritePass((candidate, ignored) -> {
                    if (requests.size() + fieldRequests.size() + methodRequests.size()
                            + methodFieldRequests.size() >= maxPerClass || RANDOM.nextInt(100) >= probability) {
                        return java.util.Optional.empty();
                    }
                    if (candidate.instruction().operation().code().equals(CoreOps.INVOKE)) {
                        int opcode = invokeOpcode(candidate.invokeKind());
                        if (opcode < 0) return java.util.Optional.empty();
                        MethodInsnNode call = new MethodInsnNode(opcode, candidate.owner(), candidate.name(),
                                candidate.descriptor(), candidate.interfaceOwner());
                        if (!canProxy(pool, applicationMethodAccess, classNode.name, call)) {
                            return java.util.Optional.empty();
                        }
                        String proxyName = uniqueMethodName(usedNames);
                        String proxyDesc = proxyDescriptor(call);
                        methodRequests.add(new ProxyRequest(proxyName, proxyDesc, call));
                        return java.util.Optional.of(staticInvoke(classNode.name, proxyName, proxyDesc));
                    }
                    int fieldOpcode = fieldOpcode(candidate.instruction().operation().code());
                    if (fieldOpcode >= 0) {
                        FieldInsnNode fieldInsn = new FieldInsnNode(fieldOpcode, candidate.owner(), candidate.name(),
                                candidate.descriptor());
                        if (!canProxyField(pool, classNode.name, fieldInsn)) return java.util.Optional.empty();
                        String proxyName = uniqueMethodName(usedNames);
                        String proxyDesc = fieldProxyDescriptor(fieldInsn);
                        methodFieldRequests.add(new FieldProxyRequest(proxyName, proxyDesc, fieldInsn));
                        return java.util.Optional.of(staticInvoke(classNode.name, proxyName, proxyDesc));
                    }
                    return java.util.Optional.empty();
                });
                var result = adapter.run(classNode.name, method, pass, RANDOM.nextLong());
                if (result.changed()) {
                    MethodNode output = result.output().orElseThrow();
                    IrMethodPassAdapter.removeUnreferencedEntryLabel(output);
                    IrMethodPassAdapter.publishBody(method, output);
                    requests.addAll(methodRequests);
                    fieldRequests.addAll(methodFieldRequests);
                }
            }

            for (ProxyRequest request : requests) {
                classNode.methods.add(buildProxy(request));
            }
            for (FieldProxyRequest request : fieldRequests) {
                classNode.methods.add(buildFieldProxy(request));
            }
            if (!requests.isEmpty() || !fieldRequests.isEmpty()) {
                pool.markFramesDirty(classNode.name);
                detail("Inserted {} method and {} field reference proxies in {}", requests.size(), fieldRequests.size(), classNode.name);
            }
        });
    }

    private Operation staticInvoke(String owner, String name, String descriptor) {
        return new Operation(CoreOps.INVOKE, Map.of(
                "owner", IrAttribute.of(owner),
                "name", IrAttribute.of(name),
                "descriptor", IrAttribute.of(descriptor),
                "invoke_kind", IrAttribute.of("INVOKESTATIC"),
                "interface", IrAttribute.of(false)));
    }

    private int invokeOpcode(String kind) {
        return switch (kind) {
            case "INVOKESTATIC" -> Opcodes.INVOKESTATIC;
            case "INVOKEVIRTUAL" -> Opcodes.INVOKEVIRTUAL;
            case "INVOKEINTERFACE" -> Opcodes.INVOKEINTERFACE;
            case "INVOKESPECIAL" -> Opcodes.INVOKESPECIAL;
            default -> -1;
        };
    }

    private int fieldOpcode(dev.frost.ir.model.OperationCode code) {
        if (code.equals(CoreOps.STATIC_LOAD)) return Opcodes.GETSTATIC;
        if (code.equals(CoreOps.STATIC_STORE)) return Opcodes.PUTSTATIC;
        if (code.equals(CoreOps.FIELD_LOAD)) return Opcodes.GETFIELD;
        if (code.equals(CoreOps.FIELD_STORE)) return Opcodes.PUTFIELD;
        return -1;
    }

    private boolean canProxyField(ClassPool pool, String caller, FieldInsnNode field) {
        if (field.owner.startsWith("java/lang/")) return false;
        ClassNode owner = pool.getClass(field.owner);
        if (owner == null) owner = pool.getLibraryClasses().get(field.owner);
        if (owner == null) return false;
        return true;
    }

    private String fieldProxyDescriptor(FieldInsnNode field) {
        Type ownerType = Type.getObjectType(field.owner);
        Type fieldType = Type.getType(field.desc);
        return switch (field.getOpcode()) {
            case Opcodes.GETSTATIC -> Type.getMethodDescriptor(fieldType);
            case Opcodes.PUTSTATIC -> Type.getMethodDescriptor(Type.VOID_TYPE, fieldType);
            case Opcodes.GETFIELD -> Type.getMethodDescriptor(fieldType, ownerType);
            case Opcodes.PUTFIELD -> Type.getMethodDescriptor(Type.VOID_TYPE, ownerType, fieldType);
            default -> field.desc;
        };
    }

    private MethodNode buildFieldProxy(FieldProxyRequest request) {
        FieldInsnNode field = request.field;
        MethodNode proxy = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                request.name, request.desc, null, null);
        InsnList il = proxy.instructions;

        Type ownerType = Type.getObjectType(field.owner);
        Type fieldType = Type.getType(field.desc);

        switch (field.getOpcode()) {
            case Opcodes.GETSTATIC -> {
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, field.owner, field.name, field.desc));
                il.add(new InsnNode(fieldType.getOpcode(Opcodes.IRETURN)));
            }
            case Opcodes.PUTSTATIC -> {
                il.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), 0));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, field.owner, field.name, field.desc));
                il.add(new InsnNode(Opcodes.RETURN));
            }
            case Opcodes.GETFIELD -> {
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
                il.add(new InsnNode(fieldType.getOpcode(Opcodes.IRETURN)));
            }
            case Opcodes.PUTFIELD -> {
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), ownerType.getSize()));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, field.owner, field.name, field.desc));
                il.add(new InsnNode(Opcodes.RETURN));
            }
        }

        proxy.maxLocals = Type.getArgumentsAndReturnSizes(request.desc) >> 2;
        proxy.maxStack = Math.max(2, fieldType.getSize() + ownerType.getSize());
        return proxy;
    }

    private boolean canProxy(ClassPool pool, Map<String, Map<String, Integer>> applicationMethodAccess,
                             String caller, MethodInsnNode call) {
        if (call.name.startsWith("<")) return false;
        if (call.owner.startsWith("java/lang/invoke/")) return false;
        if (call.getOpcode() == Opcodes.INVOKESPECIAL) return false;

        ClassNode owner = pool.getClass(call.owner);
        if (owner != null) {
            Integer access = applicationMethodAccess
                    .getOrDefault(call.owner, Map.of())
                    .get(methodKey(call.name, call.desc));
            return access != null && (call.owner.equals(caller) || AccessHelper.isPublic(access));
        }

        owner = pool.getLibraryClasses().get(call.owner);
        if (owner == null) return call.getOpcode() == Opcodes.INVOKESTATIC;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(call.name) && method.desc.equals(call.desc)) {
                return call.owner.equals(caller) || AccessHelper.isPublic(method.access);
            }
        }
        return false;
    }

    private Map<String, Map<String, Integer>> snapshotApplicationMethods(ClassPool pool) {
        Map<String, Map<String, Integer>> snapshot = new HashMap<>();
        for (ClassNode owner : pool.getClasses()) {
            Map<String, Integer> methods = new HashMap<>();
            for (MethodNode method : owner.methods) {
                methods.put(methodKey(method.name, method.desc), method.access);
            }
            snapshot.put(owner.name, Map.copyOf(methods));
        }
        return Map.copyOf(snapshot);
    }

    private String methodKey(String name, String desc) {
        return name + desc;
    }

    private String proxyDescriptor(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return call.desc;
        }
        Type ownerType = Type.getObjectType(call.owner);
        Type methodType = Type.getMethodType(call.desc);
        Type[] originalArgs = methodType.getArgumentTypes();
        Type[] proxyArgs = new Type[originalArgs.length + 1];
        proxyArgs[0] = ownerType;
        System.arraycopy(originalArgs, 0, proxyArgs, 1, originalArgs.length);
        return Type.getMethodDescriptor(methodType.getReturnType(), proxyArgs);
    }

    private MethodNode buildProxy(ProxyRequest request) {
        MethodInsnNode call = request.call;
        MethodNode proxy = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                request.name, request.desc, null, null);
        InsnList il = proxy.instructions;

        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getId", "()J", false));
        il.add(new InsnNode(Opcodes.LXOR));
        il.add(new InsnNode(Opcodes.L2I));
        il.add(new InsnNode(Opcodes.POP));

        int slot = 0;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            il.add(new VarInsnNode(Opcodes.ALOAD, slot++));
        }
        for (Type arg : Type.getArgumentTypes(call.desc)) {
            il.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        il.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        il.add(new InsnNode(Type.getReturnType(call.desc).getOpcode(Opcodes.IRETURN)));
        proxy.maxLocals = Type.getArgumentsAndReturnSizes(request.desc) >> 2;
        proxy.maxStack = Math.max(1, proxy.maxLocals + 1);
        return proxy;
    }

    private Set<String> usedMethodNames(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (MethodNode method : classNode.methods) {
            used.add(method.name);
        }
        return used;
    }

    private String uniqueMethodName(Set<String> usedNames) {
        String name;
        do {
            name = randomIdentifier();
        } while (!usedNames.add(name));
        return name;
    }

    private String randomIdentifier() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";
        String body = alphabet + "0123456789";
        int length = 4 + RANDOM.nextInt(7);
        StringBuilder builder = new StringBuilder(length);
        builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        for (int i = 1; i < length; i++) {
            builder.append(body.charAt(RANDOM.nextInt(body.length())));
        }
        return builder.toString();
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ProxyRequest(String name, String desc, MethodInsnNode call) {
    }

    private record FieldProxyRequest(String name, String desc, FieldInsnNode field) {
    }
}
