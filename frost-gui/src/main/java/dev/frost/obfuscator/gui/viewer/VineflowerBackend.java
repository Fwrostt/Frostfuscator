package dev.frost.obfuscator.gui.viewer;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

public final class VineflowerBackend implements DecompilerBackend {
    @Override
    public String id() {
        return "vineflower";
    }

    @Override
    public String displayName() {
        return "Vineflower";
    }

    @Override
    public String version() {
        return "1.12.0";
    }

    @Override
    public DecompileResult decompile(Path archive, String classEntry) throws Exception {
        Instant started = Instant.now();
        List<String> diagnostics = new ArrayList<>();
        MemoryResultSaver saver = new MemoryResultSaver();
        String internalName = classEntry.substring(0, classEntry.length() - ".class".length());
        String rootName = internalName.contains("$")
                ? internalName.substring(0, internalName.indexOf('$')) : internalName;
        Decompiler.builder()
                .inputs(archive.toFile())
                .output(saver)
                .allowedPrefixes(rootName)
                .options(
                        IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, true,
                        IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, true,
                        IFernflowerPreferences.DECOMPILE_PREVIEW, true,
                        IFernflowerPreferences.IGNORE_INVALID_BYTECODE, true,
                        IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, true,
                        IFernflowerPreferences.DECOMPILER_COMMENTS, true,
                        IFernflowerPreferences.REMOVE_SYNTHETIC, true,
                        IFernflowerPreferences.REMOVE_BRIDGE, true,
                        IFernflowerPreferences.DECOMPILE_ENUM, true,
                        IFernflowerPreferences.DECOMPILE_ASSERTIONS, true,
                        IFernflowerPreferences.HIDE_EMPTY_SUPER, true,
                        IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR, true,
                        IFernflowerPreferences.INCORPORATE_RETURNS, true,
                        IFernflowerPreferences.ENSURE_SYNCHRONIZED_MONITOR, true,
                        IFernflowerPreferences.PREFERRED_LINE_LENGTH, "120",
                        IFernflowerPreferences.THREADS, Integer.toString(
                                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))))
                .logger(new CollectingLogger(diagnostics))
                .build()
                .decompile();

        String source = saver.source(rootName);
        if (source == null) {
            throw new IllegalStateException("Vineflower did not produce source for " + classEntry
                    + diagnosticSuffix(diagnostics));
        }
        return new DecompileResult(source, Duration.between(started, Instant.now()), diagnostics);
    }

    private static String diagnosticSuffix(List<String> diagnostics) {
        if (diagnostics.isEmpty()) return "";
        return ": " + diagnostics.get(diagnostics.size() - 1);
    }

    private static final class CollectingLogger extends IFernflowerLogger {
        private final List<String> diagnostics;

        private CollectingLogger(List<String> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public void writeMessage(String message, Severity severity) {
            if (severity.ordinal() >= Severity.WARN.ordinal()) {
                diagnostics.add(severity + ": " + message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable throwable) {
            if (severity.ordinal() >= Severity.WARN.ordinal()) {
                String detail = throwable == null || throwable.getMessage() == null
                        ? "" : " — " + throwable.getMessage();
                diagnostics.add(severity + ": " + message + detail);
            }
        }
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
