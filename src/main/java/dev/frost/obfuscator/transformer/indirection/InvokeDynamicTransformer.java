package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class InvokeDynamicTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "invoke-dynamic";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 35), 0, 100);
        boolean mutableCallSites = getBooleanOption(config, "mutable-callsites", true);

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            String bootstrapName = uniqueMethodName(classNode);
            boolean changed = false;
            Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, classNode.name, bootstrapName,
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false);

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || AccessHelper.isInitializer(method)) continue;
                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof MethodInsnNode call && canWrap(pool, classNode.name, call)
                            && RANDOM.nextInt(100) < probability) {
                        method.instructions.set(call, new InvokeDynamicInsnNode(randomIdentifier(), indyDescriptor(call), bootstrap,
                                call.getOpcode(), Type.getObjectType(call.owner), call.name, Type.getMethodType(call.desc)));
                        changed = true;
                    }
                    insn = next;
                }
            }

            if (changed) {
                classNode.version = Math.max(classNode.version, Opcodes.V1_7);
                classNode.methods.add(buildBootstrap(bootstrapName, mutableCallSites));
                pool.markDirty(classNode.name);
                log("Wrapped method calls with invokedynamic in {}", classNode.name);
            }
        }
    }

    private boolean canWrap(ClassPool pool, String caller, MethodInsnNode call) {
        int opcode = call.getOpcode();
        if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
            return false;
        }
        if (call.name.startsWith("<") || call.owner.startsWith("java/lang/invoke/")) return false;

        ClassNode owner = pool.getClass(call.owner);
        if (owner == null) owner = pool.getLibraryClasses().get(call.owner);
        if (owner == null) return false;

        for (MethodNode method : owner.methods) {
            if (method.name.equals(call.name) && method.desc.equals(call.desc)) {
                return call.owner.equals(caller) || (AccessHelper.isPublic(owner.access) && AccessHelper.isPublic(method.access));
            }
        }
        return false;
    }

    private String indyDescriptor(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return call.desc;
        }

        Type methodType = Type.getMethodType(call.desc);
        Type[] args = methodType.getArgumentTypes();
        Type[] indyArgs = new Type[args.length + 1];
        indyArgs[0] = Type.getObjectType(call.owner);
        System.arraycopy(args, 0, indyArgs, 1, args.length);
        return Type.getMethodDescriptor(methodType.getReturnType(), indyArgs);
    }

    private MethodNode buildBootstrap(String name, boolean mutableCallSite) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                null, new String[]{"java/lang/Exception"});
        InsnList il = mn.instructions;
        LabelNode virtualCall = new LabelNode();
        LabelNode adapt = new LabelNode();

        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new LdcInsnNode(Opcodes.INVOKESTATIC));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPNE, virtualCall));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                false));
        il.add(new JumpInsnNode(Opcodes.GOTO, adapt));

        il.add(virtualCall);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findVirtual",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                false));

        il.add(adapt);
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType",
                "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new TypeInsnNode(Opcodes.NEW, mutableCallSite
                ? "java/lang/invoke/MutableCallSite"
                : "java/lang/invoke/ConstantCallSite"));
        il.add(new InsnNode(Opcodes.DUP_X1));
        il.add(new InsnNode(Opcodes.SWAP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mutableCallSite
                        ? "java/lang/invoke/MutableCallSite"
                        : "java/lang/invoke/ConstantCallSite", "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 6;
        mn.maxLocals = 7;
        return mn;
    }

    private String uniqueMethodName(ClassNode classNode) {
        String name;
        do {
            name = randomIdentifier();
        } while (hasMethod(classNode, name));
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

    private boolean hasMethod(ClassNode classNode, String name) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
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

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
