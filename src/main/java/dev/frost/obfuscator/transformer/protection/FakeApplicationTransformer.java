package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;

public class FakeApplicationTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Map<String, List<String>> PROFILES = Map.ofEntries(
            Map.entry("minecraft-plugin", List.of("EssentialsManager", "PlayerDataManager", "EconomyHandler", "PermissionCache", "VaultBridge")),
            Map.entry("spring-boot", List.of("UserService", "AccountRepository", "LoginController", "JwtProvider")),
            Map.entry("crypto-miner-fake", List.of("HashEngine", "WorkerPool", "NonceGenerator", "MiningSession")),
            Map.entry("networking-stack", List.of("PacketHandler", "ConnectionManager", "SocketSession", "ProtocolManager")),
            Map.entry("machine-learning", List.of("NeuralNetwork", "TensorEngine", "GradientOptimizer", "LossFunction")),
            Map.entry("enterprise", List.of("AbstractTransactionManagerFactoryImpl", "SessionPersistenceProvider", "DefaultConfigurationResolver")),
            Map.entry("stackoverflow", List.of("NullPointerDiagnostics", "UnexpectedTokenResolver", "ArrayBoundsAdvisor")),
            Map.entry("conspiracy", List.of("Area51Manager", "MoonLandingController", "ReptilianDatabase", "CIAConnectionPool")),
            Map.entry("ai-framework", List.of("TransformerEncoder", "AttentionLayer", "GradientCheckpointManager", "ModelInferenceSession")),
            Map.entry("dll-illusion", List.of("Kernel32Bridge", "NativeProcessManager", "WindowsApiSession")),
            Map.entry("quantum", List.of("QubitState", "EntanglementManager", "QuantumTensor")),
            Map.entry("scp", List.of("SCP173Controller", "MemeticHazard", "ContainmentProcedure"))
    );

    @Override
    public String getName() {
        return "fake-application";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public Priority priority() {
        return Priority.PRE_OBFUSCATION;
    }

    @Override
    public void transform(Context context) {
        List<String> profiles = parseProfiles(context.config().getOption("profiles", "minecraft-plugin,networking-stack,enterprise"));
        int classesPerProfile = Math.max(1, getIntOption(context, "classes-per-profile", 3));
        int minMethods = Math.max(1, getIntOption(context, "min-methods-per-class", 8));
        int maxMethods = Math.max(minMethods, getIntOption(context, "max-methods-per-class", 24));
        int minFields = Math.max(0, getIntOption(context, "min-fields-per-class", 3));
        int maxFields = Math.max(minFields, getIntOption(context, "max-fields-per-class", 10));
        long seed = getLongOption(context, "seed", 0L);
        Random random = new Random(seed == 0L ? SECURE_RANDOM.nextLong() : seed);
        Set<String> reserved = new HashSet<>(context.pool().getClassMap().keySet());
        List<String> packages = existingPackages(context);
        int generated = 0;

        for (String profile : profiles) {
            List<String> names = PROFILES.getOrDefault(profile, PROFILES.get("enterprise"));
            for (int i = 0; i < classesPerProfile; i++) {
                String simple = names.get(i % names.size()) + (i >= names.size() ? i : "");
                String pkg = packages.isEmpty() ? profile.replace('-', '/') : packages.get(random.nextInt(packages.size()));
                String internal = unique(reserved, pkg + "/" + simple);
                ClassNode node = buildClass(internal, profile, randomRange(random, minMethods, maxMethods), randomRange(random, minFields, maxFields), random);
                context.pool().addClass(internal, node);
                context.pool().markDirty(internal);
                generated++;
            }
        }
        context.stats().add("fakeApplicationClasses", generated);
        log("Generated {} fake application profile classes", generated);
    }

    private ClassNode buildClass(String name, String profile, int methods, int fields, Random random) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = "java/lang/Object";
        node.signature = "Ljava/lang/Object;";
        node.sourceFile = name.substring(name.lastIndexOf('/') + 1) + ".java";

        List<String> fieldNames = new ArrayList<>();
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "PROFILE", "Ljava/lang/String;", null, profile));
        for (int i = 0; i < fields; i++) {
            String field = uniqueFieldName(fieldNames, profileFieldName(profile, i));
            fieldNames.add(field);
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, field, fieldDesc(profile, i), null, null));
        }

        node.methods.add(constructor(node.name, fieldNames, node.fields, random));
        node.methods.add(staticInit(profile));

        List<MethodSpec> specs = profileMethods(profile);
        Set<String> usedMethods = new HashSet<>();
        for (int i = 0; i < methods; i++) {
            MethodSpec spec = specs.get(i % specs.size());
            String methodName = uniqueMethodName(usedMethods, spec.name(), spec.desc(), i / specs.size());
            node.methods.add(buildMethod(methodName, spec.desc(), spec.kind(), profile, random));
        }
        return node;
    }

    private MethodNode constructor(String owner, List<String> fieldNames, List<FieldNode> fields, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        for (int i = 0; i < fieldNames.size(); i++) {
            FieldNode field = fields.get(i + 1);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            addDefaultValue(method.instructions, field.desc, i, random);
            method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field.name, field.desc));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 4;
        method.maxLocals = 1;
        return method;
    }

    private MethodNode staticInit(String profile) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.instructions.add(new LdcInsnNode(profile));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        return method;
    }

    private MethodNode buildMethod(String name, String desc, MethodKind kind, String profile, Random random) {
        return switch (kind) {
            case SCORE -> scoreMethod(name, desc, random);
            case STRING -> stringMethod(name, desc, profile);
            case BOOLEAN -> booleanMethod(name, desc, profile);
            case SIZE -> sizeMethod(name, desc);
            case LONG -> longMethod(name, desc, random);
            case VOID -> voidMethod(name, desc);
        };
    }

    private MethodNode scoreMethod(String name, String desc, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        int salt = random.nextInt(512) + 17;
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new LdcInsnNode(salt));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ICONST_4));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.instructions.add(new IincInsnNode(3, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        method.instructions.add(done);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 4;
        return method;
    }

    private MethodNode stringMethod(String name, String desc, String profile) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        LabelNode notNull = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        method.instructions.add(new LdcInsnNode(profile));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(notNull);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new LdcInsnNode(":" + profile));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode booleanMethod(String name, String desc, String profile) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        LabelNode check = new LabelNode();
        LabelNode done = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, check));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(check);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
        method.instructions.add(new LdcInsnNode(profile.split("-")[0]));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode sizeMethod(String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        LabelNode notNull = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(notNull);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode longMethod(String name, String desc, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        method.instructions.add(new LdcInsnNode(random.nextLong()));
        method.instructions.add(new InsnNode(Opcodes.LXOR));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.maxStack = 4;
        method.maxLocals = 3;
        return method;
    }

    private MethodNode voidMethod(String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        return method;
    }

    private void addDefaultValue(InsnList instructions, String desc, int index, Random random) {
        switch (desc) {
            case "I", "Z" -> instructions.add(new LdcInsnNode(index + random.nextInt(32)));
            case "J" -> instructions.add(new LdcInsnNode((long) index + random.nextInt(64)));
            default -> instructions.add(new LdcInsnNode("cache-" + index));
        }
    }

    private String fieldDesc(String profile, int index) {
        return switch (index % 4) {
            case 0 -> "I";
            case 1 -> "J";
            case 2 -> "Z";
            default -> "Ljava/lang/String;";
        };
    }

    private String profileFieldName(String profile, int index) {
        List<String> names = switch (profile) {
            case "minecraft-plugin" -> List.of("onlinePlayers", "permissionEpoch", "vaultReady", "lastWorldName", "economyBalance", "cachedRank");
            case "spring-boot" -> List.of("beanCount", "refreshTimestamp", "transactional", "contextId", "repositoryHits", "jwtIssuer");
            case "crypto-miner-fake" -> List.of("nonceOffset", "workerEpoch", "hashingPaused", "sessionLabel", "acceptedShares", "poolName");
            case "networking-stack" -> List.of("packetWindow", "lastHeartbeat", "connected", "protocolName", "retryBudget", "remoteAddress");
            case "machine-learning", "ai-framework" -> List.of("layerCount", "checkpointStep", "training", "modelName", "tensorWidth", "lossName");
            case "dll-illusion" -> List.of("lastError", "nativeHandle", "loaded", "libraryName", "sessionId", "exportName");
            case "quantum" -> List.of("qubitCount", "measurementTick", "entangled", "registerName", "gateDepth", "backendName");
            case "scp" -> List.of("clearanceLevel", "containmentTick", "breachActive", "procedureName", "hazardScore", "siteName");
            default -> List.of("stateVersion", "lastRefresh", "enabled", "providerName", "cacheSize", "resolverName");
        };
        return names.get(index % names.size()) + (index >= names.size() ? index : "");
    }

    private List<MethodSpec> profileMethods(String profile) {
        List<String> names = switch (profile) {
            case "minecraft-plugin" -> List.of("loadPlayerData", "savePlayerData", "calculateEconomyBalance", "hasPermissionCached", "syncVaultBridge", "reloadWorldSettings", "resolveCommandAlias", "flushPlayerCache", "isPlayerOnline", "formatChatPrefix");
            case "spring-boot" -> List.of("resolveUserById", "refreshSecurityContext", "validateJwtAudience", "mapLoginRequest", "countActiveSessions", "invalidateToken", "loadRepositoryPage", "normalizeUsername", "isTransactionalCall", "buildControllerRoute");
            case "crypto-miner-fake" -> List.of("prepareNonceWindow", "calculateFakeHashRate", "rotateWorkerSeed", "isShareAccepted", "formatMiningSession", "resetWorkerPool", "measureSyntheticLatency", "selectNonceRange", "validatePoolLabel", "closeMiningSession");
            case "networking-stack" -> List.of("decodePacketFrame", "encodePacketFrame", "openSocketSession", "closeConnection", "isProtocolSupported", "countPendingPackets", "normalizeRemoteAddress", "refreshHeartbeat", "selectRetryDelay", "drainPacketQueue");
            case "machine-learning" -> List.of("evaluateTensorSlice", "normalizeInputVector", "calculateLossValue", "applyGradientStep", "isLayerFrozen", "countTrainableWeights", "formatModelSummary", "resetOptimizerState", "loadCheckpointName", "closeTrainingSession");
            case "enterprise" -> List.of("resolveConfiguration", "createTransactionScope", "isSessionPersistent", "countPendingMigrations", "normalizeProviderName", "refreshResolverCache", "closePersistenceContext", "calculateRetryBudget", "loadDefaultPolicy", "commitWorkflowStage");
            case "stackoverflow" -> List.of("formatStackTrace", "isNullPointerPattern", "countUnexpectedTokens", "resolveQuestionSlug", "normalizeExceptionName", "cacheAcceptedAnswer", "clearDiagnosticContext", "calculateLineOffset", "buildErrorMessage", "isDuplicateQuestion");
            case "conspiracy" -> List.of("loadArea51Manifest", "verifyMoonLandingTape", "isReptilianRecord", "countRedactedFiles", "normalizeAgencyName", "openCIAConnection", "closeDisclosureSession", "calculateTinfoilIndex", "resolveWitnessAlias", "refreshSatelliteCache");
            case "ai-framework" -> List.of("encodeAttentionMask", "runInferenceStep", "isCheckpointLoaded", "countAttentionHeads", "normalizePromptText", "resetGradientCheckpoint", "formatTensorName", "closeModelSession", "calculateTokenBudget", "loadAdapterWeights");
            case "dll-illusion" -> List.of("queryNativeStatus", "resolveKernelSymbol", "isProcessHandleValid", "countLoadedExports", "normalizeLibraryName", "openWindowsSession", "closeNativeBridge", "calculateLastError", "formatPointerValue", "refreshModuleCache");
            case "quantum" -> List.of("collapseQubitState", "measureEntanglement", "isGateSupported", "countQuantumRegisters", "normalizeCircuitName", "resetQubitRegister", "calculatePhaseShift", "formatMeasurement", "loadBackendName", "closeQuantumSession");
            case "scp" -> List.of("applyContainmentStep", "isMemeticHazard", "countBreachEvents", "normalizeProcedureName", "loadClearanceRecord", "refreshSiteStatus", "calculateThreatIndex", "closeContainmentLog", "formatIncidentReport", "resolveObjectClass");
            default -> List.of("resolveConfiguration", "refreshCache", "isEnabled", "countItems", "normalizeName", "closeSession", "calculateScore", "formatStatus", "loadDefaultValue", "commitState");
        };
        List<MethodSpec> specs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            MethodKind kind = switch (i % 6) {
                case 0 -> MethodKind.STRING;
                case 1 -> MethodKind.VOID;
                case 2 -> MethodKind.BOOLEAN;
                case 3 -> MethodKind.SIZE;
                case 4 -> MethodKind.LONG;
                default -> MethodKind.SCORE;
            };
            specs.add(new MethodSpec(names.get(i), descFor(kind), kind));
        }
        return specs;
    }

    private String descFor(MethodKind kind) {
        return switch (kind) {
            case STRING -> "(Ljava/lang/String;)Ljava/lang/String;";
            case BOOLEAN -> "(Ljava/lang/String;)Z";
            case SIZE -> "(Ljava/util/Map;)I";
            case LONG -> "(J)J";
            case VOID -> "()V";
            case SCORE -> "(I)I";
        };
    }

    private String uniqueFieldName(List<String> used, String base) {
        String name = base;
        int suffix = 0;
        while (used.contains(name)) {
            name = base + (++suffix);
        }
        return name;
    }

    private String uniqueMethodName(Set<String> used, String name, String desc, int suffix) {
        String candidate = suffix == 0 ? name : name + suffix;
        while (!used.add(candidate + desc)) {
            candidate = name + (++suffix);
        }
        return candidate;
    }

    private enum MethodKind {
        SCORE,
        STRING,
        BOOLEAN,
        SIZE,
        LONG,
        VOID
    }

    private record MethodSpec(String name, String desc, MethodKind kind) {
    }

    private List<String> parseProfiles(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.isEmpty() ? List.of("enterprise") : result;
    }

    private List<String> existingPackages(Context context) {
        Set<String> packages = new HashSet<>();
        for (String name : context.pool().getClassMap().keySet()) {
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash > 0 && !name.startsWith("__frost/")) {
                packages.add(name.substring(0, lastSlash));
            }
        }
        return new ArrayList<>(packages);
    }

    private String unique(Set<String> reserved, String base) {
        String current = base;
        int suffix = 0;
        while (!reserved.add(current)) {
            current = base + (++suffix);
        }
        return current;
    }

    private int randomRange(Random random, int min, int max) {
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private int getIntOption(Context context, String key, int fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private long getLongOption(Context context, String key, long fallback) {
        Object value = context.config().getOptions().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
