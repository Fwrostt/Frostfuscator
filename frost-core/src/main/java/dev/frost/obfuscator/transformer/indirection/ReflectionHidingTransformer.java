package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Replaces selected public Java API calls with encrypted invokedynamic call
 * sites whose bootstrap resolves the target through MethodHandles.
 */
public final class ReflectionHidingTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int OWNER_SALT = 0x6A09E667;
    private static final int NAME_SALT = 0x3C6EF372;
    private static final int DESC_SALT = 0x510E527F;
    private static final int MIX_STEP = 0x9E37;
    private static final String DEFAULT_PREFIXES =
            "java/io,java/net,java/nio/file,java/util/zip,java/util/jar";

    @Override
    public String getName() {
        return "reflection-hiding";
    }

    @Override
    public String getCategory() {
        return "Calls";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(Context context) {
        int changed = apply(context.pool(), context.config());
        context.stats().add("reflectionHiddenCalls", changed);
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, config);
    }

    private int apply(ClassPool pool, TransformerConfig config) {
        int probability = intOption(config, "probability", 70, 0, 100);
        int maximumPerMethod = intOption(config, "max-per-method", 32, 0, 256);
        int maximumPerClass = intOption(config, "max-per-class", 128, 0, 2_048);
        int maximumMethodInstructions = intOption(config, "max-method-instructions", 6_000, 64, 50_000);
        boolean includeSynthetic = booleanOption(config, "include-synthetic", false);
        Set<String> prefixes = prefixes(config.getOption("owner-prefixes", DEFAULT_PREFIXES));
        Set<String> excludedOwners = prefixes(config.getOption(
                "excluded-owners",
                "java/io/PrintStream,java/io/Console"
        ));
        long configuredSeed = longOption(config, "seed", 0L);
        long runSeed = configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed;

        LongAdder totalChanged = new LongAdder();
        pool.forEachClass(owner -> {
            if (!shouldProcess(owner.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(owner.access)) {
                return;
            }
            Random random = new Random(runSeed ^ owner.name.hashCode());

            List<CallSite> sites = new ArrayList<>();
            for (MethodNode method : owner.methods) {
                if (method.instructions == null
                        || method.instructions.size() > maximumMethodInstructions
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || (!includeSynthetic && (method.access & Opcodes.ACC_SYNTHETIC) != 0)) {
                    continue;
                }
                int changedInMethod = 0;
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null
                             && changedInMethod < maximumPerMethod
                             && sites.size() < maximumPerClass;
                     instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && random.nextInt(100) < probability
                            && eligible(call, prefixes, excludedOwners)) {
                        sites.add(new CallSite(method, call));
                        changedInMethod++;
                    }
                }
                if (sites.size() >= maximumPerClass) {
                    break;
                }
            }
            if (sites.isEmpty()) {
                return;
            }

            String decoderName = uniqueMethodName(owner, random);
            String bootstrapName = uniqueMethodName(owner, random);
            Handle bootstrap = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    owner.name,
                    bootstrapName,
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)"
                            + "Ljava/lang/invoke/CallSite;",
                    false
            );

            for (CallSite site : sites) {
                MethodInsnNode call = site.call();
                int key = nonZeroKey(random);
                int kind = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
                InvokeDynamicInsnNode dynamic = new InvokeDynamicInsnNode(
                        randomIdentifier(random),
                        invocationDescriptor(call),
                        bootstrap,
                        encode(call.owner, key ^ OWNER_SALT),
                        encode(call.name, key ^ NAME_SALT),
                        encode(call.desc, key ^ DESC_SALT),
                        key,
                        kind
                );
                site.method().instructions.set(call, dynamic);
            }

            owner.version = Math.max(owner.version, Opcodes.V1_7);
            owner.methods.add(buildDecoder(decoderName));
            owner.methods.add(buildBootstrap(owner.name, bootstrapName, decoderName));
            pool.markDirty(owner.name);
            totalChanged.add(sites.size());
            detail("Replaced {} API calls with encrypted MethodHandle sites in {}", sites.size(), owner.name);
        });
        return totalChanged.intValue();
    }

    private boolean eligible(MethodInsnNode call, Set<String> prefixes, Set<String> excludedOwners) {
        int opcode = call.getOpcode();
        if (opcode != Opcodes.INVOKESTATIC
                && opcode != Opcodes.INVOKEVIRTUAL
                && opcode != Opcodes.INVOKEINTERFACE) {
            return false;
        }
        if (call.name.startsWith("<")
                || call.owner.startsWith("java/lang/invoke/")
                || call.owner.startsWith("java/lang/reflect/")
                || matchesPrefix(call.owner, excludedOwners)
                || !matchesPrefix(call.owner, prefixes)) {
            return false;
        }
        return isPublicApiMethod(call);
    }

    private boolean isPublicApiMethod(MethodInsnNode call) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> owner = Class.forName(call.owner.replace('/', '.'), false, loader);
            if (!Modifier.isPublic(owner.getModifiers())) {
                return false;
            }
            boolean expectedStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(call.name)
                        && Type.getMethodDescriptor(method).equals(call.desc)
                        && Modifier.isStatic(method.getModifiers()) == expectedStatic
                        && Modifier.isPublic(method.getModifiers())) {
                    return true;
                }
            }
        } catch (LinkageError | ReflectiveOperationException | SecurityException ignored) {
            return false;
        }
        return false;
    }

    private String invocationDescriptor(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return call.desc;
        }
        Type methodType = Type.getMethodType(call.desc);
        Type[] arguments = methodType.getArgumentTypes();
        Type[] invocationArguments = new Type[arguments.length + 1];
        invocationArguments[0] = Type.getObjectType(call.owner);
        System.arraycopy(arguments, 0, invocationArguments, 1, arguments.length);
        return Type.getMethodDescriptor(methodType.getReturnType(), invocationArguments);
    }

    private MethodNode buildBootstrap(String owner, String name, String decoderName) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)"
                        + "Ljava/lang/invoke/CallSite;",
                null,
                new String[]{"java/lang/Throwable"}
        );
        InsnList instructions = method.instructions;

        // Resolve the encrypted owner with the caller's defining class loader.
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        instructions.add(new LdcInsnNode(OWNER_SALT));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                decoderName,
                "(Ljava/lang/String;I)Ljava/lang/String;",
                false
        ));
        instructions.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
        instructions.add(new IntInsnNode(Opcodes.BIPUSH, '.'));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(CC)Ljava/lang/String;",
                false
        ));
        instructions.add(new InsnNode(Opcodes.ICONST_0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "lookupClass",
                "()Ljava/lang/Class;",
                false
        ));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getClassLoader",
                "()Ljava/lang/ClassLoader;",
                false
        ));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 8));

        // Decode the target descriptor and method name.
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        instructions.add(new LdcInsnNode(DESC_SALT));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                decoderName,
                "(Ljava/lang/String;I)Ljava/lang/String;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getClassLoader",
                "()Ljava/lang/ClassLoader;",
                false
        ));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodType",
                "fromMethodDescriptorString",
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 9));

        instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        instructions.add(new LdcInsnNode(NAME_SALT));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                decoderName,
                "(Ljava/lang/String;I)Ljava/lang/String;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 10));

        LabelNode virtualCall = new LabelNode(new Label());
        LabelNode resolved = new LabelNode(new Label());
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
        instructions.add(new JumpInsnNode(Opcodes.IFNE, virtualCall));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "publicLookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 10));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/MethodHandle;",
                false
        ));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, resolved));

        instructions.add(virtualCall);
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "publicLookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 10));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "findVirtual",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/MethodHandle;",
                false
        ));

        instructions.add(resolved);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "asType",
                "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                false
        ));
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        instructions.add(new InsnNode(Opcodes.DUP_X1));
        instructions.add(new InsnNode(Opcodes.SWAP));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/invoke/ConstantCallSite",
                "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V",
                false
        ));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 8;
        method.maxLocals = 11;
        return method;
    }

    private MethodNode buildDecoder(String name) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "(Ljava/lang/String;I)Ljava/lang/String;",
                null,
                null
        );
        InsnList instructions = method.instructions;
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Base64",
                "getDecoder",
                "()Ljava/util/Base64$Decoder;",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Base64$Decoder",
                "decode",
                "(Ljava/lang/String;)[B",
                false
        ));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        instructions.add(new InsnNode(Opcodes.ICONST_0));
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode loop = new LabelNode(new Label());
        LabelNode done = new LabelNode(new Label());
        instructions.add(loop);
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new InsnNode(Opcodes.BALOAD));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new LdcInsnNode(MIX_STEP));
        instructions.add(new InsnNode(Opcodes.IMUL));
        instructions.add(new InsnNode(Opcodes.IADD));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new InsnNode(Opcodes.BASTORE));
        instructions.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        instructions.add(done);
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "java/nio/charset/StandardCharsets",
                "UTF_8",
                "Ljava/nio/charset/Charset;"
        ));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/String",
                "<init>",
                "([BLjava/nio/charset/Charset;)V",
                false
        ));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 6;
        method.maxLocals = 4;
        return method;
    }

    private String encode(String value, int key) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i] ^ (key + i * MIX_STEP));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private Set<String> prefixes(String configured) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : configured.split("[,;\\s]+")) {
            if (!value.isBlank()) {
                result.add(value.trim().replace('.', '/'));
            }
        }
        return result;
    }

    private boolean matchesPrefix(String owner, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (owner.equals(prefix) || owner.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private String uniqueMethodName(ClassNode owner, Random random) {
        Set<String> names = new HashSet<>();
        for (MethodNode method : owner.methods) {
            names.add(method.name);
        }
        String name;
        do {
            name = randomIdentifier(random);
        } while (!names.add(name));
        return name;
    }

    private String randomIdentifier(Random random) {
        String first = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String rest = first + "0123456789";
        int length = 9 + random.nextInt(8);
        StringBuilder result = new StringBuilder(length);
        result.append(first.charAt(random.nextInt(first.length())));
        while (result.length() < length) {
            result.append(rest.charAt(random.nextInt(rest.length())));
        }
        return result.toString();
    }

    private int nonZeroKey(Random random) {
        int key;
        do {
            key = random.nextInt();
        } while (key == 0);
        return key;
    }

    private int intOption(TransformerConfig config, String key, int fallback, int minimum, int maximum) {
        Object value = config.getOptions().get(key);
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private long longOption(TransformerConfig config, String key, long fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private record CallSite(MethodNode method, MethodInsnNode call) {
    }
}
