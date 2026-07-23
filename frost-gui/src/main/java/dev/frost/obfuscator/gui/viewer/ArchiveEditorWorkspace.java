package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Manages the in-memory staged working copy of a project JAR archive.
 * Tracks modified class bytes, deleted classes/members, and new files.
 */
public final class ArchiveEditorWorkspace {

    private final Path archivePath;
    private final Map<String, byte[]> stagedClassBytes = new ConcurrentHashMap<>();
    private final Set<String> deletedEntries = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, byte[]> originalClassCache = new ConcurrentHashMap<>();
    private Path tempStagedJar;

    public ArchiveEditorWorkspace(Path archivePath) {
        this.archivePath = archivePath != null ? archivePath.toAbsolutePath().normalize() : null;
    }

    public static String normalizeEntryName(String name) {
        if (name == null) return "";
        String clean = name.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        return clean;
    }

    public Path archivePath() {
        return archivePath;
    }

    public boolean isModified(String entryName) {
        String key = normalizeEntryName(entryName);
        return stagedClassBytes.containsKey(key) || deletedEntries.contains(key);
    }

    public boolean hasUnsavedChanges() {
        return !stagedClassBytes.isEmpty() || !deletedEntries.isEmpty();
    }

    public void updateClassBytes(String entryName, byte[] newBytes) {
        if (entryName == null || newBytes == null) return;
        String key = normalizeEntryName(entryName);
        deletedEntries.remove(key);
        stagedClassBytes.put(key, newBytes);
        invalidateTempStagedJar();
    }

    public byte[] getClassBytes(String entryName) throws IOException {
        String key = normalizeEntryName(entryName);
        if (deletedEntries.contains(key)) {
            return null;
        }
        if (stagedClassBytes.containsKey(key)) {
            return stagedClassBytes.get(key);
        }
        if (originalClassCache.containsKey(key)) {
            return originalClassCache.get(key);
        }

        if (archivePath == null || !Files.exists(archivePath)) return null;

        try (JarFile jar = new JarFile(archivePath.toFile())) {
            JarEntry entry = jar.getJarEntry(key);
            if (entry == null) entry = jar.getJarEntry(entryName);
            if (entry == null) return null;
            try (var is = jar.getInputStream(entry)) {
                byte[] bytes = is.readAllBytes();
                originalClassCache.put(key, bytes);
                return bytes;
            }
        }
    }

    public Map<String, byte[]> getAllClassBytes() throws IOException {
        Map<String, byte[]> pool = new HashMap<>();
        if (archivePath != null && Files.exists(archivePath)) {
            try (JarFile jar = new JarFile(archivePath.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".class") && !deletedEntries.contains(name)) {
                        if (stagedClassBytes.containsKey(name)) {
                            pool.put(name.substring(0, name.length() - 6), stagedClassBytes.get(name));
                        } else {
                            try (var is = jar.getInputStream(entry)) {
                                pool.put(name.substring(0, name.length() - 6), is.readAllBytes());
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, byte[]> staged : stagedClassBytes.entrySet()) {
            String name = staged.getKey();
            if (name.endsWith(".class") && !deletedEntries.contains(name)) {
                pool.put(name.substring(0, name.length() - 6), staged.getValue());
            }
        }

        return pool;
    }

    public synchronized Path getStagedJarPath() throws IOException {
        if (!hasUnsavedChanges()) {
            return archivePath;
        }
        if (tempStagedJar != null && Files.exists(tempStagedJar)) {
            return tempStagedJar;
        }

        tempStagedJar = Files.createTempFile("frost-workspace-staged-", ".jar");
        tempStagedJar.toFile().deleteOnExit();
        writeStagedJar(tempStagedJar);
        return tempStagedJar;
    }

    public synchronized void invalidateTempStagedJar() {
        if (tempStagedJar != null) {
            try {
                Files.deleteIfExists(tempStagedJar);
            } catch (Exception ignored) {}
            tempStagedJar = null;
        }
    }

    public void deleteEntry(String entryName) {
        if (entryName == null) return;
        String key = normalizeEntryName(entryName);
        stagedClassBytes.remove(key);
        deletedEntries.add(key);
        invalidateTempStagedJar();
    }

    public void deleteMethod(String entryName, String methodName, String methodDesc) throws IOException {
        byte[] current = getClassBytes(entryName);
        if (current == null) return;

        ClassNode node = new ClassNode();
        new ClassReader(current).accept(node, 0);

        boolean removed = node.methods.removeIf(m -> m.name.equals(methodName) && (methodDesc == null || methodDesc.isEmpty() || m.desc.equals(methodDesc)));
        if (removed) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            updateClassBytes(entryName, writer.toByteArray());
        }
    }

    public void deleteField(String entryName, String fieldName, String fieldDesc) throws IOException {
        byte[] current = getClassBytes(entryName);
        if (current == null) return;

        ClassNode node = new ClassNode();
        new ClassReader(current).accept(node, 0);

        boolean removed = node.fields.removeIf(f -> f.name.equals(fieldName) && (fieldDesc == null || fieldDesc.isEmpty() || f.desc.equals(fieldDesc)));
        if (removed) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            updateClassBytes(entryName, writer.toByteArray());
        }
    }

    public void revertClass(String entryName) {
        stagedClassBytes.remove(entryName);
        deletedEntries.remove(entryName);
    }

    public void revertAll() {
        stagedClassBytes.clear();
        deletedEntries.clear();
    }

    public void writeStagedJar(Path targetOutput) throws IOException {
        if (archivePath == null || !Files.exists(archivePath)) {
            throw new IOException("Original archive does not exist.");
        }

        Path target = targetOutput.toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".frost-staged-", ".jar");

        try (JarFile jar = new JarFile(archivePath.toFile());
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {

            Set<String> written = new HashSet<>();

            // Write updated staged entries first
            for (Map.Entry<String, byte[]> staged : stagedClassBytes.entrySet()) {
                String name = staged.getKey();
                if (deletedEntries.contains(name)) continue;

                JarEntry jarEntry = new JarEntry(name);
                jos.putNextEntry(jarEntry);
                jos.write(staged.getValue());
                jos.closeEntry();
                written.add(name);
            }

            // Write non-deleted entries from original archive
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (deletedEntries.contains(name) || written.contains(name) || entry.isDirectory()) {
                    continue;
                }

                JarEntry copy = new JarEntry(name);
                copy.setTime(entry.getTime());
                jos.putNextEntry(copy);
                try (var is = jar.getInputStream(entry)) {
                    is.transferTo(jos);
                }
                jos.closeEntry();
                written.add(name);
            }
        } catch (Throwable throwable) {
            Files.deleteIfExists(temp);
            if (throwable instanceof IOException io) throw io;
            throw new IOException(throwable);
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
