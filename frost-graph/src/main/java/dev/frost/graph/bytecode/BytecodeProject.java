package dev.frost.graph.bytecode;

import dev.frost.graph.GraphIds;

import java.security.MessageDigest;
import java.util.*;

/** Immutable bytecode input. It deliberately owns bytes, never ASM tree nodes. */
public final class BytecodeProject {
    private final Map<String, byte[]> classes;
    private final Set<String> libraryClasses;
    private final String fingerprint;
    private volatile BytecodeProjectIndex index;

    public BytecodeProject(Map<String, byte[]> classes, Set<String> libraryClasses) {
        TreeMap<String, byte[]> copy = new TreeMap<>();
        if (classes != null) classes.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        this.classes = Collections.unmodifiableMap(copy);
        this.libraryClasses = libraryClasses == null ? Set.of() : Set.copyOf(libraryClasses);
        this.fingerprint = fingerprint(copy);
    }

    public Set<String> classNames() { return classes.keySet(); }
    public Optional<byte[]> classBytes(String name) {
        byte[] bytes = classes.get(name);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }
    byte[] bytesUnsafe(String name) { return classes.get(name); }
    public boolean isLibrary(String name) { return libraryClasses.contains(name); }
    public String fingerprint() { return fingerprint; }
    public int size() { return classes.size(); }

    public BytecodeProjectIndex index() {
        return index(new dev.frost.graph.GraphBuildContext(dev.frost.graph.GraphOptions.defaults(),
                dev.frost.graph.GraphCancellation.NONE, dev.frost.graph.GraphProgressListener.NONE,
                new dev.frost.graph.GraphCache()));
    }

    BytecodeProjectIndex index(dev.frost.graph.GraphBuildContext context) {
        BytecodeProjectIndex value = index;
        if (value != null) return value;
        synchronized (this) {
            value = index;
            if (value == null) index = value = BytecodeProjectIndex.build(this, context);
            return value;
        }
    }

    private static String fingerprint(Map<String, byte[]> classes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            classes.forEach((name, bytes) -> {
                digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(bytes);
            });
            return GraphIds.hex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
