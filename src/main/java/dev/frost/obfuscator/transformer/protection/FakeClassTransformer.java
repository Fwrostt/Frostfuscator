package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.dictionary.Dictionary;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class FakeClassTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<String> FIELD_NAMES = List.of(
            "stateVersion", "lastRefreshTime", "enabled", "cacheName", "retryBudget", "sessionLabel",
            "pendingCount", "checksumSeed", "initialized", "providerName", "activeProfile", "windowSize",
            "lastErrorCode", "requestCount", "dirty", "contextPath", "resolverName", "dispatchIndex"
    );
    private static final List<String> METHOD_NAMES = List.of(
            "loadCache", "saveCache", "resolveContext", "validateState", "mergeOptions", "countEntries",
            "formatStatus", "isReady", "closeSession", "refreshSnapshot", "calculateChecksum", "normalizeName",
            "applyDefaults", "resetState", "buildLookupKey", "shouldRetry", "drainQueue", "mapIdentifier",
            "commitChanges", "readProperty", "writeProperty", "openSession", "resolveProvider", "compactCache",
            "verifyChecksum", "createSnapshot", "updateMetrics", "loadDefaultProfile", "flushPendingWrites",
            "calculateRetryDelay", "mergeStateVersion", "hasFeatureFlag", "formatDiagnosticLine", "selectCandidate"
    );

    @Override
    public String getName() {
        return "fake-classes";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public Priority priority() {
        return Priority.PRE_OBFUSCATION;
    }

    @Override
    public void transform(Context context) {
        int count = clamp(getIntOption(context, "count", 12), 0, 500);
        int minMethods = clamp(getIntOption(context, "min-methods-per-class", getIntOption(context, "methods-per-class", 8)), 0, 200);
        int maxMethods = clamp(getIntOption(context, "max-methods-per-class", getIntOption(context, "methods-per-class", 24)), minMethods, 200);
        int minFields = clamp(getIntOption(context, "min-fields-per-class", getIntOption(context, "fields-per-class", 2)), 0, 64);
        int maxFields = clamp(getIntOption(context, "max-fields-per-class", getIntOption(context, "fields-per-class", 8)), minFields, 64);
        String placement = context.config().getOption("placement", "package-mode").toLowerCase();
        String naming = context.config().getOption("naming", "dictionary").toLowerCase();
        String customPattern = context.config().getOption("custom-pattern", "Fake{index}");
        long seed = getLongOption(context, "seed", 0L);
        String kindRatio = context.config().getOption("kind-ratio", "regular:70,interface:10,enum:10,inner:10");
        if (seed == 0L) {
            seed = SECURE_RANDOM.nextLong();
        }
        Random random = new Random(seed);
        Dictionary dictionary = Dictionary.create(context.config().getDictionary());
        List<String> existingPackages = existingPackages(context);
        Set<String> reserved = new HashSet<>(context.pool().getClassMap().keySet());
        reserved.addAll(context.pool().getLibraryClasses().keySet());

        int generated = 0;
        for (int i = 0; i < count; i++) {
            String packageName = resolvePackage(context, placement, existingPackages, random);
            String simpleName = nextSimpleName(naming, customPattern, dictionary, i, random);
            String name = uniqueName(reserved, packageName, simpleName, random);
            int methodCount = randomRange(random, minMethods, maxMethods);
            int fieldCount = randomRange(random, minFields, maxFields);
            ClassNode classNode = buildClass(name, chooseKind(kindRatio, random), methodCount, fieldCount, random);
            context.pool().addClass(name, classNode);
            context.pool().markDirty(name);
            generated++;
        }

        context.stats().add("fakeClasses", generated);
        log("Generated {} fake classes", generated);
    }

    private ClassNode buildClass(String name, String kind, int methodsPerClass, int fieldsPerClass, Random random) {
        if ("interface".equals(kind)) {
            return buildInterface(name, methodsPerClass, random);
        }

        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = "java/lang/Object";
        node.sourceFile = simpleName(name) + ".java";
        node.signature = "Ljava/lang/Object;";
        node.methods.add(defaultConstructor());
        node.methods.add(staticInitializer(name, random));

        Set<String> usedFields = new HashSet<>();
        for (int i = 0; i < fieldsPerClass; i++) {
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE,
                    uniqueMemberName(usedFields, FIELD_NAMES.get(i % FIELD_NAMES.size()), i / FIELD_NAMES.size()),
                    fieldDesc(i),
                    null,
                    null
            ));
        }
        Set<String> usedMethods = new HashSet<>();
        for (int i = 0; i < methodsPerClass; i++) {
            MethodShape shape = MethodShape.values()[i % MethodShape.values().length];
            String methodName = uniqueMethodName(usedMethods, METHOD_NAMES.get(i % METHOD_NAMES.size()), descFor(shape), i / METHOD_NAMES.size());
            node.methods.add(realisticMethod(methodName, shape, random));
        }
        if ("enum".equals(kind)) {
            addEnumLikeMembers(node, random);
        }
        if ("inner".equals(kind)) {
            node.innerClasses = new ArrayList<>();
            node.innerClasses.add(new InnerClassNode(name, null, node.name.substring(node.name.lastIndexOf('/') + 1), node.access));
        }
        return node;
    }

    private ClassNode buildInterface(String name, int methodsPerClass, Random random) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;
        node.name = name;
        node.superName = "java/lang/Object";
        node.sourceFile = simpleName(name) + ".java";
        Set<String> usedMethods = new HashSet<>();
        for (int i = 0; i < Math.max(1, methodsPerClass / 4); i++) {
            MethodShape shape = MethodShape.values()[i % MethodShape.values().length];
            node.methods.add(new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    uniqueMethodName(usedMethods, METHOD_NAMES.get(i % METHOD_NAMES.size()), descFor(shape), i / METHOD_NAMES.size()),
                    descFor(shape),
                    null,
                    null
            ));
        }
        return node;
    }

    private MethodNode defaultConstructor() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private MethodNode staticInitializer(String owner, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.instructions.add(new LdcInsnNode(random.nextInt()));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 3));
        method.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        return method;
    }

    private void addEnumLikeMembers(ClassNode node, Random random) {
        String name = confuseName(random);
        node.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                name,
                "Ljava/lang/String;",
                null,
                confuseName(random)
        ));
        MethodNode values = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()[Ljava/lang/String;", null, null);
        values.instructions.add(new InsnNode(Opcodes.ICONST_1));
        values.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        values.instructions.add(new InsnNode(Opcodes.DUP));
        values.instructions.add(new InsnNode(Opcodes.ICONST_0));
        values.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, name, "Ljava/lang/String;"));
        values.instructions.add(new InsnNode(Opcodes.AASTORE));
        values.instructions.add(new InsnNode(Opcodes.ARETURN));
        values.maxStack = 4;
        values.maxLocals = 0;
        node.methods.add(values);
    }

    private MethodNode realisticMethod(String name, MethodShape shape, Random random) {
        return switch (shape) {
            case SCORE -> scoreMethod(name, random);
            case TEXT -> textMethod(name);
            case CHECK -> checkMethod(name);
            case SIZE -> sizeMethod(name);
            case LONG -> longMethod(name, random);
            case VOID -> voidMethod(name);
            case ARRAY -> arrayMethod(name);
            case COPY -> copyMethod(name);
        };
    }

    private MethodNode scoreMethod(String name, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(I)I", null, null);
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        int salt = random.nextInt(31) + 3;
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new LdcInsnNode(salt));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.ICONST_3));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new IincInsnNode(3, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(done);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 4;
        return method;
    }

    private MethodNode textMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        LabelNode notNull = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new LdcInsnNode("default"));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(notNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode checkMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/String;)Z", null, null);
        LabelNode notNull = new LabelNode();
        LabelNode falseResult = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new JumpInsnNode(Opcodes.GOTO, falseResult));
        insns.add(notNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isBlank", "()Z", false));
        insns.add(new JumpInsnNode(Opcodes.IFNE, falseResult));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(falseResult);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode sizeMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/util/Collection;)I", null, null);
        LabelNode notNull = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(notNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "size", "()I", true));
        insns.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }

    private MethodNode longMethod(String name, Random random) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(J)J", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        method.instructions.add(new LdcInsnNode(Math.abs(random.nextLong() % 2048L) + 1L));
        method.instructions.add(new InsnNode(Opcodes.LADD));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.maxStack = 4;
        method.maxLocals = 3;
        return method;
    }

    private MethodNode voidMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false));
        method.instructions.add(new InsnNode(Opcodes.POP2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        return method;
    }

    private MethodNode arrayMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "([I)I", null, null);
        LabelNode notNull = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(notNull);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insns.add(new IincInsnNode(3, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(done);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 3;
        method.maxLocals = 4;
        return method;
    }

    private MethodNode copyMethod(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/util/Map;)Ljava/util/Map;", null, null);
        LabelNode notNull = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(notNull);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "(Ljava/util/Map;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 2;
        return method;
    }

    private String fieldDesc(int index) {
        return switch (index % 4) {
            case 0 -> "I";
            case 1 -> "J";
            case 2 -> "Z";
            default -> "Ljava/lang/String;";
        };
    }

    private String descFor(MethodShape shape) {
        return switch (shape) {
            case SCORE -> "(I)I";
            case TEXT -> "(Ljava/lang/String;)Ljava/lang/String;";
            case CHECK -> "(Ljava/lang/String;)Z";
            case SIZE -> "(Ljava/util/Collection;)I";
            case LONG -> "(J)J";
            case VOID -> "()V";
            case ARRAY -> "([I)I";
            case COPY -> "(Ljava/util/Map;)Ljava/util/Map;";
        };
    }

    private String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash == -1 ? internalName : internalName.substring(slash + 1);
    }

    private String uniqueMemberName(Set<String> used, String base, int suffix) {
        String candidate = suffix == 0 ? base : base + suffix;
        while (!used.add(candidate)) {
            candidate = base + (++suffix);
        }
        return candidate;
    }

    private String uniqueMethodName(Set<String> used, String base, String desc, int suffix) {
        String candidate = suffix == 0 ? base : base + suffix;
        while (!used.add(candidate + desc)) {
            candidate = base + (++suffix);
        }
        return candidate;
    }

    private enum MethodShape {
        SCORE,
        TEXT,
        CHECK,
        SIZE,
        LONG,
        VOID,
        ARRAY,
        COPY
    }

    private String uniqueName(Set<String> reserved, String packageName, String simpleName, Random random) {
        String name;
        int suffix = 0;
        do {
            String current = suffix == 0 ? simpleName : simpleName + suffix;
            name = packageName.isBlank() ? current : packageName + "/" + current;
            suffix++;
        } while (!reserved.add(name));
        return name;
    }

    private String nextSimpleName(String naming, String customPattern, Dictionary dictionary, int index, Random random) {
        return switch (naming) {
            case "custom" -> sanitizeSimpleName(customPattern
                    .replace("{index}", String.valueOf(index))
                    .replace("{random}", Integer.toHexString(random.nextInt())));
            case "confusable" -> confuseName(random);
            case "chinese" -> chineseName(random, 2, 5);
            default -> sanitizeSimpleName(dictionary.next());
        };
    }

    private String resolvePackage(Context context, String placement, List<String> existingPackages, Random random) {
        return switch (placement) {
            case "specific" -> normalizePackage(context.config().getOption("package", "frost/junk"));
            case "existing" -> existingPackages.isEmpty() ? "" : existingPackages.get(random.nextInt(existingPackages.size()));
            case "none" -> "";
            default -> switch (context.pool().getPackageMode().toLowerCase()) {
                case "flatten" -> normalizePackage(context.pool().getFlattenPackage());
                case "remove" -> "";
                default -> existingPackages.isEmpty() ? "" : existingPackages.get(random.nextInt(existingPackages.size()));
            };
        };
    }

    private List<String> existingPackages(Context context) {
        Set<String> packages = new HashSet<>();
        for (String name : context.pool().getClassMap().keySet()) {
            if (name.startsWith("__frost/")) {
                continue;
            }
            int lastSlash = name.lastIndexOf('/');
            packages.add(lastSlash == -1 ? "" : name.substring(0, lastSlash));
        }
        return new ArrayList<>(packages);
    }

    private String confuseName(Random random) {
        char[] alphabet = {'I', 'l', '1', 'O', '0'};
        int length = 8 + random.nextInt(8);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return builder.toString();
    }

    private String chineseName(Random random, int min, int max) {
        int length = randomRange(random, min, max);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) (0x4E00 + random.nextInt(0x9FA5 - 0x4E00)));
        }
        return builder.toString();
    }

    private String normalizePackage(String value) {
        String normalized = value == null || value.isBlank() ? "frost/junk" : value.replace('.', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "frost/junk" : normalized;
    }

    private String sanitizeSimpleName(String value) {
        String sanitized = value == null || value.isBlank()
                ? "Fake"
                : value.replaceAll("[^\\p{L}\\p{N}_$]", "");
        if (sanitized.isBlank() || Character.isDigit(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return sanitized;
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int randomRange(Random random, int min, int max) {
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private String chooseKind(String ratio, Random random) {
        int regular = 70;
        int iface = 10;
        int enumLike = 10;
        int inner = 10;
        for (String part : ratio.split(",")) {
            String[] pieces = part.trim().split(":");
            if (pieces.length != 2) continue;
            try {
                int value = Math.max(0, Integer.parseInt(pieces[1].trim()));
                switch (pieces[0].trim().toLowerCase()) {
                    case "regular" -> regular = value;
                    case "interface" -> iface = value;
                    case "enum" -> enumLike = value;
                    case "inner" -> inner = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int total = Math.max(1, regular + iface + enumLike + inner);
        int roll = random.nextInt(total);
        if (roll < regular) return "regular";
        roll -= regular;
        if (roll < iface) return "interface";
        roll -= iface;
        if (roll < enumLike) return "enum";
        return "inner";
    }
}
