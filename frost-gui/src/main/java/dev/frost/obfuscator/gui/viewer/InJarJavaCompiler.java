package dev.frost.obfuscator.gui.viewer;

import javax.tools.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

/**
 * In-memory Java compiler that compiles source text against an indexed JAR class pool.
 * Resolves all cross-class references, imports, and dependencies directly from memory.
 */
public final class InJarJavaCompiler {

    public record CompilationDiagnostic(long lineNumber, long columnNumber, String code, String message, Diagnostic.Kind kind) {}

    public record CompilationResult(boolean success, Map<String, byte[]> compiledClasses, List<CompilationDiagnostic> diagnostics) {}

    /**
     * Compiles Java source text in memory against the provided JAR class map.
     *
     * @param className     fully qualified name of class being compiled (e.g. "com/example/MyClass")
     * @param sourceText    Java source code string
     * @param jarClassPool  map of internal class names ("com/example/OtherClass") to class byte arrays
     * @return CompilationResult containing compiled bytecode or diagnostic errors
     */
    public CompilationResult compile(String className, String sourceText, Map<String, byte[]> jarClassPool) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompilationResult(false, Map.of(), List.of(
                    new CompilationDiagnostic(1, 1, "NO_COMPILER",
                            "JDK JavaCompiler not found. Please ensure Frostfuscator is running under a JDK JVM rather than a JRE.",
                            Diagnostic.Kind.ERROR)
            ));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fileManager = new InMemoryFileManager(standardFileManager, jarClassPool);

        String cleanName = className;
        if (cleanName.endsWith(".class")) {
            cleanName = cleanName.substring(0, cleanName.length() - 6);
        }
        cleanName = cleanName.trim().replace('\\', '/');
        while (cleanName.startsWith("/")) cleanName = cleanName.substring(1);
        String normalizedName = cleanName.replace('/', '.');
        JavaFileObject sourceFile = new StringSourceFileObject(cleanName, sourceText);

        List<String> options = List.of("-nowarn", "-source", "17", "-target", "17");
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, List.of(sourceFile)
        );

        boolean success = task.call();
        List<CompilationDiagnostic> diagnosticList = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            diagnosticList.add(new CompilationDiagnostic(
                    diag.getLineNumber(),
                    diag.getColumnNumber(),
                    diag.getCode() == null ? "" : diag.getCode(),
                    diag.getMessage(Locale.ROOT),
                    diag.getKind()
            ));
        }

        return new CompilationResult(success, fileManager.getOutputBytecode(), diagnosticList);
    }

    private static final class StringSourceFileObject extends SimpleJavaFileObject {
        private final String content;

        StringSourceFileObject(String className, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> jarClassPool;
        private final Map<String, BytecodeOutputJavaFileObject> outputFiles = new HashMap<>();

        InMemoryFileManager(JavaFileManager fileManager, Map<String, byte[]> jarClassPool) {
            super(fileManager);
            this.jarClassPool = jarClassPool;
        }

        Map<String, byte[]> getOutputBytecode() {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, BytecodeOutputJavaFileObject> entry : outputFiles.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return result;
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
                throws IOException {
            Iterable<JavaFileObject> superFiles = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH && location != StandardLocation.PLATFORM_CLASS_PATH) {
                return superFiles;
            }

            List<JavaFileObject> result = new ArrayList<>();
            if (superFiles != null) {
                superFiles.forEach(result::add);
            }

            if (kinds.contains(JavaFileObject.Kind.CLASS)) {
                String pkgPrefix = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
                for (Map.Entry<String, byte[]> entry : jarClassPool.entrySet()) {
                    String internalName = entry.getKey();
                    if (internalName.startsWith(pkgPrefix)) {
                        String relative = internalName.substring(pkgPrefix.length());
                        if (recurse || !relative.contains("/")) {
                            result.add(new ByteArrayInputJavaFileObject(internalName, entry.getValue()));
                        }
                    }
                }
            }

            return result;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof ByteArrayInputJavaFileObject inObject) {
                return inObject.getBinaryName();
            }
            return super.inferBinaryName(location, file);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            String internalName = className.replace('.', '/');
            BytecodeOutputJavaFileObject outObject = new BytecodeOutputJavaFileObject(internalName);
            outputFiles.put(internalName, outObject);
            return outObject;
        }
    }

    private static final class ByteArrayInputJavaFileObject extends SimpleJavaFileObject {
        private final String internalName;
        private final byte[] bytes;

        ByteArrayInputJavaFileObject(String internalName, byte[] bytes) {
            super(URI.create("bytes:///" + internalName + Kind.CLASS.extension), Kind.CLASS);
            this.internalName = internalName;
            this.bytes = bytes;
        }

        String getBinaryName() {
            return internalName.replace('/', '.');
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class BytecodeOutputJavaFileObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        BytecodeOutputJavaFileObject(String internalName) {
            super(URI.create("bytes:///" + internalName + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return outputStream;
        }

        byte[] toByteArray() {
            return outputStream.toByteArray();
        }
    }
}
