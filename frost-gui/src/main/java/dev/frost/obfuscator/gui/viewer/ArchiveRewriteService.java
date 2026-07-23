package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Copy-only archive transformations used by the viewer's advanced tools.
 * The selected project input is never modified in place.
 */
public final class ArchiveRewriteService {
    public RewriteSummary replaceStrings(Path input, Path output, String find, String replacement)
            throws IOException {
        if (find == null || find.isEmpty()) throw new IllegalArgumentException("Find text cannot be empty.");
        String value = replacement == null ? "" : replacement;
        int[] changed = {0};
        RewriteSummary summary = rewrite(input, output, (name, bytes) -> {
            if (!name.endsWith(".class")) return bytes;
            ClassNode node = readNode(bytes, 0);
            for (FieldNode field : node.fields) {
                if (find.equals(field.value)) {
                    field.value = value;
                    changed[0]++;
                }
            }
            for (MethodNode method : node.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null; instruction = instruction.getNext()) {
                    if (instruction instanceof LdcInsnNode ldc && find.equals(ldc.cst)) {
                        ldc.cst = value;
                        changed[0]++;
                    }
                }
            }
            return writeNode(node, ClassWriter.COMPUTE_MAXS);
        });
        return new RewriteSummary(summary.output(), summary.classesVisited(), changed[0],
                "Replaced " + changed[0] + " exact string constant" + (changed[0] == 1 ? "" : "s") + ".");
    }

    public RewriteSummary removeStackFrames(Path input, Path output) throws IOException {
        int[] frames = {0};
        RewriteSummary summary = rewrite(input, output, (name, bytes) -> {
            if (!name.endsWith(".class")) return bytes;
            ClassNode node = readNode(bytes, 0);
            for (MethodNode method : node.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null; ) {
                    AbstractInsnNode next = instruction.getNext();
                    if (instruction instanceof FrameNode) {
                        method.instructions.remove(instruction);
                        frames[0]++;
                    }
                    instruction = next;
                }
            }
            return writeNode(node, ClassWriter.COMPUTE_MAXS);
        });
        return new RewriteSummary(summary.output(), summary.classesVisited(), frames[0],
                "Removed " + frames[0] + " stack-map frame" + (frames[0] == 1 ? "" : "s")
                        + ". Modern JVMs may reject classes that require frames.");
    }

    public RewriteSummary changeClassVersion(Path input, Path output, int targetMajor) throws IOException {
        if (targetMajor < 45 || targetMajor > 70) {
            throw new IllegalArgumentException("Class version must be between 45 (Java 1.1) and 70 (Java 26).");
        }
        int[] changed = {0};
        RewriteSummary summary = rewrite(input, output, (name, bytes) -> {
            if (!name.endsWith(".class")) return bytes;
            if (bytes.length < 8) throw new IllegalArgumentException("Malformed class entry: " + name);
            int current = (bytes[6] & 0xff) << 8 | bytes[7] & 0xff;
            if (current == targetMajor) return bytes;
            byte[] copy = bytes.clone();
            copy[6] = (byte) (targetMajor >>> 8);
            copy[7] = (byte) targetMajor;
            changed[0]++;
            return copy;
        });
        return new RewriteSummary(summary.output(), summary.classesVisited(), changed[0],
                "Changed " + changed[0] + " class header" + (changed[0] == 1 ? "" : "s")
                        + " to major " + targetMajor + " (Java " + javaVersion(targetMajor) + ").");
    }

    private RewriteSummary rewrite(Path input, Path output, BiFunction<String, byte[], byte[]> transformer)
            throws IOException {
        Path source = input.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        if (source.equals(target)) {
            throw new IllegalArgumentException("Choose a new output file; the input archive is never overwritten.");
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".frost-viewer-", ".jar");
        int classes = 0;
        try (JarFile jar = new JarFile(source.toFile());
             JarOutputStream outputStream = createOutput(temp, sanitizedManifest(jar.getManifest()))) {
            Enumeration<JarEntry> entries = jar.entries();
            Set<String> written = new HashSet<>();
            written.add("META-INF/MANIFEST.MF");
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.equalsIgnoreCase("META-INF/MANIFEST.MF")
                        || isSignatureEntry(name) || !written.add(name)) {
                    continue;
                }
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (name.endsWith(".class")) classes++;
                byte[] transformed;
                try {
                    transformed = transformer.apply(name, bytes);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not rewrite " + name + ": " + safeMessage(exception), exception);
                }
                JarEntry copy = new JarEntry(name);
                copy.setTime(entry.getTime());
                if (entry.getComment() != null) copy.setComment(entry.getComment());
                outputStream.putNextEntry(copy);
                outputStream.write(transformed);
                outputStream.closeEntry();
            }
        } catch (Throwable throwable) {
            Files.deleteIfExists(temp);
            if (throwable instanceof IOException io) throw io;
            throw throwable;
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new RewriteSummary(target, classes, 0, "");
    }

    private static JarOutputStream createOutput(Path temp, Manifest manifest) throws IOException {
        BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(temp));
        return manifest == null ? new JarOutputStream(stream) : new JarOutputStream(stream, manifest);
    }

    private static Manifest sanitizedManifest(Manifest source) {
        if (source == null) return null;
        Manifest clean = new Manifest();
        copyAttributes(source.getMainAttributes(), clean.getMainAttributes());
        clean.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        source.getEntries().forEach((name, attributes) -> {
            Attributes copy = new Attributes();
            copyAttributes(attributes, copy);
            if (!copy.isEmpty()) clean.getEntries().put(name, copy);
        });
        return clean;
    }

    private static void copyAttributes(Attributes source, Attributes target) {
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!name.equalsIgnoreCase("Signature-Version")
                    && !name.toLowerCase(Locale.ROOT).endsWith("-digest")
                    && !name.toLowerCase(Locale.ROOT).contains("-digest-")) {
                target.put(key, value);
            }
        });
    }

    private static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.equals("META-INF/INDEX.LIST"));
    }

    private static ClassNode readNode(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    private static byte[] writeNode(ClassNode node, int flags) {
        ClassWriter writer = new ClassWriter(flags);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static String javaVersion(int major) {
        return major == 45 ? "1.1" : Integer.toString(major - 44);
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record RewriteSummary(Path output, int classesVisited, int changedValues, String message) {}
}
