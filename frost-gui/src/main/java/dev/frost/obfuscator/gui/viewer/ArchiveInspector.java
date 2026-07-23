package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class ArchiveInspector {
    private static final int RESOURCE_PREVIEW_LIMIT = 2 * 1024 * 1024;

    public ArchiveSnapshot open(Path archive) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Archive does not exist: " + normalized);
        }
        List<ArchiveEntry> entries = new ArrayList<>();
        Map<String, String> manifest = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(normalized.toFile())) {
            Manifest value = jar.getManifest();
            if (value != null) {
                value.getMainAttributes().forEach((key, item) ->
                        manifest.put(String.valueOf(key), String.valueOf(item)));
            }
            Enumeration<JarEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                EntryKind kind = entry.getName().endsWith(".class") ? EntryKind.CLASS : EntryKind.RESOURCE;
                int major = 0;
                if (kind == EntryKind.CLASS) {
                    try (var stream = jar.getInputStream(entry)) {
                        byte[] header = stream.readNBytes(8);
                        major = header.length < 8 ? 0 : (header[6] & 0xff) << 8 | header[7] & 0xff;
                    }
                }
                entries.add(new ArchiveEntry(entry.getName(), kind, entry.getSize(), major));
            }
        }
        entries.sort(Comparator.comparing(ArchiveEntry::name));
        return new ArchiveSnapshot(normalized, Files.size(normalized),
                Files.getLastModifiedTime(normalized).toMillis(), List.copyOf(entries),
                Map.copyOf(manifest));
    }

    public ClassInspection inspectClass(Path archive, String classEntry) throws IOException {
        byte[] bytes = readBytes(archive, classEntry);
        return inspectClassBytes(bytes, classEntry);
    }

    public ClassInspection inspectClassBytes(byte[] bytes, String classEntry) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        List<MemberInfo> fields = new ArrayList<>();
        List<MemberInfo> methods = new ArrayList<>();
        List<StringOccurrence> strings = new ArrayList<>();
        List<CallEdge> calls = new ArrayList<>();
        List<CapabilityFinding> findings = new ArrayList<>();
        for (FieldNode field : node.fields) {
            fields.add(new MemberInfo(field.name, field.desc, access(field.access), 0, false));
            if (field.value instanceof String value) {
                strings.add(new StringOccurrence(value, displayName(node.name) + "." + field.name, "Field"));
            }
        }
        for (MethodNode method : node.methods) {
            int instructionCount = 0;
            int sequence = 0;
            String methodName = method.name + method.desc;
            if (method.instructions != null) {
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() >= 0) instructionCount++;
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                        strings.add(new StringOccurrence(value,
                                displayName(node.name) + "." + methodName, "LDC"));
                    } else if (instruction instanceof MethodInsnNode call) {
                        sequence++;
                        String target = displayName(call.owner) + "." + call.name + call.desc;
                        calls.add(new CallEdge(displayName(node.name), methodName, sequence,
                                invocationKind(call.getOpcode()), target));
                        CapabilityFinding finding = findingFor(node.name, methodName, call);
                        if (finding != null) findings.add(finding);
                    } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        sequence++;
                        calls.add(new CallEdge(displayName(node.name), methodName, sequence,
                                "invokedynamic", dynamic.name + dynamic.desc));
                    }
                }
            }
            methods.add(new MemberInfo(method.name, method.desc, access(method.access),
                    instructionCount, isMain(method)));
        }
        findings.addAll(structuralFindings(node));
        return new ClassInspection(classEntry, displayName(node.name), node.version,
                kind(node.access), access(node.access), nullable(node.superName, "java/lang/Object"),
                node.interfaces.stream().map(ArchiveInspector::displayName).toList(),
                node.sourceFile == null ? "" : node.sourceFile,
                node.signature == null ? "" : node.signature,
                annotations(node.visibleAnnotations, node.invisibleAnnotations),
                List.copyOf(fields), List.copyOf(methods), List.copyOf(strings),
                List.copyOf(calls), List.copyOf(findings), bytecode(bytes),
                bytes, extractConstantPool(bytes));
    }

    public static List<String> extractConstantPool(byte[] bytes) {
        List<String> entries = new ArrayList<>();
        try {
            ClassReader reader = new ClassReader(bytes);
            int count = reader.getItemCount();
            char[] buf = new char[Math.max(256, reader.getMaxStringLength())];
            for (int i = 1; i < count; i++) {
                try {
                    Object item = reader.readConst(i, buf);
                    if (item != null) {
                        entries.add(String.format("#%-4d = %-18s %s", i, item.getClass().getSimpleName(), item));
                    } else {
                        entries.add(String.format("#%-4d = Constant Entry", i));
                    }
                } catch (Exception ignored) {
                    entries.add(String.format("#%-4d = Entry", i));
                }
            }
        } catch (Exception exception) {
            entries.add("Error parsing constant pool: " + exception.getMessage());
        }
        return entries;
    }

    public ArchiveScan scan(Path archive) throws IOException {
        List<ClassInspection> classes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ArchiveSnapshot snapshot = open(archive);
        for (ArchiveEntry entry : snapshot.entries()) {
            if (entry.kind() != EntryKind.CLASS) continue;
            try {
                classes.add(inspectClass(archive, entry.name()));
            } catch (RuntimeException | IOException exception) {
                errors.add(entry.name() + ": " + safeMessage(exception));
            }
        }
        List<StringOccurrence> strings = classes.stream().flatMap(item -> item.strings().stream()).toList();
        List<MainMethod> mains = classes.stream().flatMap(item -> item.methods().stream()
                        .filter(MemberInfo::mainMethod)
                        .map(method -> new MainMethod(item.className(), method.name(), method.descriptor())))
                .toList();
        List<CallEdge> calls = classes.stream().flatMap(item -> item.calls().stream()).toList();
        List<CapabilityFinding> findings = classes.stream().flatMap(item -> item.findings().stream())
                .sorted(Comparator.comparingInt((CapabilityFinding value) -> severityRank(value.severity()))
                        .thenComparing(CapabilityFinding::className)
                        .thenComparing(CapabilityFinding::method))
                .toList();
        return new ArchiveScan(List.copyOf(classes), strings, mains, calls, findings, List.copyOf(errors));
    }

    public String resourcePreview(Path archive, String entryName) throws IOException {
        byte[] bytes = readBytes(archive, entryName);
        if (bytes.length > RESOURCE_PREVIEW_LIMIT) {
            return "Preview unavailable: resource is " + formatBytes(bytes.length)
                    + ". The viewer limits inline previews to " + formatBytes(RESOURCE_PREVIEW_LIMIT) + ".";
        }
        if (looksTextual(bytes)) return new String(bytes, StandardCharsets.UTF_8);
        return hexDump(bytes, 64 * 1024);
    }

    public String manifestText(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return "This archive has no META-INF/MANIFEST.MF.";
            StringBuilder text = new StringBuilder();
            appendAttributes(text, "Main attributes", manifest.getMainAttributes());
            manifest.getEntries().forEach((name, attributes) ->
                    appendAttributes(text, "\n[" + name + "]", attributes));
            return text.toString();
        }
    }

    private static byte[] readBytes(Path archive, String entryName) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IOException("Archive entry not found: " + entryName);
            try (var stream = jar.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static String bytecode(byte[] bytes) {
        StringWriter output = new StringWriter();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new TraceClassVisitor(new PrintWriter(output)), 0);
        return output.toString();
    }

    @SafeVarargs
    private static List<String> annotations(List<AnnotationNode>... groups) {
        List<String> values = new ArrayList<>();
        for (List<AnnotationNode> group : groups) {
            if (group == null) continue;
            group.stream().map(node -> Type.getType(node.desc).getClassName()).forEach(values::add);
        }
        return List.copyOf(values);
    }

    private static List<CapabilityFinding> structuralFindings(ClassNode node) {
        List<CapabilityFinding> findings = new ArrayList<>();
        if ((node.access & Opcodes.ACC_NATIVE) != 0 || node.methods.stream()
                .anyMatch(method -> (method.access & Opcodes.ACC_NATIVE) != 0)) {
            findings.add(new CapabilityFinding("Medium", "Native code", "Native method declared",
                    "Native bindings can execute code outside the JVM sandbox.",
                    displayName(node.name), ""));
        }
        return findings;
    }

    private static CapabilityFinding findingFor(String owner, String method, MethodInsnNode call) {
        String target = call.owner + "." + call.name;
        String className = displayName(owner);
        if (target.equals("java/lang/Runtime.exec") || target.equals("java/lang/ProcessBuilder.start")) {
            return finding("High", "Process execution", "External process execution",
                    target, className, method);
        }
        if (target.equals("java/lang/System.load") || target.equals("java/lang/System.loadLibrary")) {
            return finding("High", "Native code", "Native library loading", target, className, method);
        }
        if (call.owner.startsWith("javax/naming/")) {
            return finding("High", "JNDI", "Directory or naming lookup", target, className, method);
        }
        if (call.owner.equals("sun/misc/Unsafe") || call.owner.equals("jdk/internal/misc/Unsafe")) {
            return finding("High", "Unsafe", "Unsafe memory access", target, className, method);
        }
        if (call.owner.startsWith("java/net/") || call.owner.startsWith("java/net/http/")
                || call.owner.startsWith("javax/net/")) {
            return finding("Medium", "Network", "Network capability", target, className, method);
        }
        if (call.owner.startsWith("java/lang/reflect/") || call.owner.startsWith("java/lang/invoke/")
                || call.owner.equals("java/lang/Class") && Set.of("forName", "getMethod",
                "getDeclaredMethod", "getField", "getDeclaredField").contains(call.name)) {
            return finding("Medium", "Reflection", "Dynamic code access", target, className, method);
        }
        if (call.owner.startsWith("java/io/ObjectInput")
                || call.owner.startsWith("java/beans/XMLDecoder")) {
            return finding("Medium", "Deserialization", "Object deserialization", target, className, method);
        }
        if (call.owner.startsWith("java/nio/file/") && (call.name.contains("delete")
                || call.name.contains("write") || call.name.contains("move"))) {
            return finding("Low", "Filesystem", "Filesystem mutation", target, className, method);
        }
        if (call.owner.startsWith("java/security/") || call.owner.startsWith("javax/crypto/")) {
            return finding("Info", "Cryptography", "Cryptographic API use", target, className, method);
        }
        return null;
    }

    private static CapabilityFinding finding(String severity, String category, String title,
                                               String evidence, String owner, String method) {
        return new CapabilityFinding(severity, category, title,
                evidence.replace('/', '.'), owner, method);
    }

    private static boolean isMain(MethodNode method) {
        return method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V")
                && (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
    }

    private static String kind(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) return "Annotation";
        if ((access & Opcodes.ACC_ENUM) != 0) return "Enum";
        if ((access & Opcodes.ACC_RECORD) != 0) return "Record";
        if ((access & Opcodes.ACC_INTERFACE) != 0) return "Interface";
        if ((access & Opcodes.ACC_ABSTRACT) != 0) return "Abstract class";
        return "Class";
    }

    private static String access(int access) {
        List<String> values = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0) values.add("public");
        else if ((access & Opcodes.ACC_PROTECTED) != 0) values.add("protected");
        else if ((access & Opcodes.ACC_PRIVATE) != 0) values.add("private");
        if ((access & Opcodes.ACC_STATIC) != 0) values.add("static");
        if ((access & Opcodes.ACC_FINAL) != 0) values.add("final");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) values.add("abstract");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) values.add("synchronized");
        if ((access & Opcodes.ACC_NATIVE) != 0) values.add("native");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) values.add("synthetic");
        if ((access & Opcodes.ACC_BRIDGE) != 0) values.add("bridge");
        return String.join(" ", values);
    }

    private static String invocationKind(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKESTATIC -> "static";
            case Opcodes.INVOKEINTERFACE -> "interface";
            case Opcodes.INVOKESPECIAL -> "special";
            default -> "virtual";
        };
    }

    private static String displayName(String internalName) {
        return internalName == null ? "" : internalName.replace('/', '.');
    }

    private static String nullable(String value, String fallback) {
        return displayName(value == null ? fallback : value);
    }

    private static boolean looksTextual(byte[] bytes) {
        if (bytes.length == 0) return true;
        int suspicious = 0;
        int sampled = Math.min(bytes.length, 8192);
        for (int i = 0; i < sampled; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) return false;
            if (value < 9 || value > 13 && value < 32) suspicious++;
        }
        return suspicious < Math.max(2, sampled / 50);
    }

    private static String hexDump(byte[] bytes, int limit) {
        int length = Math.min(bytes.length, limit);
        StringBuilder output = new StringBuilder(length * 4);
        for (int offset = 0; offset < length; offset += 16) {
            output.append(String.format(Locale.ROOT, "%08X  ", offset));
            for (int index = 0; index < 16; index++) {
                if (offset + index < length) {
                    output.append(String.format(Locale.ROOT, "%02X ", bytes[offset + index] & 0xff));
                } else {
                    output.append("   ");
                }
            }
            output.append(" |");
            for (int index = 0; index < 16 && offset + index < length; index++) {
                int value = bytes[offset + index] & 0xff;
                output.append(value >= 32 && value < 127 ? (char) value : '.');
            }
            output.append("|\n");
        }
        if (bytes.length > length) output.append("\n… ").append(bytes.length - length).append(" more bytes");
        return output.toString();
    }

    private static void appendAttributes(StringBuilder output, String title, Attributes attributes) {
        output.append(title).append('\n');
        attributes.forEach((key, value) ->
                output.append(String.valueOf(key)).append(": ").append(value).append('\n'));
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "High" -> 0;
            case "Medium" -> 1;
            case "Low" -> 2;
            default -> 3;
        };
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024d;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024d);
    }

    public enum EntryKind { CLASS, RESOURCE }

    public record ArchiveEntry(String name, EntryKind kind, long size, int classMajor) {}

    public record ArchiveSnapshot(Path path, long size, long modifiedMillis, List<ArchiveEntry> entries,
                                  Map<String, String> manifest) {
        public long classCount() {
            return entries.stream().filter(entry -> entry.kind == EntryKind.CLASS).count();
        }

        public long resourceCount() {
            return entries.size() - classCount();
        }
    }

    public record MemberInfo(String name, String descriptor, String access,
                             int instructions, boolean mainMethod) {}

    public record StringOccurrence(String value, String location, String source) {}

    public record CallEdge(String owner, String method, int sequence, String invocation, String target) {}

    public record CapabilityFinding(String severity, String category, String title, String evidence,
                                    String className, String method) {}

    public record MainMethod(String className, String name, String descriptor) {}

    public record ClassInspection(
            String entryName,
            String className,
            int classMajor,
            String kind,
            String access,
            String superClass,
            List<String> interfaces,
            String sourceFile,
            String signature,
            List<String> annotations,
            List<MemberInfo> fields,
            List<MemberInfo> methods,
            List<StringOccurrence> strings,
            List<CallEdge> calls,
            List<CapabilityFinding> findings,
            String bytecode,
            byte[] rawBytes,
            List<String> constantPool
    ) {}

    public record ArchiveScan(
            List<ClassInspection> classes,
            List<StringOccurrence> strings,
            List<MainMethod> mainMethods,
            List<CallEdge> calls,
            List<CapabilityFinding> findings,
            List<String> errors
    ) {}
}
