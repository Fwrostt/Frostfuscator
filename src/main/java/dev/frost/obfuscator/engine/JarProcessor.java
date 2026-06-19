package dev.frost.obfuscator.engine;

import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JarProcessor {

    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final Map<String, byte[]> originalClassBytes = new LinkedHashMap<>();
    private final Map<String, byte[]> preFlowClassBytes = new LinkedHashMap<>();
    private Manifest manifest;
    private String detectedMainClass;

    public ClassPool loadJar(Path inputPath) throws IOException {
        ClassPool pool = new ClassPool();

        try (JarFile jarFile = new JarFile(inputPath.toFile())) {
            manifest = jarFile.getManifest();

            jarFile.stream().forEach(entry -> {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    byte[] data = is.readAllBytes();

                    if (entry.getName().endsWith(".class")) {
                        ClassReader reader = new ClassReader(data);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
                        originalClassBytes.put(classNode.name, data);
                        pool.addClass(classNode.name, classNode);
                    } else if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
                        resources.put(entry.getName(), data);
                    }
                } catch (IOException e) {
                    Logger.error("Failed to read entry: {}", entry.getName());
                }
            });
        }

        detectMainClass();

        Logger.info("Loaded {} classes and {} resources from {}",
                pool.size(), resources.size(), inputPath.getFileName());

        if (detectedMainClass != null) {
            Logger.info("Detected plugin main class: {}", detectedMainClass);
        }

        return pool;
    }

    public void loadLibraries(ClassPool pool, Path libsPath) throws IOException {
        if (!Files.exists(libsPath)) {
            Logger.warn("Libraries path does not exist: {}", libsPath);
            return;
        }

        int libCount = 0;
        int classCount = 0;

        if (Files.isDirectory(libsPath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libsPath, "*.jar")) {
                for (Path jarPath : stream) {
                    classCount += loadLibraryJar(pool, jarPath);
                    libCount++;
                }
            }
        } else if (libsPath.toString().endsWith(".jar")) {
            classCount += loadLibraryJar(pool, libsPath);
            libCount++;
        }

        Logger.info("Loaded {} library classes from {} library JARs", classCount, libCount);
    }

    private int loadLibraryJar(ClassPool pool, Path jarPath) {
        int count = 0;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] data = is.readAllBytes();
                        ClassReader reader = new ClassReader(data);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (!pool.contains(classNode.name)) {
                            pool.addLibraryClass(classNode.name, classNode);
                            count++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Logger.warn("Failed to load library JAR: {}", jarPath.getFileName());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void detectMainClass() {
        byte[] pluginYml = resources.get("plugin.yml");
        if (pluginYml == null) {
            pluginYml = resources.get("paper-plugin.yml");
        }

        if (pluginYml != null) {
            try {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(new String(pluginYml, StandardCharsets.UTF_8));
                if (data != null && data.containsKey("main")) {
                    detectedMainClass = data.get("main").toString();
                }
            } catch (Exception e) {
                Logger.warn("Failed to parse plugin.yml for main class detection");
            }
        }
    }

    public void updatePluginMainClass(String oldMainClass, String newMainClass) {
        updateYamlMainClass("plugin.yml", oldMainClass, newMainClass);
        updateYamlMainClass("paper-plugin.yml", oldMainClass, newMainClass);
    }

    private void updateYamlMainClass(String fileName, String oldMainClass, String newMainClass) {
        byte[] yamlData = resources.get(fileName);
        if (yamlData == null) return;

        String content = new String(yamlData, StandardCharsets.UTF_8);
        Pattern mainPattern = Pattern.compile("(?m)^(\\s*main\\s*:\\s*['\"]?)"
                + Pattern.quote(oldMainClass)
                + "(['\"]?\\s*(?:#.*)?)$");
        Matcher matcher = mainPattern.matcher(content);
        String updated = matcher.replaceFirst("$1" + Matcher.quoteReplacement(newMainClass) + "$2");

        if (!content.equals(updated)) {
            resources.put(fileName, updated.getBytes(StandardCharsets.UTF_8));
            Logger.info("Updated main class in {} : {} -> {}", fileName, oldMainClass, newMainClass);
        }
    }

    public String getDetectedMainClass() {
        return detectedMainClass;
    }

    public String getManifestMainClass() {
        if (manifest == null) return null;
        return manifest.getMainAttributes().getValue("Main-Class");
    }

    public void updateManifestMainClass(String oldMainClass, String newMainClass) {
        if (manifest == null) return;
        String current = manifest.getMainAttributes().getValue("Main-Class");
        if (oldMainClass.equals(current)) {
            manifest.getMainAttributes().putValue("Main-Class", newMainClass);
            Logger.info("Updated Main-Class in manifest: {} -> {}", oldMainClass, newMainClass);
        }
    }

    /**
     * Snapshot all classes BEFORE flow transforms run.
     * These snapshots are used as fallback when COMPUTE_FRAMES produces
     * invalid frames due to structurally complex bytecode from flow transforms.
     */
    public void snapshotPreFlowClasses(ClassPool pool) {
        for (Map.Entry<String, ClassNode> entry : pool.getClassMap().entrySet()) {
            try {
                ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected String getCommonSuperClass(String t1, String t2) {
                        return "java/lang/Object";
                    }
                };
                entry.getValue().accept(w);
                preFlowClassBytes.put(entry.getKey(), w.toByteArray());
            } catch (Exception e) {
                // Pre-flow snapshot failed — no fallback for this class
            }
        }
        Logger.info("Saved pre-flow snapshots for {} classes", preFlowClassBytes.size());
    }

    public void writeJar(ClassPool pool, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (JarOutputStream jos = manifest != null
                ? new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputPath)), manifest)
                : new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputPath)))) {

            for (Map.Entry<String, ClassNode> entry : pool.getClassMap().entrySet()) {
                ClassNode classNode = entry.getValue();
                byte[] bytes;
                String originalName = pool.getOriginalName(classNode.name);

                if (!pool.isDirty(classNode.name) && originalName.equals(classNode.name)) {
                    bytes = originalClassBytes.get(originalName);
                    if (bytes != null) {
                        JarEntry jarEntry = new JarEntry(classNode.name + ".class");
                        jos.putNextEntry(jarEntry);
                        jos.write(bytes);
                        jos.closeEntry();
                        continue;
                    }
                }

                // Strip all stale FrameNode entries before writing.
                // Classes are loaded with EXPAND_FRAMES which creates FrameNodes,
                // but after transformers modify the instruction list these become
                // invalid (wrong positions, wrong stack state). COMPUTE_FRAMES
                // regenerates them from scratch — stale ones must be removed first
                // or they can confuse the frame computation.
                for (org.objectweb.asm.tree.MethodNode mn : classNode.methods) {
                    if (mn.instructions == null) continue;
                    AbstractInsnNode insn = mn.instructions.getFirst();
                    while (insn != null) {
                        AbstractInsnNode next = insn.getNext();
                        if (insn instanceof org.objectweb.asm.tree.FrameNode) {
                            mn.instructions.remove(insn);
                        }
                        insn = next;
                    }
                }

                try {
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try {
                                // Try to resolve using the class pool for better accuracy
                                ClassNode c1 = pool.getClass(type1);
                                if (c1 == null) c1 = pool.getLibraryClasses().get(type1);
                                ClassNode c2 = pool.getClass(type2);
                                if (c2 == null) c2 = pool.getLibraryClasses().get(type2);

                                if (c1 != null && c2 != null) {
                                    if (isAssignableFrom(pool, type2, type1)) return type1;
                                    if (isAssignableFrom(pool, type1, type2)) return type2;

                                    String current = type1;
                                    while (current != null && !current.equals("java/lang/Object")) {
                                        if (isAssignableFrom(pool, type2, current)) return current;
                                        ClassNode cn = pool.getClass(current);
                                        if (cn == null) cn = pool.getLibraryClasses().get(current);
                                        current = (cn != null) ? cn.superName : null;
                                    }
                                }
                            } catch (Exception ignored) {
                                // Defensive: if hierarchy resolution fails for any reason,
                                // fall through to the safe default
                            }
                            return "java/lang/Object";
                        }
                    };
                    classNode.accept(writer);
                    bytes = writer.toByteArray();

                    // Verify the generated bytes — COMPUTE_FRAMES can silently
                    // produce wrong frames when bytecode has structural issues
                    // from flow transforms (different stack sizes at merge points).
                    try {
                        ClassReader verifyReader = new ClassReader(bytes);
                        ClassNode verifyNode = new ClassNode();
                        verifyReader.accept(verifyNode, ClassReader.EXPAND_FRAMES);
                        for (org.objectweb.asm.tree.MethodNode mn : verifyNode.methods) {
                            if (mn.instructions == null || mn.instructions.size() == 0) continue;
                            if ((mn.access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_NATIVE)) != 0) continue;
                            org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.BasicValue> analyzer =
                                    new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier());
                            analyzer.analyze(verifyNode.name, mn);
                        }
                    } catch (Exception verifyEx) {
                        Logger.warn("Post-COMPUTE_FRAMES verification failed for {}: {} — using pre-flow snapshot",
                                classNode.name, verifyEx.getMessage());
                        byte[] snapshot = preFlowClassBytes.get(classNode.name);
                        if (snapshot != null) {
                            bytes = snapshot;
                        }
                        // else keep the COMPUTE_FRAMES bytes as best-effort
                    }
                } catch (org.objectweb.asm.MethodTooLargeException e) {
                    Logger.warn("Method too large in {}, keeping original class: {}",
                            classNode.name, e.getMessage());
                    bytes = preFlowClassBytes.getOrDefault(classNode.name,
                            originalClassBytes.get(originalName));
                } catch (Exception e) {
                    Logger.warn("COMPUTE_FRAMES failed for {}: {} — using pre-flow snapshot",
                            classNode.name, e.getMessage());
                    bytes = preFlowClassBytes.getOrDefault(classNode.name,
                            originalClassBytes.get(originalName));
                }

                // Final failsafe
                if (bytes == null) {
                    Logger.error("No valid bytes for {}. Skipping.", classNode.name);
                    continue;
                }

                JarEntry jarEntry = new JarEntry(classNode.name + ".class");
                jos.putNextEntry(jarEntry);
                jos.write(bytes);
                jos.closeEntry();
            }

            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }

        Logger.info("Written obfuscated jar to {}", outputPath);
    }

    private boolean isAssignableFrom(ClassPool pool, String child, String parent) {
        if (child.equals(parent)) return true;
        if (parent.equals("java/lang/Object")) return true;

        String current = child;
        int depth = 0;
        while (current != null && !current.equals("java/lang/Object") && depth < 64) {
            if (current.equals(parent)) return true;
            ClassNode cn = pool.getClass(current);
            if (cn == null) cn = pool.getLibraryClasses().get(current);
            if (cn == null) break;

            // Check interfaces
            if (cn.interfaces != null) {
                for (String iface : cn.interfaces) {
                    if (iface.equals(parent)) return true;
                }
            }

            current = cn.superName;
            depth++;
        }
        return false;
    }
}
