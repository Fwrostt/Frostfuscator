package dev.frost.obfuscator.gui.export;

import dev.frost.obfuscator.gui.viewer.DecompileResult;
import dev.frost.obfuscator.gui.viewer.DecompilerBackend;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hardened export engine supporting source exports, raw bytecode disassembly,
 * decompiler comparison reports, resource extraction, and sanitized JAR rebuilding.
 */
public final class ArchiveExportEngine {

    public record ExportSummary(
            Path targetPath,
            int exportedFilesCount,
            long totalBytesWritten,
            String message
    ) {}

    // ── 1. Decompiled Sources Exports ─────────────────────────────────────────────

    public ExportSummary exportSources(
            Path archive,
            List<DecompilerBackend> backends,
            ExportOptions options,
            Path targetDir,
            String packageOrClassFilter
    ) throws Exception {
        Files.createDirectories(targetDir);
        int fileCount = 0;
        long totalBytes = 0;

        List<String> classEntries = getClassEntries(archive, packageOrClassFilter);

        for (DecompilerBackend backend : backends) {
            Path backendDir = targetDir.resolve("sources").resolve(backend.id());
            Files.createDirectories(backendDir);

            for (String classEntry : classEntries) {
                DecompileResult result = backend.decompile(archive, classEntry);
                String sourceCode = result.source();

                String relPath = options.preservePackageStructure()
                        ? classEntry.replace(".class", ".java")
                        : new File(classEntry).getName().replace(".class", ".java");

                Path outputPath = PathSanitizer.validatePathWithinTarget(backendDir, relPath);
                Files.createDirectories(outputPath.getParent());
                byte[] data = sourceCode.getBytes(StandardCharsets.UTF_8);
                Files.write(outputPath, data);
                fileCount++;
                totalBytes += data.length;
            }
        }

        // Generate Decompiler Comparison Report if multiple backends ran
        if (backends.size() > 1) {
            Path reportPath = targetDir.resolve("reports").resolve("decompiler-comparison.md");
            Files.createDirectories(reportPath.getParent());
            String reportMarkdown = generateComparisonReport(archive, backends, classEntries);
            byte[] reportBytes = reportMarkdown.getBytes(StandardCharsets.UTF_8);
            Files.write(reportPath, reportBytes);
            fileCount++;
            totalBytes += reportBytes.length;
        }

        return new ExportSummary(targetDir, fileCount, totalBytes, "Exported decompiled sources successfully.");
    }

    public ExportSummary exportSourceZip(
            Path archive,
            DecompilerBackend backend,
            ExportOptions options,
            Path targetZip
    ) throws Exception {
        Files.createDirectories(targetZip.getParent());
        List<String> classEntries = getClassEntries(archive, null);
        int fileCount = 0;
        long totalBytes = 0;

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            for (String classEntry : classEntries) {
                DecompileResult result = backend.decompile(archive, classEntry);
                String relPath = options.preservePackageStructure()
                        ? classEntry.replace(".class", ".java")
                        : new File(classEntry).getName().replace(".class", ".java");

                String safeEntryName = PathSanitizer.sanitizeRelativePath(relPath);
                byte[] data = result.source().getBytes(StandardCharsets.UTF_8);

                ZipEntry zipEntry = new ZipEntry(safeEntryName);
                zos.putNextEntry(zipEntry);
                zos.write(data);
                zos.closeEntry();
                fileCount++;
                totalBytes += data.length;
            }
        }

        return new ExportSummary(targetZip, fileCount, totalBytes, "Exported source ZIP successfully.");
    }

    public String generateComparisonReport(
            Path archive,
            List<DecompilerBackend> backends,
            List<String> classEntries
    ) {
        StringBuilder report = new StringBuilder();
        report.append("# Decompiler Comparison Report\n\n");
        report.append("- **Archive**: `").append(archive.getFileName()).append("`\n");
        report.append("- **Generated**: ").append(Instant.now()).append("\n");
        report.append("- **Classes Evaluated**: ").append(classEntries.size()).append("\n\n");

        report.append("| Class | Decompiler | Status | LOC | Elapsed Time |\n");
        report.append("| :--- | :--- | :--- | :--- | :--- |\n");

        for (String classEntry : classEntries) {
            for (DecompilerBackend backend : backends) {
                try {
                    DecompileResult res = backend.decompile(archive, classEntry);
                    int loc = res.source().split("\r\n|\r|\n").length;
                    report.append("| `").append(classEntry).append("` | ")
                            .append(backend.displayName()).append(" | SUCCESS | ")
                            .append(loc).append(" lines | ")
                            .append(res.elapsed().toMillis()).append(" ms |\n");
                } catch (Exception ex) {
                    report.append("| `").append(classEntry).append("` | ")
                            .append(backend.displayName()).append(" | FAILED | N/A | N/A |\n");
                }
            }
        }

        report.append("\n## Diagnostic Summary\n\n");
        for (DecompilerBackend backend : backends) {
            report.append("### ").append(backend.displayName()).append(" ").append(backend.version()).append("\n");
            report.append("- **Engine ID**: `").append(backend.id()).append("`\n\n");
        }

        return report.toString();
    }

    // ── 2. Raw Bytecode & Disassembly Exports ────────────────────────────────────

    public ExportSummary exportRawBytecode(
            Path archive,
            Path targetDir,
            String packageOrClassFilter,
            boolean asmTextifier,
            boolean javapStyle,
            boolean controlFlowGraph,
            boolean methodBytecodeOnly
    ) throws Exception {
        Files.createDirectories(targetDir);
        int fileCount = 0;
        long totalBytes = 0;

        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (packageOrClassFilter != null && !entry.getName().contains(packageOrClassFilter)) continue;

                byte[] classBytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    classBytes = in.readAllBytes();
                }

                // 1. Raw .class export
                Path classOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("bytecode"), entry.getName());
                Files.createDirectories(classOut.getParent());
                Files.write(classOut, classBytes);
                fileCount++;
                totalBytes += classBytes.length;

                // 2. ASM Textifier disassembly
                if (asmTextifier) {
                    String asmOutput = textifyClass(classBytes);
                    Path asmOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("bytecode"), entry.getName().replace(".class", ".bytecode.asm"));
                    Files.createDirectories(asmOut.getParent());
                    byte[] asmData = asmOutput.getBytes(StandardCharsets.UTF_8);
                    Files.write(asmOut, asmData);
                    fileCount++;
                    totalBytes += asmData.length;
                }

                // 3. JVM Instruction javap -v style disassembly
                if (javapStyle) {
                    String javapOutput = disassembleJavapStyle(classBytes);
                    Path javapOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("bytecode"), entry.getName().replace(".class", ".javap.txt"));
                    Files.createDirectories(javapOut.getParent());
                    byte[] javapData = javapOutput.getBytes(StandardCharsets.UTF_8);
                    Files.write(javapOut, javapData);
                    fileCount++;
                    totalBytes += javapData.length;
                }

                // 4. Control-Flow Graph export (Mermaid & DOT)
                if (controlFlowGraph) {
                    Path graphDir = targetDir.resolve("graphs");
                    Files.createDirectories(graphDir);

                    String mermaidCfg = generateMermaidCFG(classBytes);
                    Path cfgOut = PathSanitizer.validatePathWithinTarget(graphDir, entry.getName().replace(".class", ".cfg.mmd"));
                    Files.createDirectories(cfgOut.getParent());
                    byte[] cfgData = mermaidCfg.getBytes(StandardCharsets.UTF_8);
                    Files.write(cfgOut, cfgData);
                    fileCount++;
                    totalBytes += cfgData.length;
                }

                // 5. Method Bytecode Only
                if (methodBytecodeOnly) {
                    String methodBytecode = extractMethodBytecode(classBytes);
                    Path methodOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("bytecode"), entry.getName().replace(".class", ".methods.txt"));
                    Files.createDirectories(methodOut.getParent());
                    byte[] mData = methodBytecode.getBytes(StandardCharsets.UTF_8);
                    Files.write(methodOut, mData);
                    fileCount++;
                    totalBytes += mData.length;
                }
            }
        }

        return new ExportSummary(targetDir, fileCount, totalBytes, "Exported raw bytecode and disassembly.");
    }

    // ── 3. Project & Archive Exports ─────────────────────────────────────────────

    public ExportSummary exportProjectResources(Path archive, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        int fileCount = 0;
        long totalBytes = 0;

        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                Path SanitizedOut;
                String name = entry.getName();

                if (name.startsWith("META-INF/")) {
                    SanitizedOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("resources").resolve("META-INF"), name.substring(9));
                } else if (!name.endsWith(".class")) {
                    SanitizedOut = PathSanitizer.validatePathWithinTarget(targetDir.resolve("resources"), name);
                } else {
                    continue;
                }

                Files.createDirectories(SanitizedOut.getParent());
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] data = in.readAllBytes();
                    Files.write(SanitizedOut, data);
                    fileCount++;
                    totalBytes += data.length;
                }
            }
        }

        // Export Inventory JSON
        ProjectInventory inventory = ProjectInventory.scan(archive);
        Path invPath = targetDir.resolve("reports").resolve("inventory.json");
        Files.createDirectories(invPath.getParent());
        byte[] invData = inventory.toJson().getBytes(StandardCharsets.UTF_8);
        Files.write(invPath, invData);
        fileCount++;
        totalBytes += invData.length;

        return new ExportSummary(targetDir, fileCount, totalBytes, "Exported resources & project inventory.");
    }

    public ExportSummary rebuildSanitizedJar(Path archive, Path targetJar) throws Exception {
        Files.createDirectories(targetJar.getParent());
        int fileCount = 0;
        long totalBytes = 0;
        Set<String> seenEntries = new HashSet<>();

        try (JarFile srcJar = new JarFile(archive.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(targetJar))) {

            Enumeration<JarEntry> entries = srcJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String safeName = PathSanitizer.sanitizeRelativePath(entry.getName());
                if (seenEntries.contains(safeName)) continue; // Prevent duplicates
                seenEntries.add(safeName);

                byte[] data;
                try (InputStream in = srcJar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }

                JarEntry sanitizedEntry = new JarEntry(safeName);
                sanitizedEntry.setTime(entry.getTime() > 0 ? entry.getTime() : System.currentTimeMillis());
                jos.putNextEntry(sanitizedEntry);
                jos.write(data);
                jos.closeEntry();

                fileCount++;
                totalBytes += data.length;
            }
        }

        return new ExportSummary(targetJar, fileCount, totalBytes, "Rebuilt sanitized JAR safely.");
    }

    // ── Helper Utilities ─────────────────────────────────────────────────────────

    private static List<String> getClassEntries(Path archive, String filter) throws IOException {
        List<String> list = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    if (filter == null || entry.getName().contains(filter)) {
                        list.add(entry.getName());
                    }
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    public static String textifyClass(byte[] classBytes) {
        StringWriter sw = new StringWriter();
        Printer printer = new Textifier();
        TraceClassVisitor visitor = new TraceClassVisitor(null, printer, new PrintWriter(sw));
        new ClassReader(classBytes).accept(visitor, 0);
        return sw.toString();
    }

    public static String disassembleJavapStyle(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("// javap -v style disassembly\n");
        sb.append("// Classfile ").append(node.name).append(".class\n");
        sb.append("// Major version: ").append(node.version & 0xFFFF).append("\n\n");

        sb.append("public class ").append(node.name.replace('/', '.')).append(" {\n");
        for (FieldNode field : node.fields) {
            sb.append("  ").append(field.name).append(" ").append(field.desc).append(";\n");
        }
        sb.append("\n");
        for (MethodNode method : node.methods) {
            sb.append("  ").append(method.name).append(method.desc).append(";\n");
            sb.append("    Code:\n");
            if (method.instructions != null) {
                int insnIdx = 0;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() >= 0) {
                        sb.append(String.format("      %4d: opcode %d\n", insnIdx++, insn.getOpcode()));
                    }
                }
            }
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    public static String generateMermaidCFG(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        StringBuilder sb = new StringBuilder("graph TD\n");
        for (MethodNode method : node.methods) {
            sb.append("  subgraph ").append(method.name).append("\n");
            if (method.instructions != null) {
                LabelNode currentLabel = null;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LabelNode label) {
                        currentLabel = label;
                        sb.append("    L_").append(label.hashCode()).append("[\"Label \"]\n");
                    } else if (insn instanceof JumpInsnNode jump && currentLabel != null) {
                        sb.append("    L_").append(currentLabel.hashCode())
                                .append(" --> L_").append(jump.label.hashCode()).append("\n");
                    }
                }
            }
            sb.append("  end\n");
        }
        return sb.toString();
    }

    public static String extractMethodBytecode(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        StringBuilder sb = new StringBuilder();
        for (MethodNode method : node.methods) {
            sb.append("=== METHOD: ").append(method.name).append(method.desc).append(" ===\n");
            if (method.instructions != null) {
                Printer printer = new Textifier();
                TraceClassVisitor visitor = new TraceClassVisitor(null, printer, new PrintWriter(new StringWriter()));
                method.accept(visitor);
                StringWriter sw = new StringWriter();
                printer.print(new PrintWriter(sw));
                sb.append(sw.toString()).append("\n");
            }
        }
        return sb.toString();
    }
}
