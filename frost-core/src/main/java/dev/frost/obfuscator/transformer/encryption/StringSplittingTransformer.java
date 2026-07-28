package dev.frost.obfuscator.transformer.encryption;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.rename.MethodNameAllocator;
import dev.frost.obfuscator.util.AccessHelper;
import dev.frost.obfuscator.util.ASMHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Splits executable string constants into independently encoded fragments.
 *
 * <p>Fragments, assembly methods, and optional relay methods are injected into
 * safe pre-existing application classes. The original use site retains only
 * one relocated call. This pass runs before mapping collection, so all injected
 * owners, members, and call sites participate in later renaming, flow,
 * indirection, and {@link StringEncryptionTransformer} passes.</p>
 */
public final class StringSplittingTransformer extends Transformer {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final String FRAGMENT_METHOD_DESC = "()" + STRING_DESC;
    private static final String DECODER_METHOD_DESC = "(" + STRING_DESC + "I)" + STRING_DESC;
    private static final int MIX_STEP = 0x9E37;

    @Override
    public String getName() {
        return "string-splitting";
    }

    @Override
    public String getCategory() {
        return "Constants";
    }

    @Override
    public void transform(Context context) {
        Result result = apply(context.pool(), context.mappings(), context.config());
        context.stats().add("splitStrings", result.strings());
        context.stats().add("stringFragments", result.fragments());
        context.stats().add("stringCarrierClasses", result.carriers());
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        apply(pool, mappings, config);
    }

    private Result apply(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int minimumLength = intOption(config, "min-length", 4, 2, 65_535);
        int minimumFragments = intOption(config, "min-fragments", 2, 2, 64);
        int maximumFragments = intOption(config, "max-fragments", 32, minimumFragments, 128);
        int maximumFragmentLength = intOption(config, "max-fragment-length", 4, 1, 1_024);
        int requestedCarriers = intOption(config, "carrier-classes", 4, 1, 32);
        int indirectionDepth = intOption(config, "indirection-depth", 1, 0, 4);
        int decoysPerString = intOption(config, "decoys-per-string", 1, 0, 8);
        int maximumStringsPerClass = intOption(config, "max-strings-per-class", 256, 1, 4_096);
        int maximumMethodInstructions = intOption(config, "max-method-instructions", 6_000, 64, 65_000);
        int maximumOutputMethodInstructions = intOption(
                config,
                "max-output-method-instructions",
                12_000,
                maximumMethodInstructions,
                65_000
        );
        boolean encodeFragments = booleanOption(config, "encode-fragments", true);
        boolean preserveReflectionStrings = booleanOption(config, "preserve-reflection-strings", true);
        long configuredSeed = longOption(config, "seed", 0L);
        Random random = new Random(configuredSeed == 0L ? SECURE_RANDOM.nextLong() : configuredSeed);
        GeneratedMethodNamer methodNames = new GeneratedMethodNamer(
                mappings.methodNames(config.getDictionary(), pool.getClasses()),
                mappings
        );

        List<ClassNode> sourceClasses = new ArrayList<>(pool.getClasses());
        List<StringSite> sites = new ArrayList<>();
        List<ClassNode> eligibleClasses = new ArrayList<>();
        int materializedFields = 0;
        int normalizedConcats = 0;

        for (ClassNode classNode : sourceClasses) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)
                    || "module-info".equals(classNode.name)) {
                continue;
            }
            if (preserveReflectionStrings && usesNameBasedReflection(classNode)) {
                continue;
            }

            eligibleClasses.add(classNode);
            normalizedConcats += normalizeStringConcatenations(classNode, maximumMethodInstructions);
            materializedFields += materializeStringConstantFields(classNode);
            int collected = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null
                        || method.instructions.size() > maximumOutputMethodInstructions
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    continue;
                }
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null && collected < maximumStringsPerClass;
                     instruction = instruction.getNext()) {
                    if (instruction instanceof LdcInsnNode ldc
                            && ldc.cst instanceof String value
                            && codePointCount(value) >= minimumLength
                            && (!preserveReflectionStrings || !isKnownClassName(value, pool))) {
                        sites.add(new StringSite(classNode, method, ldc, value));
                        collected++;
                    }
                }
            }
        }

        if (sites.isEmpty()) {
            if (materializedFields > 0) {
                log("Materialized {} constant fields; no eligible literals remained", materializedFields);
            }
            return new Result(0, 0, 0);
        }

        List<SplitPlan> plans = new ArrayList<>(sites.size());
        Map<MethodNode, Integer> projectedMethodSizes = new IdentityHashMap<>();
        int fragmentCount = 0;
        int skippedForGrowth = 0;
        for (StringSite site : sites) {
            List<String> fragments = split(site.value(), minimumFragments, maximumFragments,
                    maximumFragmentLength, random);
            if (fragments.size() < 2) {
                continue;
            }
            int currentSize = projectedMethodSizes.getOrDefault(
                    site.method(),
                    site.method().instructions.size()
            );
            int projectedSize = currentSize + fragments.size() * 2 + 3;
            if (projectedSize > maximumOutputMethodInstructions) {
                skippedForGrowth++;
                continue;
            }
            projectedMethodSizes.put(site.method(), projectedSize);
            plans.add(new SplitPlan(site, fragments));
            fragmentCount += fragments.size();
        }
        if (plans.isEmpty()) {
            return new Result(0, 0, 0);
        }

        List<ClassNode> safeRemoteCarriers = eligibleClasses.stream()
                .filter(this::isSafeRemoteCarrier)
                .toList();
        Map<ClassNode, Carrier> carrierStates = new IdentityHashMap<>();
        Set<ClassNode> touchedCarriers = Collections.newSetFromMap(new IdentityHashMap<>());

        for (SplitPlan plan : plans) {
            List<ClassNode> available = availableCarriers(
                    plan.site().owner(),
                    safeRemoteCarriers,
                    requestedCarriers,
                    random
            );
            ClassNode assemblyHost = chooseRemoteFirst(plan.site().owner(), available, random);
            List<ClassNode> relayHosts = chooseRelayHosts(
                    plan.site().owner(),
                    assemblyHost,
                    available,
                    indirectionDepth,
                    random
            );

            List<FragmentReference> references = new ArrayList<>(plan.fragments().size());
            int step = coprimeStep(available.size(), random);
            int cursor = random.nextInt(available.size());
            for (String fragment : plan.fragments()) {
                ClassNode carrierNode = available.get(cursor);
                Carrier carrier = carrierStates.computeIfAbsent(carrierNode, Carrier::new);
                references.add(addFragmentAccessor(
                        carrier,
                        assemblyHost.name,
                        fragment,
                        encodeFragments,
                        random,
                        methodNames
                ));
                touchedCarriers.add(carrierNode);
                cursor = Math.floorMod(cursor + step, available.size());
            }

            String immediateCaller = relayHosts.isEmpty()
                    ? plan.site().owner().name
                    : relayHosts.get(relayHosts.size() - 1).name;
            FragmentReference entry = addAssemblyMethod(
                    assemblyHost,
                    immediateCaller,
                    references,
                    methodNames
            );
            touchedCarriers.add(assemblyHost);

            for (int i = relayHosts.size() - 1; i >= 0; i--) {
                ClassNode relayHost = relayHosts.get(i);
                String relayCaller = i == 0
                        ? plan.site().owner().name
                        : relayHosts.get(i - 1).name;
                entry = addRelayMethod(relayHost, relayCaller, entry, methodNames);
                touchedCarriers.add(relayHost);
            }
            replaceWithCall(plan.site(), entry);

            for (int i = 0; i < decoysPerString; i++) {
                ClassNode decoyNode = available.get(random.nextInt(available.size()));
                Carrier carrier = carrierStates.computeIfAbsent(decoyNode, Carrier::new);
                addFragmentAccessor(carrier, decoyNode.name, randomDecoy(random), encodeFragments,
                        random, methodNames);
                touchedCarriers.add(decoyNode);
            }
            pool.markDirty(plan.site().owner().name);
        }

        for (ClassNode carrier : touchedCarriers) {
            pool.markDirty(carrier.name);
        }

        log("Split {} strings into {} live fragments inside {} existing classes ({} decoys, {} materialized fields, {} normalized concats, {} growth-limited)",
                plans.size(), fragmentCount, touchedCarriers.size(), plans.size() * decoysPerString,
                materializedFields, normalizedConcats, skippedForGrowth);
        return new Result(plans.size(), fragmentCount, touchedCarriers.size());
    }

    private boolean usesNameBasedReflection(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if ("java/lang/Class".equals(call.owner)
                        && Set.of(
                        "forName",
                        "getMethod",
                        "getDeclaredMethod",
                        "getField",
                        "getDeclaredField"
                ).contains(call.name)) {
                    return true;
                }
                if ("java/lang/invoke/MethodHandles$Lookup".equals(call.owner)
                        && call.name.startsWith("find")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isKnownClassName(String value, ClassPool pool) {
        String internalName = value.replace('.', '/');
        return pool.contains(internalName)
                || pool.getLibraryClasses().containsKey(internalName);
    }

    /**
     * Modern javac stores fixed concat text in StringConcatFactory bootstrap
     * recipes rather than executable LDC instructions. Expand those call sites
     * first so the embedded literals enter the normal splitting pipeline.
     */
    private int normalizeStringConcatenations(ClassNode owner, int maximumMethodInstructions) {
        int changed = 0;
        for (MethodNode method : owner.methods) {
            if (method.instructions == null
                    || method.instructions.size() > maximumMethodInstructions
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            List<InvokeDynamicInsnNode> concatenations = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof InvokeDynamicInsnNode dynamic
                        && isStringConcat(dynamic)) {
                    concatenations.add(dynamic);
                }
            }
            for (InvokeDynamicInsnNode dynamic : concatenations) {
                InsnList replacement = expandStringConcat(method, dynamic);
                if (replacement == null) {
                    continue;
                }
                method.instructions.insertBefore(dynamic, replacement);
                method.instructions.remove(dynamic);
                changed++;
            }
        }
        return changed;
    }

    private boolean isStringConcat(InvokeDynamicInsnNode dynamic) {
        return dynamic.bsm != null
                && "java/lang/invoke/StringConcatFactory".equals(dynamic.bsm.getOwner())
                && ("makeConcatWithConstants".equals(dynamic.bsm.getName())
                || "makeConcat".equals(dynamic.bsm.getName()))
                && Type.getReturnType(dynamic.desc).equals(Type.getType(String.class));
    }

    private InsnList expandStringConcat(MethodNode method, InvokeDynamicInsnNode dynamic) {
        Type[] argumentTypes = Type.getArgumentTypes(dynamic.desc);
        String recipe;
        Object[] constants;
        if ("makeConcat".equals(dynamic.bsm.getName())) {
            recipe = "\u0001".repeat(argumentTypes.length);
            constants = new Object[0];
        } else {
            if (dynamic.bsmArgs.length == 0 || !(dynamic.bsmArgs[0] instanceof String value)) {
                return null;
            }
            recipe = value;
            constants = new Object[dynamic.bsmArgs.length - 1];
            System.arraycopy(dynamic.bsmArgs, 1, constants, 0, constants.length);
        }

        if (count(recipe, '\u0001') != argumentTypes.length
                || count(recipe, '\u0002') != constants.length) {
            return null;
        }

        int[] localSlots = new int[argumentTypes.length];
        int nextLocal = ASMHelper.nextFreeLocal(method);
        for (int i = 0; i < argumentTypes.length; i++) {
            localSlots[i] = nextLocal;
            nextLocal += argumentTypes[i].getSize();
        }
        method.maxLocals = Math.max(method.maxLocals, nextLocal);

        InsnList replacement = new InsnList();
        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            replacement.add(new VarInsnNode(argumentTypes[i].getOpcode(Opcodes.ISTORE), localSlots[i]));
        }
        replacement.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "()V",
                false
        ));

        int dynamicIndex = 0;
        int constantIndex = 0;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char current = recipe.charAt(i);
            if (current != '\u0001' && current != '\u0002') {
                literal.append(current);
                continue;
            }
            appendLiteral(replacement, literal);
            if (current == '\u0001') {
                Type type = argumentTypes[dynamicIndex];
                replacement.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), localSlots[dynamicIndex]));
                appendValue(replacement, type);
                dynamicIndex++;
            } else {
                Object constant = constants[constantIndex++];
                replacement.add(new LdcInsnNode(constant));
                appendValue(replacement, constantType(constant));
            }
        }
        appendLiteral(replacement, literal);
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()" + STRING_DESC,
                false
        ));
        return replacement;
    }

    private void appendLiteral(InsnList instructions, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        instructions.add(new LdcInsnNode(literal.toString()));
        appendValue(instructions, Type.getType(String.class));
        literal.setLength(0);
    }

    private void appendValue(InsnList instructions, Type type) {
        String descriptor = switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case Type.CHAR -> "(C)Ljava/lang/StringBuilder;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;";
            case Type.FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case Type.LONG -> "(J)Ljava/lang/StringBuilder;";
            case Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            case Type.OBJECT -> "java/lang/String".equals(type.getInternalName())
                    ? "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                    : "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
            default -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                descriptor,
                false
        ));
    }

    private Type constantType(Object constant) {
        if (constant instanceof Integer) {
            return Type.INT_TYPE;
        }
        if (constant instanceof Float) {
            return Type.FLOAT_TYPE;
        }
        if (constant instanceof Long) {
            return Type.LONG_TYPE;
        }
        if (constant instanceof Double) {
            return Type.DOUBLE_TYPE;
        }
        if (constant instanceof String) {
            return Type.getType(String.class);
        }
        return Type.getType(Object.class);
    }

    private int count(String value, char needle) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == needle) {
                result++;
            }
        }
        return result;
    }

    private List<ClassNode> availableCarriers(ClassNode source,
                                              List<ClassNode> safeRemoteCarriers,
                                              int requested,
                                              Random random) {
        List<ClassNode> remote = safeRemoteCarriers.stream()
                .filter(candidate -> candidate != source)
                .filter(candidate -> isClassAccessible(source.name, candidate))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(remote, random);

        List<ClassNode> result = new ArrayList<>(Math.min(requested, remote.size() + 1));
        for (ClassNode candidate : remote) {
            if (result.size() >= requested) {
                break;
            }
            result.add(candidate);
        }
        if (result.size() < requested || result.isEmpty()) {
            result.add(source);
        }
        return result;
    }

    private ClassNode chooseRemoteFirst(ClassNode source, List<ClassNode> available, Random random) {
        List<ClassNode> remote = available.stream().filter(candidate -> candidate != source).toList();
        return remote.isEmpty() ? source : remote.get(random.nextInt(remote.size()));
    }

    private List<ClassNode> chooseRelayHosts(ClassNode source,
                                             ClassNode assemblyHost,
                                             List<ClassNode> available,
                                             int depth,
                                             Random random) {
        if (depth <= 0) {
            return List.of();
        }
        List<ClassNode> choices = new ArrayList<>(available);
        choices.remove(source);
        choices.remove(assemblyHost);
        Collections.shuffle(choices, random);

        List<ClassNode> reversed = new ArrayList<>();
        ClassNode target = assemblyHost;
        for (ClassNode candidate : choices) {
            if (reversed.size() >= depth) {
                break;
            }
            if (isClassAccessible(candidate.name, target)
                    && isClassAccessible(source.name, candidate)) {
                reversed.add(candidate);
                target = candidate;
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private boolean isSafeRemoteCarrier(ClassNode candidate) {
        if (AccessHelper.isInterface(candidate.access)
                || (candidate.access & (Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION)) != 0
                || "module-info".equals(candidate.name)
                || candidate.name.endsWith("/package-info")
                || !"java/lang/Object".equals(candidate.superName)) {
            return false;
        }
        return candidate.methods.stream().noneMatch(method -> "<clinit>".equals(method.name));
    }

    private boolean isClassAccessible(String callerOwner, ClassNode target) {
        return (target.access & Opcodes.ACC_PUBLIC) != 0
                || packageName(callerOwner).equals(packageName(target.name));
    }

    private int injectedMethodAccess(String callerOwner, ClassNode target) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if (!packageName(callerOwner).equals(packageName(target.name))) {
            access |= Opcodes.ACC_PUBLIC;
        }
        return access;
    }

    private FragmentReference addFragmentAccessor(Carrier carrier,
                                                    String callerOwner,
                                                    String fragment,
                                                    boolean encode,
                                                    Random random,
                                                    GeneratedMethodNamer methodNames) {
        String methodName = methodNames.next(carrier.node(), FRAGMENT_METHOD_DESC);
        MethodNode method = new MethodNode(
                injectedMethodAccess(callerOwner, carrier.node()),
                methodName,
                FRAGMENT_METHOD_DESC,
                null,
                null
        );

        if (encode) {
            if (carrier.decoder() == null) {
                String decoder = methodNames.next(carrier.node(), DECODER_METHOD_DESC);
                addDecoder(carrier.node(), decoder);
                carrier.decoder(decoder);
            }
            int key;
            String encoded;
            do {
                key = random.nextInt();
                encoded = encode(fragment, key);
            } while (key == 0 || encoded.equals(fragment));
            method.instructions.add(new LdcInsnNode(encoded));
            method.instructions.add(new LdcInsnNode(key));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    carrier.node().name,
                    carrier.decoder(),
                    "(" + STRING_DESC + "I)" + STRING_DESC,
                    false
            ));
        } else {
            method.instructions.add(new LdcInsnNode(fragment));
        }
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        carrier.node().methods.add(method);
        return new FragmentReference(carrier.node().name, methodName);
    }

    private FragmentReference addAssemblyMethod(ClassNode owner,
                                                String callerOwner,
                                                List<FragmentReference> references,
                                                GeneratedMethodNamer methodNames) {
        String methodName = methodNames.next(owner, FRAGMENT_METHOD_DESC);
        MethodNode method = new MethodNode(
                injectedMethodAccess(callerOwner, owner),
                methodName,
                FRAGMENT_METHOD_DESC,
                null,
                null
        );
        InsnList instructions = method.instructions;
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "()V",
                false
        ));
        for (FragmentReference reference : references) {
            instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    reference.owner(),
                    reference.method(),
                    "()" + STRING_DESC,
                    false
            ));
            instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(" + STRING_DESC + ")Ljava/lang/StringBuilder;",
                    false
            ));
        }
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()" + STRING_DESC,
                false
        ));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        owner.methods.add(method);
        return new FragmentReference(owner.name, methodName);
    }

    private FragmentReference addRelayMethod(ClassNode owner,
                                             String callerOwner,
                                             FragmentReference target,
                                             GeneratedMethodNamer methodNames) {
        String methodName = methodNames.next(owner, FRAGMENT_METHOD_DESC);
        MethodNode method = new MethodNode(
                injectedMethodAccess(callerOwner, owner),
                methodName,
                FRAGMENT_METHOD_DESC,
                null,
                null
        );
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                target.owner(),
                target.method(),
                "()" + STRING_DESC,
                false
        ));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        owner.methods.add(method);
        return new FragmentReference(owner.name, methodName);
    }

    private void addDecoder(ClassNode owner, String methodName) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                methodName,
                DECODER_METHOD_DESC,
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
        instructions.add(new IincInsnNode(3, 1));
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
        owner.methods.add(method);
    }

    private void replaceWithCall(StringSite site, FragmentReference entry) {
        InsnList replacement = new InsnList();
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                entry.owner(),
                entry.method(),
                "()" + STRING_DESC,
                false
        ));
        site.method().instructions.insertBefore(site.literal(), replacement);
        site.method().instructions.remove(site.literal());
    }

    private int materializeStringConstantFields(ClassNode classNode) {
        InsnList assignments = new InsnList();
        int changed = 0;
        for (FieldNode field : classNode.fields) {
            if (!STRING_DESC.equals(field.desc)
                    || !(field.value instanceof String value)
                    || (field.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            field.value = null;
            assignments.add(new LdcInsnNode(value));
            assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, field.desc));
            changed++;
        }
        if (changed > 0) {
            insertIntoClinit(classNode, assignments);
        }
        return changed;
    }

    private void insertIntoClinit(ClassNode classNode, InsnList assignments) {
        MethodNode clinit = classNode.methods.stream()
                .filter(method -> "<clinit>".equals(method.name))
                .findFirst()
                .orElse(null);
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(clinit);
        }
        AbstractInsnNode returnInstruction = clinit.instructions.getLast();
        while (returnInstruction != null && returnInstruction.getOpcode() != Opcodes.RETURN) {
            returnInstruction = returnInstruction.getPrevious();
        }
        if (returnInstruction == null) {
            clinit.instructions.add(assignments);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(returnInstruction, assignments);
        }
    }

    private List<String> split(String value,
                               int minimumFragments,
                               int maximumFragments,
                               int maximumFragmentLength,
                               Random random) {
        List<String> units = codePointUnits(value);
        if (units.size() < 2) {
            return List.of(value);
        }

        int upper = Math.min(maximumFragments, units.size());
        int requiredForSize = (units.size() + maximumFragmentLength - 1) / maximumFragmentLength;
        int lower = Math.min(upper, Math.max(minimumFragments, requiredForSize));
        int count = lower == upper ? lower : lower + random.nextInt(upper - lower + 1);
        int effectiveCapacity = Math.max(maximumFragmentLength, (units.size() + count - 1) / count);

        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            lengths[i] = 1;
        }
        int remaining = units.size() - count;
        while (remaining > 0) {
            int index = random.nextInt(count);
            if (lengths[index] < effectiveCapacity) {
                lengths[index]++;
                remaining--;
            }
        }

        List<String> result = new ArrayList<>(count);
        int unitIndex = 0;
        for (int length : lengths) {
            StringBuilder fragment = new StringBuilder();
            for (int i = 0; i < length; i++) {
                fragment.append(units.get(unitIndex++));
            }
            result.add(fragment.toString());
        }
        return result;
    }

    private List<String> codePointUnits(String value) {
        List<String> units = new ArrayList<>(codePointCount(value));
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int next = offset + Character.charCount(codePoint);
            units.add(value.substring(offset, next));
            offset = next;
        }
        return units;
    }

    private int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private String encode(String value, int key) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i] ^ (key + i * MIX_STEP));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private int coprimeStep(int modulus, Random random) {
        if (modulus <= 1) {
            return 1;
        }
        int step;
        do {
            step = 1 + random.nextInt(modulus - 1);
        } while (greatestCommonDivisor(step, modulus) != 1);
        return step;
    }

    private int greatestCommonDivisor(int left, int right) {
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return Math.abs(left);
    }

    private String randomDecoy(Random random) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
        int length = 2 + random.nextInt(7);
        StringBuilder result = new StringBuilder(length);
        while (result.length() < length) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private String packageName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
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

    private record StringSite(ClassNode owner, MethodNode method, LdcInsnNode literal, String value) {
    }

    private record SplitPlan(StringSite site, List<String> fragments) {
    }

    private static final class Carrier {
        private final ClassNode node;
        private String decoder;

        private Carrier(ClassNode node) {
            this.node = node;
        }

        private ClassNode node() {
            return node;
        }

        private String decoder() {
            return decoder;
        }

        private void decoder(String decoder) {
            this.decoder = decoder;
        }
    }

    private record FragmentReference(String owner, String method) {
    }

    private record GeneratedMethodNamer(MethodNameAllocator allocator, MappingCollector mappings) {
        private String next(ClassNode owner, String descriptor) {
            String name = allocator.next(owner.name, descriptor);
            mappings.preserveMethod(owner.name, name, descriptor);
            return name;
        }
    }

    private record Result(int strings, int fragments, int carriers) {
    }
}
