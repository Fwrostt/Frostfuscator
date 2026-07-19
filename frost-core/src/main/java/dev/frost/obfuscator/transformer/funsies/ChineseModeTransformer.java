package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.*;

public class ChineseModeTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> BANNERS = List.of(
            "\u51b0\u971c\u6df7\u6dc6\u5668\u4fdd\u62a4\u6b64\u7c7b",
            "\u9006\u5411\u5de5\u7a0b\u5e08\u8ff7\u8def\u4e2d",
            "\u96ea\u843d\u65e0\u58f0 \u5b57\u8282\u6709\u6bd2",
            "\u8fd9\u91cc\u6ca1\u6709\u4f60\u60f3\u8981\u7684\u7b54\u6848"
    );
    private static final List<String> LARGE_BANNERS = List.of(
            "\u51b0\u971c\n\u6df7\u6dc6\u5668\n\u4e0d\u8981\u76f4\u89c6\u5b57\u8282",
            "\u2605 \u51b0\u5c01\u4ee3\u7801 \u2605\n\u2605 \u9006\u5411\u8005\u6b62\u6b65 \u2605"
    );
    private static final List<String> QUOTES = List.of(
            "\u98ce\u96ea\u591c\u5f52\u4eba",
            "\u6b64\u95e8\u4e0d\u901a",
            "\u6708\u843d\u4e4c\u557c\u971c\u6ee1\u5929",
            "\u4e00\u5207\u6709\u4e3a\u6cd5\u5982\u68a6\u5e7b\u6ce1\u5f71"
    );

    @Override
    public String getName() {
        return "chinese-mode";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public Priority priority() {
        return Priority.FINAL;
    }

    @Override
    public void transform(Context context) {
        ClassPool pool = context.pool();
        TransformerConfig config = context.config();
        boolean renameMembers = getBooleanOption(config, "rename-members", true);
        boolean injectFun = getBooleanOption(config, "inject-fun", true);
        boolean largeBanners = getBooleanOption(config, "large-banners", true);
        boolean quotes = getBooleanOption(config, "quotes", true);
        boolean injectMetadata = getBooleanOption(config, "inject-metadata", true);
        boolean injectStrings = getBooleanOption(config, "inject-strings", true);
        int minFun = Math.max(0, getIntOption(config, "min-fun-members", 1));
        int maxFun = Math.max(minFun, getIntOption(config, "max-fun-members", 3));
        String packageMode = config.getOption("package-mode", "global").toLowerCase(Locale.ROOT);
        String prefix = normalizePackage(config.getOption("package-prefix", "\u51b0\u971c/\u6df7\u6dc6\u5668"));

        Map<String, String> classMap = buildClassMap(pool, packageMode, prefix);
        Map<String, String> fieldMap = new HashMap<>();
        Map<String, String> methodMap = new HashMap<>();
        if (renameMembers) {
            buildMemberMaps(pool, fieldMap, methodMap);
        }

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return fieldMap.getOrDefault(owner + "." + name + ":" + descriptor, name);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return methodMap.getOrDefault(owner + "." + name + descriptor, name);
            }
        };

        Map<String, ClassNode> remapped = new LinkedHashMap<>();
        for (Map.Entry<String, ClassNode> entry : pool.getClassMap().entrySet()) {
            ClassNode output = new ClassNode();
            entry.getValue().accept(new ClassRemapper(output, remapper));
            if (injectMetadata) {
                output.sourceFile = chineseName(3, 6) + ".java";
                output.sourceDebug = "\u6ce8\u91ca: " + QUOTES.get(RANDOM.nextInt(QUOTES.size()));
            }
            if (injectFun && !AccessHelper.isInterface(output.access) && !AccessHelper.isAnnotation(output.access)) {
                injectFun(output, randomRange(minFun, maxFun), largeBanners, quotes, injectStrings);
            }
            remapped.put(output.name, output);
            pool.setOriginalName(output.name, pool.getOriginalName(entry.getKey()));
            pool.markDirty(output.name);
        }

        pool.getClassMap().clear();
        pool.getClassMap().putAll(remapped);
        updateEntrypoints(context, classMap);
        log("Chinese Mode remapped {} classes and injected Chinese metadata", classMap.size());
    }

    private Map<String, String> buildClassMap(ClassPool pool, String packageMode, String prefix) {
        Map<String, String> map = new LinkedHashMap<>();
        Set<String> used = new HashSet<>(pool.getLibraryClasses().keySet());
        used.addAll(pool.getClassMap().keySet());
        List<String> existingPackages = existingPackages(pool);
        String globalPackage = resolveGlobalPackage(prefix);
        for (String name : pool.getClassMap().keySet()) {
            if (name.equals("module-info") || name.endsWith("/package-info")) {
                continue;
            }
            String candidate;
            do {
                String simple = chineseName(2, 5);
                String packageName = switch (packageMode) {
                    case "random" -> randomChinesePackage();
                    case "existing" -> existingPackages.isEmpty() ? "" : existingPackages.get(RANDOM.nextInt(existingPackages.size()));
                    case "none" -> "";
                    default -> globalPackage;
                };
                candidate = packageName.isBlank() ? simple : packageName + "/" + simple;
            } while (!used.add(candidate));
            map.put(name, candidate);
        }
        return map;
    }

    private String resolveGlobalPackage(String prefix) {
        return prefix.isBlank() ? randomChinesePackage() : prefix;
    }

    private String randomChinesePackage() {
        int depth = randomRange(1, 3);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            parts.add(chineseName(2, 5));
        }
        return String.join("/", parts);
    }

    private List<String> existingPackages(ClassPool pool) {
        Set<String> packages = new HashSet<>();
        for (String name : pool.getClassMap().keySet()) {
            int slash = name.lastIndexOf('/');
            packages.add(slash == -1 ? "" : name.substring(0, slash));
        }
        return new ArrayList<>(packages);
    }

    private void buildMemberMaps(ClassPool pool, Map<String, String> fieldMap, Map<String, String> methodMap) {
        for (ClassNode classNode : pool.getClasses()) {
            Set<String> usedFields = new HashSet<>();
            Map<String, Set<String>> usedMethods = new HashMap<>();
            for (FieldNode field : classNode.fields) usedFields.add(field.name);
            for (MethodNode method : classNode.methods) {
                usedMethods.computeIfAbsent(method.desc, key -> new HashSet<>()).add(method.name);
            }
            for (FieldNode field : classNode.fields) {
                if ((field.access & Opcodes.ACC_ENUM) != 0) continue;
                String next;
                do {
                    next = chineseName(2, 4);
                } while (!usedFields.add(next));
                fieldMap.put(classNode.name + "." + field.name + ":" + field.desc, next);
            }
            for (MethodNode method : classNode.methods) {
                if (AccessHelper.isInitializer(method)) continue;
                if (AccessHelper.isMainMethod(method)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                Set<String> used = usedMethods.computeIfAbsent(method.desc, key -> new HashSet<>());
                String next;
                do {
                    next = chineseName(2, 4);
                } while (!used.add(next));
                methodMap.put(classNode.name + "." + method.name + method.desc, next);
            }
        }
    }

    private void injectFun(ClassNode classNode, int count, boolean largeBanners, boolean quotes, boolean injectStrings) {
        Set<String> used = new HashSet<>();
        for (FieldNode field : classNode.fields) used.add(field.name);
        for (MethodNode method : classNode.methods) used.add(method.name);

        for (int i = 0; i < count; i++) {
            String fieldName = uniqueChinese(used);
            String methodName = uniqueChinese(used);
            classNode.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    fieldName,
                    "Ljava/lang/String;",
                    null,
                    randomChinesePayload(largeBanners, quotes, injectStrings)
            ));
            MethodNode method = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    methodName,
                    "()Ljava/lang/String;",
                    null,
                    null
            );
            method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, fieldName, "Ljava/lang/String;"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
            method.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
            method.instructions.add(new InsnNode(Opcodes.POP));
            method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, fieldName, "Ljava/lang/String;"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            method.maxStack = 1;
            method.maxLocals = 0;
            classNode.methods.add(method);
        }
    }

    private String randomChinesePayload(boolean largeBanners, boolean quotes, boolean injectStrings) {
        List<String> parts = new ArrayList<>();
        parts.add(BANNERS.get(RANDOM.nextInt(BANNERS.size())));
        if (largeBanners) parts.add(LARGE_BANNERS.get(RANDOM.nextInt(LARGE_BANNERS.size())));
        if (quotes) parts.add(QUOTES.get(RANDOM.nextInt(QUOTES.size())));
        if (injectStrings) parts.add(chineseName(8, 18));
        return String.join(" :: ", parts);
    }

    private void updateEntrypoints(Context context, Map<String, String> classMap) {
        String manifestMain = context.jar().getManifestMainClass();
        if (manifestMain != null) {
            String mapped = classMap.get(manifestMain.replace('.', '/'));
            if (mapped != null) {
                context.jar().updateManifestMainClass(manifestMain, mapped.replace('/', '.'));
            }
        }

        String pluginMain = context.jar().getCurrentPluginMainClass();
        if (pluginMain != null) {
            String mapped = classMap.get(pluginMain.replace('.', '/'));
            if (mapped != null) {
                context.jar().updatePluginMainClass(pluginMain, mapped.replace('/', '.'));
            }
        }
    }

    private String uniqueChinese(Set<String> used) {
        String name;
        do {
            name = chineseName(2, 4);
        } while (!used.add(name));
        return name;
    }

    private String chineseName(int min, int max) {
        int length = randomRange(min, max);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) (0x4E00 + RANDOM.nextInt(0x9FA5 - 0x4E00)));
        }
        return builder.toString();
    }

    private int randomRange(int min, int max) {
        return min == max ? min : min + RANDOM.nextInt(max - min + 1);
    }

    private String normalizePackage(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder builder = new StringBuilder();
        for (String part : value.replace('.', '/').split("/+")) {
            if (part.isBlank()) continue;
            if (!builder.isEmpty()) builder.append('/');
            builder.append(part.replaceAll("[^\\p{IsHan}\\p{L}\\p{N}_$]", ""));
        }
        return builder.toString();
    }

    private boolean getBooleanOption(TransformerConfig config, String key, boolean fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private int getIntOption(TransformerConfig config, String key, int fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
