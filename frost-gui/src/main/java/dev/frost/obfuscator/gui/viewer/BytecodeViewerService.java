package dev.frost.obfuscator.gui.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BytecodeViewerService implements AutoCloseable {
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            new ViewerThreadFactory());
    private final ArchiveInspector inspector = new ArchiveInspector();
    private final ArchiveRewriteService rewriter = new ArchiveRewriteService();
    private final InJarJavaCompiler compiler = new InJarJavaCompiler();
    private final BytecodeAssembler assembler = new BytecodeAssembler();
    private final Map<Path, ArchiveEditorWorkspace> workspaces = new ConcurrentHashMap<>();
    private final List<DecompilerBackend> backends = List.of(
            new VineflowerBackend(),
            new CfrBackend(),
            new ProcyonBackend(),
            new FernflowerBackend()
    );
    private final Map<CacheKey, DecompileTask> sourceCache = new ConcurrentHashMap<>();
    private final AtomicReference<DecompileTask> activeDecompile = new AtomicReference<>();
    private final Map<Fingerprint, CompletableFuture<ArchiveInspector.ArchiveScan>> scanCache =
            new ConcurrentHashMap<>();

    public List<DecompilerBackend> backends() {
        return backends;
    }

    public CompletableFuture<ArchiveInspector.ArchiveSnapshot> open(Path archive) {
        return supply(() -> inspector.open(archive));
    }

    public CompletableFuture<ArchiveInspector.ClassInspection> inspect(Path archive, String classEntry) {
        return supply(() -> {
            byte[] bytes = workspace(archive).getClassBytes(classEntry);
            if (bytes != null) {
                return inspector.inspectClassBytes(bytes, classEntry);
            }
            return inspector.inspectClass(archive, classEntry);
        });
    }

    public CompletableFuture<String> resource(Path archive, String entryName) {
        return supply(() -> inspector.resourcePreview(archive, entryName));
    }

    public CompletableFuture<String> manifest(Path archive) {
        return supply(() -> inspector.manifestText(archive));
    }

    public CompletableFuture<ArchiveInspector.ArchiveScan> scan(Path archive) {
        try {
            Fingerprint fingerprint = fingerprint(archive);
            return scanCache.computeIfAbsent(fingerprint,
                    key -> supply(() -> inspector.scan(archive)).whenComplete((result, error) -> {
                        if (error != null) scanCache.remove(key);
                    }));
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<DecompileResult> decompile(
            DecompilerBackend backend, Path archive, String classEntry, boolean refresh) {
        try {
            ArchiveEditorWorkspace ws = workspace(archive);
            Path effectiveArchive = ws.getStagedJarPath();
            CacheKey key = new CacheKey(fingerprint(effectiveArchive), backend.id(), classEntry);
            DecompileTask task;
            synchronized (sourceCache) {
                if (refresh) cancelCachedTask(key);
                task = sourceCache.get(key);
                if (task == null || task.future.isCancelled()) {
                    task = startDecompile(key, () -> backend.decompile(effectiveArchive, classEntry));
                    sourceCache.put(key, task);
                }
            }
            activate(task);
            return task.future;
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void cancelActiveDecompilation() {
        DecompileTask task = activeDecompile.getAndSet(null);
        if (task != null) {
            task.cancel();
            sourceCache.remove(task.key, task);
        }
    }

    public CompletableFuture<ArchiveRewriteService.RewriteSummary> replaceStrings(
            Path input, Path output, String find, String replacement) {
        return supply(() -> rewriter.replaceStrings(input, output, find, replacement));
    }

    public CompletableFuture<ArchiveRewriteService.RewriteSummary> removeFrames(Path input, Path output) {
        return supply(() -> rewriter.removeStackFrames(input, output));
    }

    public CompletableFuture<ArchiveRewriteService.RewriteSummary> changeVersions(
            Path input, Path output, int targetMajor) {
        return supply(() -> rewriter.changeClassVersion(input, output, targetMajor));
    }

    public CompletableFuture<dev.frost.obfuscator.gui.export.ArchiveExportEngine.ExportSummary> exportSources(
            Path archive, List<DecompilerBackend> backends, dev.frost.obfuscator.gui.export.ExportOptions options,
            Path targetDir, String filter) {
        return supply(() -> new dev.frost.obfuscator.gui.export.ArchiveExportEngine()
                .exportSources(archive, backends, options, targetDir, filter));
    }

    public CompletableFuture<dev.frost.obfuscator.gui.export.ArchiveExportEngine.ExportSummary> exportSourceZip(
            Path archive, DecompilerBackend backend, dev.frost.obfuscator.gui.export.ExportOptions options, Path targetZip) {
        return supply(() -> new dev.frost.obfuscator.gui.export.ArchiveExportEngine()
                .exportSourceZip(archive, backend, options, targetZip));
    }

    public CompletableFuture<dev.frost.obfuscator.gui.export.ArchiveExportEngine.ExportSummary> exportRawBytecode(
            Path archive, Path targetDir, String filter, boolean asmTextifier, boolean javapStyle, boolean cfg, boolean methodOnly) {
        return supply(() -> new dev.frost.obfuscator.gui.export.ArchiveExportEngine()
                .exportRawBytecode(archive, targetDir, filter, asmTextifier, javapStyle, cfg, methodOnly));
    }

    public CompletableFuture<dev.frost.obfuscator.gui.export.ArchiveExportEngine.ExportSummary> exportProjectResources(
            Path archive, Path targetDir) {
        return supply(() -> new dev.frost.obfuscator.gui.export.ArchiveExportEngine()
                .exportProjectResources(archive, targetDir));
    }

    public CompletableFuture<dev.frost.obfuscator.gui.export.ArchiveExportEngine.ExportSummary> rebuildSanitizedJar(
            Path archive, Path targetJar) {
        return supply(() -> new dev.frost.obfuscator.gui.export.ArchiveExportEngine()
                .rebuildSanitizedJar(archive, targetJar));
    }

    public CompletableFuture<List<dev.frost.obfuscator.gui.stringexport.StringRecord>> scanAllStrings(
            Path archive, boolean includeNames) {
        return supply(() -> new dev.frost.obfuscator.gui.stringexport.StringExportEngine()
                .extractAllStrings(archive, includeNames));
    }

    public CompletableFuture<Void> exportStrings(
            List<dev.frost.obfuscator.gui.stringexport.StringRecord> records, Path outputFile, String format) {
        return supply(() -> {
            dev.frost.obfuscator.gui.stringexport.StringExportEngine engine =
                    new dev.frost.obfuscator.gui.stringexport.StringExportEngine();
            switch (format.toLowerCase()) {
                case "txt" -> engine.exportTxt(records, outputFile);
                case "csv" -> engine.exportCsv(records, outputFile);
                case "json" -> engine.exportJson(records, outputFile);
                case "jsonl" -> engine.exportJsonl(records, outputFile);
                default -> throw new IllegalArgumentException("Unsupported format: " + format);
            }
            return null;
        });
    }

    public ArchiveEditorWorkspace workspace(Path archive) {
        Path normalized = archive.toAbsolutePath().normalize();
        return workspaces.computeIfAbsent(normalized, ArchiveEditorWorkspace::new);
    }

    public CompletableFuture<InJarJavaCompiler.CompilationResult> compileSource(
            Path archive, String classEntry, String sourceText) {
        return supply(() -> {
            ArchiveEditorWorkspace ws = workspace(archive);
            Map<String, byte[]> classPool = ws.getAllClassBytes();
            InJarJavaCompiler.CompilationResult result = compiler.compile(classEntry, sourceText, classPool);
            if (result.success() && result.compiledClasses() != null) {
                for (Map.Entry<String, byte[]> entry : result.compiledClasses().entrySet()) {
                    String internalName = entry.getKey();
                    String path = internalName + ".class";
                    ws.updateClassBytes(path, entry.getValue());
                }
                invalidate(archive);
            }
            return result;
        });
    }

    public CompletableFuture<BytecodeAssembler.AssemblyResult> assembleBytecode(
            Path archive, String classEntry, String bytecodeText) {
        return supply(() -> {
            ArchiveEditorWorkspace ws = workspace(archive);
            byte[] original = ws.getClassBytes(classEntry);
            BytecodeAssembler.AssemblyResult result = assembler.assemble(original, bytecodeText);
            if (result.success() && result.bytecode() != null) {
                ws.updateClassBytes(classEntry, result.bytecode());
                invalidate(archive);
            }
            return result;
        });
    }

    public CompletableFuture<Void> deleteEntry(Path archive, String entryName) {
        return supply(() -> {
            workspace(archive).deleteEntry(entryName);
            invalidate(archive);
            return null;
        });
    }

    public CompletableFuture<Void> deleteMethod(Path archive, String classEntry, String methodName, String methodDesc) {
        return supply(() -> {
            workspace(archive).deleteMethod(classEntry, methodName, methodDesc);
            invalidate(archive);
            return null;
        });
    }

    public CompletableFuture<Void> deleteField(Path archive, String classEntry, String fieldName, String fieldDesc) {
        return supply(() -> {
            workspace(archive).deleteField(classEntry, fieldName, fieldDesc);
            invalidate(archive);
            return null;
        });
    }

    public CompletableFuture<Void> saveStagedJar(Path archive, Path targetOutput) {
        return supply(() -> {
            workspace(archive).writeStagedJar(targetOutput);
            return null;
        });
    }

    public void invalidate(Path archive) {
        Path normalized = archive.toAbsolutePath().normalize();
        sourceCache.forEach((key, task) -> {
            if (key.fingerprint.path.equals(normalized) && sourceCache.remove(key, task)) task.cancel();
        });
        scanCache.keySet().removeIf(key -> key.path.equals(normalized));
    }

    private DecompileTask startDecompile(CacheKey key, ThrowingSupplier<DecompileResult> supplier) {
        DecompileTask task = new DecompileTask(key);
        task.worker = executor.submit(() -> {
            try {
                if (!task.future.isCancelled()) task.future.complete(supplier.get());
            } catch (CancellationException exception) {
                task.future.cancel(false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                task.future.cancel(false);
            } catch (Exception exception) {
                task.future.completeExceptionally(exception);
            }
        });
        if (task.future.isCancelled()) task.worker.cancel(true);
        task.future.whenComplete((result, error) -> {
            if (error != null) sourceCache.remove(key, task);
            activeDecompile.compareAndSet(task, null);
        });
        return task;
    }

    private void activate(DecompileTask task) {
        DecompileTask previous = task.future.isDone()
                ? activeDecompile.getAndSet(null)
                : activeDecompile.getAndSet(task);
        if (previous != null && previous != task) {
            previous.cancel();
            sourceCache.remove(previous.key, previous);
        }
    }

    private void cancelCachedTask(CacheKey key) {
        DecompileTask cached = sourceCache.remove(key);
        if (cached != null) {
            activeDecompile.compareAndSet(cached, null);
            cached.cancel();
        }
    }

    private <T> CompletableFuture<T> supply(ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    private static Fingerprint fingerprint(Path archive) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        return new Fingerprint(normalized, Files.size(normalized),
                Files.getLastModifiedTime(normalized).toMillis());
    }

    @Override
    public void close() {
        cancelActiveDecompilation();
        sourceCache.values().forEach(DecompileTask::cancel);
        executor.shutdownNow();
        sourceCache.clear();
        scanCache.clear();
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Fingerprint(Path path, long size, long modified) {}
    private record CacheKey(Fingerprint fingerprint, String backend, String classEntry) {}

    private static final class DecompileTask {
        private final CacheKey key;
        private final CompletableFuture<DecompileResult> future = new CompletableFuture<>();
        private volatile Future<?> worker;

        private DecompileTask(CacheKey key) {
            this.key = key;
        }

        private void cancel() {
            future.cancel(true);
            Future<?> submitted = worker;
            if (submitted != null) submitted.cancel(true);
        }
    }

    private static final class ViewerThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "frost-bytecode-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
