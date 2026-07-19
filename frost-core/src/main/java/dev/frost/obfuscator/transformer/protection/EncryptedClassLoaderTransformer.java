package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptedClassLoaderTransformer extends Transformer {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int DATABASE_MAGIC = 0x4652434c;
    private static final int DATABASE_VERSION = 2;
    private static final int FLAG_COMPRESSED = 1;

    @Override
    public String getName() {
        return "classloader-encryption";
    }

    @Override
    public String getCategory() {
        return "Protection";
    }

    @Override
    public Priority priority() {
        return Priority.CLASSLOADER_ENCRYPTION;
    }

    @Override
    public void transform(Context context) {
        TransformerConfig config = context.config();
        boolean encryptMainClass = getBooleanOption(config, true, "encrypt-main-class", "encryptMainClass");
        String algorithm = getStringOption(config, "AES/GCM/NoPadding", "algorithm");
        String resourcePath = getStringOption(config, "classes.db", "resource-path", "resourcePath");
        boolean failOnError = getBooleanOption(config, true, "fail-on-error", "failOnError");
        boolean compressClasses = getBooleanOption(config, true, "compress-classes", "compressClasses");

        // Check if Bukkit/Spigot plugin descriptor is present
        String pluginMain = context.jar().getCurrentPluginMainClass();
        String pluginMainInternal = pluginMain != null ? pluginMain.replace('.', '/') : null;
        String pluginMainPackage = pluginMainInternal != null ? packageName(pluginMainInternal) : null;

        // Collect class dependencies of the plugin main class to avoid NoClassDefFoundErrors on Spigot startup.
        //
        // Two-tier strategy:
        //   Depth 0 (main class): full bytecode scan — the JVM verifies method bodies and resolves
        //     StackMapTable frame types during Class.forName(main, true, loader), so we must exclude
        //     every class referenced anywhere in the main class bytecode.
        //   Depth 1+ (transitive deps): structural scan only — superclass, interfaces, field types,
        //     inner classes, annotations. The JVM loads these classes but does NOT eagerly verify
        //     their method bodies until they are actually instantiated/called. This prevents the
        //     exclusion set from snowballing into the entire plugin.
        Set<String> pluginMainExclusions = new HashSet<>();
        if (pluginMainInternal != null) {
            Queue<String> queue = new LinkedList<>();
            queue.add(pluginMainInternal);
            pluginMainExclusions.add(pluginMainInternal);
            boolean isMainClass = true;

            while (!queue.isEmpty()) {
                int levelSize = queue.size();
                for (int q = 0; q < levelSize; q++) {
                    String current = queue.poll();
                    ClassNode node = context.pool().getClassMap().get(current);
                    if (node != null) {
                        // Full bytecode scan for main class; structural-only for transitive deps
                        Set<String> deps = isMainClass
                                ? getAllClassReferences(node)
                                : getStructuralDependencies(node);
                        for (String dep : deps) {
                            if (context.pool().getClassMap().containsKey(dep)) {
                                if (pluginMainExclusions.add(dep)) {
                                    queue.add(dep);
                                }
                            }
                        }
                    }
                }
                isMainClass = false;
            }
            Logger.info("Excluded plugin main class and {} transitive dependencies from encryption: {}",
                    pluginMainExclusions.size() - 1, pluginMainExclusions);
        }

        String manifestMainClass = context.jar().getManifestMainClass();
        String manifestMainInternal = manifestMainClass != null ? manifestMainClass.replace('.', '/') : null;

        // Generate root key. Each class gets a derived key based on its binary name.
        byte[] aesKey = new byte[16];
        SECURE_RANDOM.nextBytes(aesKey);
        String aesKeyBase64 = Base64.getEncoder().encodeToString(aesKey);

        Map<String, EncryptedClass> encryptedClasses = new LinkedHashMap<>();
        List<String> classesToRemove = new ArrayList<>();
        int skippedCount = 0;

        for (ClassNode classNode : context.pool().getClasses()) {
            // Loader classes must be excluded
            if (classNode.name.startsWith("dev/frost/loader/")) {
                skippedCount++;
                continue;
            }

            // Exclude plugin main class and its signature dependencies
            if (pluginMainExclusions.contains(classNode.name)) {
                skippedCount++;
                continue;
            }
            if (pluginMainPackage != null && !pluginMainPackage.equals(packageName(classNode.name))) {
                skippedCount++;
                continue;
            }

            // Exclude main class if requested
            if (classNode.name.equals(manifestMainInternal) && !encryptMainClass) {
                skippedCount++;
                continue;
            }

            // Check exclusions and inclusions
            List<String> combinedInclusions = new ArrayList<>();
            if (context.pool().getGlobalInclusions() != null) combinedInclusions.addAll(context.pool().getGlobalInclusions());
            combinedInclusions.addAll(getPatterns(config, "includePatterns", "include-patterns"));
            if (config.getInclusions() != null) combinedInclusions.addAll(config.getInclusions());

            List<String> combinedExclusions = new ArrayList<>();
            if (context.pool().getGlobalExclusions() != null) combinedExclusions.addAll(context.pool().getGlobalExclusions());
            combinedExclusions.addAll(getPatterns(config, "excludePatterns", "exclude-patterns"));
            if (config.getExclusions() != null) combinedExclusions.addAll(config.getExclusions());

            if (!shouldProcess(classNode.name, config, combinedExclusions, combinedInclusions)) {
                skippedCount++;
                continue;
            }

            try {
                // Compile class node to bytes
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        return "java/lang/Object"; // Safe fallback
                    }
                };
                classNode.accept(writer);
                byte[] classBytes = writer.toByteArray();

                String classNameDot = classNode.name.replace('/', '.');
                byte[] payload = compressClasses ? compress(classBytes) : classBytes;

                // Generate random IV
                byte[] iv = new byte[algorithm.contains("GCM") ? 12 : 16];
                SECURE_RANDOM.nextBytes(iv);

                // Encrypt bytes
                Cipher cipher = Cipher.getInstance(algorithm);
                SecretKeySpec keySpec = new SecretKeySpec(deriveClassKey(aesKey, classNameDot), "AES");
                AlgorithmParameterSpec paramSpec;
                if (algorithm.contains("GCM")) {
                    paramSpec = new GCMParameterSpec(128, iv);
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);
                    cipher.updateAAD(classNameDot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    paramSpec = new IvParameterSpec(iv);
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);
                }
                byte[] encryptedBytes = cipher.doFinal(payload);

                encryptedClasses.put(classNameDot, new EncryptedClass(
                        compressClasses ? FLAG_COMPRESSED : 0,
                        classBytes.length,
                        iv,
                        encryptedBytes
                ));
                classesToRemove.add(classNode.name);
            } catch (Exception e) {
                String errMsg = "Failed to encrypt class: " + classNode.name;
                if (failOnError) {
                    throw new RuntimeException(errMsg, e);
                } else {
                    Logger.warn(errMsg + " (" + e.getMessage() + ")");
                    skippedCount++;
                }
            }
        }

        // Remove the encrypted classes from the pool
        for (String className : classesToRemove) {
            context.pool().getClassMap().remove(className);
        }

        // Build database resource
        byte[] databaseBytes;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(DATABASE_MAGIC);
            dos.writeByte(DATABASE_VERSION);
            dos.writeInt(encryptedClasses.size());
            for (Map.Entry<String, EncryptedClass> entry : encryptedClasses.entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeByte(entry.getValue().flags);
                dos.writeInt(entry.getValue().originalLength);
                dos.writeByte(entry.getValue().iv.length);
                dos.write(entry.getValue().iv);
                dos.writeInt(entry.getValue().encryptedBytes.length);
                dos.write(entry.getValue().encryptedBytes);
            }
            dos.close();
            databaseBytes = baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct encrypted class database", e);
        }

        // Store database as resource
        context.jar().putResource(resourcePath, databaseBytes);

        // Patch and inject runtime loader classes
        Map<String, String> loaderReplacements = new HashMap<>();
        loaderReplacements.put("ALGORITHM_PLACEHOLDER", algorithm);
        loaderReplacements.put("RESOURCE_PATH_PLACEHOLDER", resourcePath);
        loaderReplacements.put("/RESOURCE_PATH_PLACEHOLDER", "/" + resourcePath);
        loaderReplacements.put("AES_KEY_PLACEHOLDER", aesKeyBase64);
        loaderReplacements.put("FAIL_ON_ERROR_PLACEHOLDER", String.valueOf(failOnError));

        Map<String, String> bootstrapReplacements = new HashMap<>();
        bootstrapReplacements.put("REAL_MAIN_PLACEHOLDER", manifestMainClass != null ? manifestMainClass : "");

        try {
            ClassNode loaderNode = patchClass("/dev/frost/loader/DecryptedClassLoader.class", loaderReplacements);
            ClassNode nestedNode = loadClassNode("/dev/frost/loader/DecryptedClassLoader$EncryptedClass.class");

            context.pool().addClass(loaderNode.name, loaderNode);
            context.pool().markDirty(loaderNode.name);
            context.pool().addClass(nestedNode.name, nestedNode);
            context.pool().markDirty(nestedNode.name);

            if (manifestMainClass != null && pluginMainInternal == null) {
                ClassNode bootstrapNode = patchClass("/dev/frost/loader/Bootstrap.class", bootstrapReplacements);
                context.pool().addClass(bootstrapNode.name, bootstrapNode);
                context.pool().markDirty(bootstrapNode.name);
            }

            context.stats().set("loaderInjectionStatus", 1L);
        } catch (Exception e) {
            String errMsg = "Failed to inject runtime class loader: " + e.getMessage();
            if (failOnError) {
                throw new RuntimeException(errMsg, e);
            } else {
                Logger.error(errMsg);
                context.stats().set("loaderInjectionStatus", 3L);
            }
        }

        // Inject bootstrap call in plugin main class or redirect Manifest Main-Class
        if (pluginMainInternal != null) {
            ClassNode pluginMainNode = context.pool().getClassMap().get(pluginMainInternal);
            if (pluginMainNode != null) {
                // Find or create static initializer <clinit>
                MethodNode clinit = null;
                for (MethodNode mn : pluginMainNode.methods) {
                    if (mn.name.equals("<clinit>")) {
                        clinit = mn;
                        break;
                    }
                }
                if (clinit == null) {
                    clinit = new MethodNode(
                        Opcodes.ACC_STATIC,
                        "<clinit>",
                        "()V",
                        null,
                        null
                    );
                    clinit.instructions.add(new InsnNode(Opcodes.RETURN));
                    pluginMainNode.methods.add(clinit);
                }

                // Inject bootstrap call inside a try-catch block so plugin startup fails only when configured.
                LabelNode start = new LabelNode();
                LabelNode end = new LabelNode();
                LabelNode handler = new LabelNode();
                LabelNode post = new LabelNode();

                InsnList list = new InsnList();
                list.add(start);
                list.add(new LdcInsnNode(Type.getType("L" + pluginMainNode.name + ";")));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/frost/loader/DecryptedClassLoader",
                        "bootstrap",
                        "(Ljava/lang/Class;)V",
                        false
                ));
                list.add(end);
                list.add(new JumpInsnNode(Opcodes.GOTO, post));
                
                list.add(handler);
                list.add(new InsnNode(Opcodes.POP)); // Pop exception object
                list.add(post);

                clinit.instructions.insert(list);
                clinit.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));
                context.pool().markDirty(pluginMainInternal);
                Logger.info("Injected classloader bootstrap in plugin main class static initializer: {}", pluginMain);
            } else {
                Logger.error("Plugin main class {} not found in ClassPool!", pluginMainInternal);
            }
        } else if (manifestMainClass != null) {
            context.jar().updateManifestMainClass(manifestMainClass, "dev.frost.loader.Bootstrap");
            Logger.info("Redirected Main-Class in manifest: {} -> dev.frost.loader.Bootstrap", manifestMainClass);
        } else {
            Logger.warn("No Manifest Main-Class or Plugin Main-Class detected; standalone execution bootstrapping skipped.");
        }

        context.stats().set("encryptedClassCount", encryptedClasses.size());
        context.stats().set("skippedClassCount", skippedCount);
        context.stats().set("encryptedResourceSize", databaseBytes.length);

        log("Encrypted {} classes ({} skipped). Resource size: {} bytes. Compression: {}.",
                encryptedClasses.size(), skippedCount, databaseBytes.length, compressClasses);
    }

    private ClassNode patchClass(String resourcePath, Map<String, String> replacements) throws IOException {
        try (InputStream is = EncryptedClassLoaderTransformer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Failed to load pre-compiled runtime class: " + resourcePath);
            }
            ClassReader reader = new ClassReader(is);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            for (MethodNode mn : node.methods) {
                if (mn.instructions != null) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof LdcInsnNode ldc) {
                            if (ldc.cst instanceof String s) {
                                if (replacements.containsKey(s)) {
                                    ldc.cst = replacements.get(s);
                                }
                            }
                        }
                    }
                }
            }
            return node;
        }
    }

    private ClassNode loadClassNode(String resourcePath) throws IOException {
        try (InputStream is = EncryptedClassLoaderTransformer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Failed to load pre-compiled runtime class: " + resourcePath);
            }
            ClassReader reader = new ClassReader(is);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            return node;
        }
    }

    private String getStringOption(TransformerConfig config, String defaultValue, String... keys) {
        for (String key : keys) {
            Object value = config.getOptions().get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return defaultValue;
    }

    private boolean getBooleanOption(TransformerConfig config, boolean defaultValue, String... keys) {
        for (String key : keys) {
            Object value = config.getOptions().get(key);
            if (value instanceof Boolean b) {
                return b;
            }
            if (value != null) {
                return Boolean.parseBoolean(value.toString());
            }
        }
        return defaultValue;
    }

    private List<String> getPatterns(TransformerConfig config, String... keys) {
        String value = getStringOption(config, "", keys);
        if (value == null || value.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private byte[] deriveClassKey(byte[] rootKey, String className) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(rootKey);
        digest.update((byte) 0);
        digest.update(className.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] full = digest.digest();
        return Arrays.copyOf(full, 16);
    }

    private byte[] compress(byte[] classBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(classBytes.length);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(classBytes);
        }
        return output.toByteArray();
    }

    private String packageName(String internalName) {
        int index = internalName.lastIndexOf('/');
        return index < 0 ? "" : internalName.substring(0, index);
    }

    /**
     * Collects ALL class references from a ClassNode, not just signature-level dependencies.
     * The JVM verifier resolves classes from StackMapTable frames during class linking
     * (before static initializers run), so we must exclude every class that could be
     * transitively referenced from the plugin main class's entire bytecode.
     *
     * Scans: superclass, interfaces, field descriptors, method descriptors, exceptions,
     * annotations, inner class entries, try-catch handler types, and all bytecode
     * instructions (NEW, CHECKCAST, INSTANCEOF, field/method owners, LDC class constants,
     * MULTIANEWARRAY element types).
     */
    private Set<String> getAllClassReferences(ClassNode node) {
        Set<String> deps = new HashSet<>();

        // Superclass and interfaces
        if (node.superName != null) {
            deps.add(node.superName);
        }
        if (node.interfaces != null) {
            deps.addAll(node.interfaces);
        }

        // Inner classes attribute — the JVM may resolve these during linking
        if (node.innerClasses != null) {
            for (InnerClassNode ic : node.innerClasses) {
                if (ic.name != null) {
                    deps.add(ic.name);
                }
            }
        }

        // Fields
        if (node.fields != null) {
            for (org.objectweb.asm.tree.FieldNode fn : node.fields) {
                if (fn.desc != null) {
                    addTypeDependencies(fn.desc, deps);
                }
                if (fn.signature != null) {
                    extractClassesFromSignature(fn.signature, deps);
                }
                addAnnotationDependencies(fn.visibleAnnotations, deps);
                addAnnotationDependencies(fn.invisibleAnnotations, deps);
            }
        }

        // Methods — signatures, exceptions, annotations, AND bytecode instructions
        if (node.methods != null) {
            for (MethodNode mn : node.methods) {
                if (mn.desc != null) {
                    addTypeDependencies(mn.desc, deps);
                }
                if (mn.signature != null) {
                    extractClassesFromSignature(mn.signature, deps);
                }
                if (mn.exceptions != null) {
                    deps.addAll(mn.exceptions);
                }
                addAnnotationDependencies(mn.visibleAnnotations, deps);
                addAnnotationDependencies(mn.invisibleAnnotations, deps);
                if (mn.visibleParameterAnnotations != null) {
                    for (List<org.objectweb.asm.tree.AnnotationNode> list : mn.visibleParameterAnnotations) {
                        addAnnotationDependencies(list, deps);
                    }
                }
                if (mn.invisibleParameterAnnotations != null) {
                    for (List<org.objectweb.asm.tree.AnnotationNode> list : mn.invisibleParameterAnnotations) {
                        addAnnotationDependencies(list, deps);
                    }
                }

                // Try-catch handler types (verifier resolves these)
                if (mn.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                        if (tcb.type != null) {
                            deps.add(tcb.type);
                        }
                    }
                }

                // Bytecode instructions — the verifier processes StackMapTable frames
                // which reference the same types as these instructions
                if (mn.instructions != null) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        switch (insn) {
                            case TypeInsnNode tin -> {
                                // NEW, ANEWARRAY, CHECKCAST, INSTANCEOF
                                String desc = tin.desc;
                                if (desc.startsWith("[")) {
                                    addTypeDependencies(desc, deps);
                                } else {
                                    deps.add(desc);
                                }
                            }
                            case FieldInsnNode fin -> {
                                deps.add(fin.owner);
                                addTypeDependencies(fin.desc, deps);
                            }
                            case MethodInsnNode min -> {
                                deps.add(min.owner);
                                addTypeDependencies(min.desc, deps);
                            }
                            case LdcInsnNode ldc -> {
                                if (ldc.cst instanceof Type t) {
                                    addTypeDependency(t, deps);
                                }
                            }
                            case MultiANewArrayInsnNode mana -> {
                                addTypeDependencies(mana.desc, deps);
                            }
                            default -> { /* other instructions don't reference types */ }
                        }
                    }
                }
            }
        }

        // Class-level annotations
        addAnnotationDependencies(node.visibleAnnotations, deps);
        addAnnotationDependencies(node.invisibleAnnotations, deps);

        // Class-level generic signature
        if (node.signature != null) {
            extractClassesFromSignature(node.signature, deps);
        }

        return deps;
    }

    /**
     * Collects only structural/signature-level class references from a ClassNode.
     * Used for transitive dependencies (depth 1+) where the JVM only needs to
     * load the class (resolve superclass/interfaces/field types) but does NOT
     * eagerly verify method body bytecode until the class is actually used.
     *
     * Scans: superclass, interfaces, inner classes, field descriptors,
     * method descriptors, exceptions, annotations, and generic signatures.
     * Does NOT scan method body instructions.
     */
    private Set<String> getStructuralDependencies(ClassNode node) {
        Set<String> deps = new HashSet<>();

        // Superclass and interfaces
        if (node.superName != null) {
            deps.add(node.superName);
        }
        if (node.interfaces != null) {
            deps.addAll(node.interfaces);
        }

        // Inner classes attribute
        if (node.innerClasses != null) {
            for (InnerClassNode ic : node.innerClasses) {
                if (ic.name != null) {
                    deps.add(ic.name);
                }
            }
        }

        // Fields — descriptors, generic signatures, annotations
        if (node.fields != null) {
            for (org.objectweb.asm.tree.FieldNode fn : node.fields) {
                if (fn.desc != null) {
                    addTypeDependencies(fn.desc, deps);
                }
                if (fn.signature != null) {
                    extractClassesFromSignature(fn.signature, deps);
                }
                addAnnotationDependencies(fn.visibleAnnotations, deps);
                addAnnotationDependencies(fn.invisibleAnnotations, deps);
            }
        }

        // Methods — only descriptors, exceptions, annotations (no bytecode)
        if (node.methods != null) {
            for (MethodNode mn : node.methods) {
                if (mn.desc != null) {
                    addTypeDependencies(mn.desc, deps);
                }
                if (mn.signature != null) {
                    extractClassesFromSignature(mn.signature, deps);
                }
                if (mn.exceptions != null) {
                    deps.addAll(mn.exceptions);
                }
                addAnnotationDependencies(mn.visibleAnnotations, deps);
                addAnnotationDependencies(mn.invisibleAnnotations, deps);
                if (mn.visibleParameterAnnotations != null) {
                    for (List<org.objectweb.asm.tree.AnnotationNode> list : mn.visibleParameterAnnotations) {
                        addAnnotationDependencies(list, deps);
                    }
                }
                if (mn.invisibleParameterAnnotations != null) {
                    for (List<org.objectweb.asm.tree.AnnotationNode> list : mn.invisibleParameterAnnotations) {
                        addAnnotationDependencies(list, deps);
                    }
                }
            }
        }

        // Class-level annotations and generic signature
        addAnnotationDependencies(node.visibleAnnotations, deps);
        addAnnotationDependencies(node.invisibleAnnotations, deps);
        if (node.signature != null) {
            extractClassesFromSignature(node.signature, deps);
        }

        return deps;
    }

    /**
     * Extracts internal class names from a generic signature string.
     * Generic signatures use 'L' prefix and ';' suffix for class references,
     * e.g. "Ljava/util/List<Lcom/example/Foo;>;" contains java/util/List and com/example/Foo.
     */
    private void extractClassesFromSignature(String signature, Set<String> deps) {
        if (signature == null) return;
        int i = 0;
        while (i < signature.length()) {
            if (signature.charAt(i) == 'L') {
                int end = i + 1;
                // Find the end: ';' or '<' (for parameterized types)
                while (end < signature.length() && signature.charAt(end) != ';' && signature.charAt(end) != '<') {
                    end++;
                }
                if (end > i + 1) {
                    deps.add(signature.substring(i + 1, end));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    private void addTypeDependencies(String desc, Set<String> deps) {
        if (desc == null) return;
        try {
            if (desc.startsWith("(")) {
                Type methodType = Type.getMethodType(desc);
                for (Type argType : methodType.getArgumentTypes()) {
                    addTypeDependency(argType, deps);
                }
                addTypeDependency(methodType.getReturnType(), deps);
            } else {
                Type fieldType = Type.getType(desc);
                addTypeDependency(fieldType, deps);
            }
        } catch (Exception e) {
            // Ignore malformed descriptors
        }
    }

    private void addTypeDependency(Type type, Set<String> deps) {
        if (type == null) return;
        if (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            deps.add(type.getInternalName());
        }
    }

    private void addAnnotationDependencies(List<org.objectweb.asm.tree.AnnotationNode> annotations, Set<String> deps) {
        if (annotations == null) return;
        for (org.objectweb.asm.tree.AnnotationNode an : annotations) {
            if (an.desc != null) {
                addTypeDependencies(an.desc, deps);
            }
            if (an.values != null) {
                for (int i = 1; i < an.values.size(); i += 2) {
                    addAnnotationValueDependencies(an.values.get(i), deps);
                }
            }
        }
    }

    private void addAnnotationValueDependencies(Object val, Set<String> deps) {
        if (val instanceof Type t) {
            addTypeDependency(t, deps);
        } else if (val instanceof org.objectweb.asm.tree.AnnotationNode an) {
            addAnnotationDependencies(List.of(an), deps);
        } else if (val instanceof List<?> list) {
            for (Object item : list) {
                addAnnotationValueDependencies(item, deps);
            }
        } else if (val instanceof String[] enumValue && enumValue.length == 2) {
            addTypeDependencies(enumValue[0], deps);
        }
    }

    private static class EncryptedClass {
        final int flags;
        final int originalLength;
        final byte[] iv;
        final byte[] encryptedBytes;

        EncryptedClass(int flags, int originalLength, byte[] iv, byte[] encryptedBytes) {
            this.flags = flags;
            this.originalLength = originalLength;
            this.iv = iv;
            this.encryptedBytes = encryptedBytes;
        }
    }
}
