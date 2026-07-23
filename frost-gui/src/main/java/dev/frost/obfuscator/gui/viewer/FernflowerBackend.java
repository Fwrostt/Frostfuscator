package dev.frost.obfuscator.gui.viewer;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.jar.Manifest;

public final class FernflowerBackend implements DecompilerBackend {
    @Override
    public String id() {
        return "fernflower";
    }

    @Override
    public String displayName() {
        return "Fernflower";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public DecompileResult decompile(Path archive, String classEntry) throws Exception {
        Instant started = Instant.now();
        List<String> diagnostics = new ArrayList<>();
        MemoryResultSaver saver = new MemoryResultSaver();

        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, "1");
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ENUM, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ASSERTIONS, "1");
        options.put(IFernflowerPreferences.HIDE_EMPTY_SUPER, "1");
        options.put(IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR, "1");
        options.put(IFernflowerPreferences.INCORPORATE_RETURNS, "1");
        options.put(IFernflowerPreferences.ENSURE_SYNCHRONIZED_MONITOR, "1");
        options.put(IFernflowerPreferences.DECOMPILER_COMMENTS, "1");
        options.put(IFernflowerPreferences.PREFERRED_LINE_LENGTH, "120");

        PrintStream nullStream = new PrintStream(new ByteArrayOutputStream());
        Fernflower fernflower = new Fernflower(saver, options, new PrintStreamLogger(nullStream));
        try {
            fernflower.addSource(archive.toFile());
            fernflower.decompileContext();
        } finally {
            fernflower.clearContext();
        }

        String internalName = classEntry.substring(0, classEntry.length() - ".class".length());
        String rootName = internalName.contains("$")
                ? internalName.substring(0, internalName.indexOf('$')) : internalName;

        String source = saver.source(rootName);
        if (source == null) {
            throw new IllegalStateException("Fernflower did not produce source for " + classEntry);
        }
        return new DecompileResult(source, Duration.between(started, Instant.now()), diagnostics);
    }

    private static final class MemoryResultSaver implements IResultSaver {
        private final Map<String, String> sources = new LinkedHashMap<>();

        private String source(String rootName) {
            String exact = sources.get(rootName);
            if (exact != null) return exact;
            return sources.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(rootName)
                            || entry.getKey().startsWith(rootName + "$"))
                    .map(Map.Entry::getValue)
                    .findFirst().orElseGet(() -> sources.values().stream().findFirst().orElse(null));
        }

        @Override public void saveFolder(String path) {}
        @Override public void copyFile(String source, String path, String entryName) {}
        @Override public void createArchive(String path, String archiveName, Manifest manifest) {}
        @Override public void saveDirEntry(String path, String archiveName, String entryName) {}
        @Override public void copyEntry(String source, String path, String archiveName, String entry) {}
        @Override public void closeArchive(String path, String archiveName) {}

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName,
                                  String content, int[] mapping) {
            sources.put(qualifiedName, content);
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                   String entryName, String content) {
            sources.put(qualifiedName, content);
        }
    }
}
