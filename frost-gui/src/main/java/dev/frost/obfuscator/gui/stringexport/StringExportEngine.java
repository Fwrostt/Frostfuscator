package dev.frost.obfuscator.gui.stringexport;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Deep archive string extraction engine and multi-format exporter (.txt, .csv, .json, .jsonl).
 */
public final class StringExportEngine {

    /**
     * Extracts all string records from the target archive.
     */
    public List<StringRecord> extractAllStrings(Path archive, boolean includeNames) throws Exception {
        List<StringRecord> records = new ArrayList<>();
        Map<String, Integer> frequencies = new HashMap<>();

        try (JarFile jar = new JarFile(archive.toFile())) {
            // 1. Extract from Manifest
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                manifest.getMainAttributes().forEach((k, v) ->
                        addRecord(records, frequencies, String.valueOf(v), "MANIFEST", "MainAttributes", "", -1, "Manifest"));
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();

                // 2. Extract from Class Bytecode
                if (name.endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        byte[] classBytes = in.readAllBytes();
                        extractFromClassBytecode(classBytes, records, frequencies, includeNames);
                    }
                }
                // 3. Extract from Services
                else if (name.startsWith("META-INF/services/")) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty() && !line.startsWith("#")) {
                                addRecord(records, frequencies, line, "RESOURCE", name, "", -1, "ServiceProvider");
                            }
                        }
                    }
                }
                // 4. Extract from Resources (.properties, .json, .xml, .yml, .yaml, .conf)
                else if (name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".xml")
                        || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".conf")) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty()) {
                                addRecord(records, frequencies, line, "RESOURCE", name, "", -1, "ResourceFile");
                            }
                        }
                    }
                }
            }
        }

        // Calculate final frequencies and return immutable list
        List<StringRecord> finalizedRecords = new ArrayList<>();
        for (StringRecord record : records) {
            int freq = frequencies.getOrDefault(record.value(), 1);
            finalizedRecords.add(new StringRecord(
                    record.value(), record.decodedValue(), record.className(),
                    record.methodName(), record.methodDescriptor(), record.instructionIndex(),
                    record.sourceType(), record.category(), record.entropy(), freq, record.likelyEncoded()
            ));
        }

        return Collections.unmodifiableList(finalizedRecords);
    }

    private void extractFromClassBytecode(byte[] classBytes, List<StringRecord> records, Map<String, Integer> frequencies, boolean includeNames) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        String className = node.name.replace('/', '.');

        if (includeNames) {
            addRecord(records, frequencies, className, className, "<class>", "", -1, "ClassName");
        }

        // Class Annotations
        extractAnnotations(node.visibleAnnotations, className, "<class>", "", records, frequencies);
        extractAnnotations(node.invisibleAnnotations, className, "<class>", "", records, frequencies);

        // Inner classes
        if (node.innerClasses != null) {
            for (InnerClassNode icn : node.innerClasses) {
                if (icn.name != null) addRecord(records, frequencies, icn.name, className, "<class>", "", -1, "InnerClass");
                if (icn.innerName != null) addRecord(records, frequencies, icn.innerName, className, "<class>", "", -1, "InnerClassName");
                if (icn.outerName != null) addRecord(records, frequencies, icn.outerName, className, "<class>", "", -1, "OuterClassName");
            }
        }

        // Module Info
        if (node.module != null) {
            if (node.module.packages != null) for (String pkg : node.module.packages) addRecord(records, frequencies, pkg, className, "<module>", "", -1, "ModulePackage");
            if (node.module.requires != null) for (ModuleRequireNode req : node.module.requires) addRecord(records, frequencies, req.module, className, "<module>", "", -1, "ModuleRequire");
            if (node.module.exports != null) for (ModuleExportNode exp : node.module.exports) addRecord(records, frequencies, exp.packaze, className, "<module>", "", -1, "ModuleExport");
            if (node.module.opens != null) for (ModuleOpenNode op : node.module.opens) addRecord(records, frequencies, op.packaze, className, "<module>", "", -1, "ModuleOpen");
        }

        // Fields
        for (FieldNode field : node.fields) {
            if (includeNames) {
                addRecord(records, frequencies, field.name, className, field.name, field.desc, -1, "FieldName");
            }
            if (field.value instanceof String strVal) {
                addRecord(records, frequencies, strVal, className, field.name, field.desc, -1, "FieldConstant");
            }
            extractAnnotations(field.visibleAnnotations, className, field.name, field.desc, records, frequencies);
            extractAnnotations(field.invisibleAnnotations, className, field.name, field.desc, records, frequencies);
        }

        // Methods
        for (MethodNode method : node.methods) {
            String methodName = method.name;
            String methodDesc = method.desc;

            if (includeNames) {
                addRecord(records, frequencies, methodName, className, methodName, methodDesc, -1, "MethodName");
            }

            extractAnnotations(method.visibleAnnotations, className, methodName, methodDesc, records, frequencies);
            extractAnnotations(method.invisibleAnnotations, className, methodName, methodDesc, records, frequencies);
            
            if (method.visibleParameterAnnotations != null) {
                for (List<AnnotationNode> anns : method.visibleParameterAnnotations) {
                    extractAnnotations(anns, className, methodName, methodDesc, records, frequencies);
                }
            }
            if (method.invisibleParameterAnnotations != null) {
                for (List<AnnotationNode> anns : method.invisibleParameterAnnotations) {
                    extractAnnotations(anns, className, methodName, methodDesc, records, frequencies);
                }
            }
            
            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                    if (tcb.type != null) {
                        addRecord(records, frequencies, tcb.type, className, methodName, methodDesc, -1, "TryCatchBlock");
                    }
                }
            }
            
            if (method.localVariables != null) {
                for (LocalVariableNode lvn : method.localVariables) {
                    addRecord(records, frequencies, lvn.name, className, methodName, methodDesc, -1, "LocalVariable");
                    if (lvn.desc != null) addRecord(records, frequencies, lvn.desc, className, methodName, methodDesc, -1, "LocalVariableDesc");
                    if (lvn.signature != null) addRecord(records, frequencies, lvn.signature, className, methodName, methodDesc, -1, "LocalVariableSig");
                }
            }

            if (method.instructions != null) {
                int insnIdx = 0;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext(), insnIdx++) {
                    // LDC Constants
                    if (insn instanceof LdcInsnNode ldc) {
                        if (ldc.cst instanceof String strVal) {
                            addRecord(records, frequencies, strVal, className, methodName, methodDesc, insnIdx, "LDC");
                        } else if (ldc.cst instanceof ConstantDynamic condy) {
                            extractCondyStrings(condy, className, methodName, methodDesc, insnIdx, records, frequencies);
                        }
                    }
                    // InvokeDynamic & StringConcatFactory Recipes
                    else if (insn instanceof InvokeDynamicInsnNode indy) {
                        extractIndyStrings(indy, className, methodName, methodDesc, insnIdx, records, frequencies);
                    }
                    // TypeInsnNode
                    else if (insn instanceof TypeInsnNode tin) {
                        if (tin.desc != null) {
                            addRecord(records, frequencies, tin.desc, className, methodName, methodDesc, insnIdx, "TypeInsn");
                        }
                    }
                    // FrameNode
                    else if (insn instanceof FrameNode fn) {
                        if (fn.local != null) {
                            for (Object o : fn.local) {
                                if (o instanceof String s) addRecord(records, frequencies, s, className, methodName, methodDesc, insnIdx, "FrameLocal");
                            }
                        }
                        if (fn.stack != null) {
                            for (Object o : fn.stack) {
                                if (o instanceof String s) addRecord(records, frequencies, s, className, methodName, methodDesc, insnIdx, "FrameStack");
                            }
                        }
                    }
                    // TableSwitchInsnNode
                    else if (insn instanceof TableSwitchInsnNode) {
                    }
                    else if (insn instanceof LookupSwitchInsnNode) {
                    }
                }
            }
        }
    }

    private void extractIndyStrings(InvokeDynamicInsnNode indy, String className, String methodName, String methodDesc, int insnIdx, List<StringRecord> records, Map<String, Integer> frequencies) {
        if (indy.bsmArgs != null) {
            for (Object arg : indy.bsmArgs) {
                if (arg instanceof String strVal) {
                    addRecord(records, frequencies, strVal, className, methodName, methodDesc, insnIdx, "InvokeDynamicRecipe");
                } else if (arg instanceof Handle h) {
                    addRecord(records, frequencies, h.getOwner(), className, methodName, methodDesc, insnIdx, "HandleOwner");
                    addRecord(records, frequencies, h.getName(), className, methodName, methodDesc, insnIdx, "HandleName");
                    addRecord(records, frequencies, h.getDesc(), className, methodName, methodDesc, insnIdx, "HandleDesc");
                }
            }
        }
    }

    private void extractCondyStrings(ConstantDynamic condy, String className, String methodName, String methodDesc, int insnIdx, List<StringRecord> records, Map<String, Integer> frequencies) {
        if (condy.getName() != null) {
            addRecord(records, frequencies, condy.getName(), className, methodName, methodDesc, insnIdx, "ConstantDynamicName");
        }
        for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
            Object arg = condy.getBootstrapMethodArgument(i);
            if (arg instanceof String strVal) {
                addRecord(records, frequencies, strVal, className, methodName, methodDesc, insnIdx, "ConstantDynamicArg");
            } else if (arg instanceof Handle h) {
                addRecord(records, frequencies, h.getOwner(), className, methodName, methodDesc, insnIdx, "HandleOwner");
                addRecord(records, frequencies, h.getName(), className, methodName, methodDesc, insnIdx, "HandleName");
                addRecord(records, frequencies, h.getDesc(), className, methodName, methodDesc, insnIdx, "HandleDesc");
            }
        }
    }

    private void extractAnnotations(List<AnnotationNode> annotations, String className, String methodName, String methodDesc, List<StringRecord> records, Map<String, Integer> frequencies) {
        if (annotations == null) return;
        for (AnnotationNode ann : annotations) {
            if (ann.values != null) {
                for (Object val : ann.values) {
                    if (val instanceof String strVal) {
                        addRecord(records, frequencies, strVal, className, methodName, methodDesc, -1, "AnnotationValue");
                    }
                }
            }
        }
    }

    private void addRecord(List<StringRecord> records, Map<String, Integer> frequencies, String value, String className, String methodName, String methodDesc, int insnIdx, String sourceType) {
        if (value == null || value.isEmpty()) return;
        frequencies.put(value, frequencies.getOrDefault(value, 0) + 1);

        double entropy = StringDecoder.calculateEntropy(value);
        boolean isBase64 = StringDecoder.isBase64(value);
        boolean isHex = StringDecoder.isHex(value);
        String decoded = StringDecoder.decode(value);
        String category = StringCategory.categorize(value, entropy, isBase64, isHex).name();
        boolean likelyEncoded = StringDecoder.isLikelyEncoded(value, entropy);

        records.add(new StringRecord(
                value, decoded, className, methodName, methodDesc, insnIdx, sourceType, category, entropy, 1, likelyEncoded
        ));
    }

    // ── Export Formats (.txt, .csv, .json, .jsonl) ────────────────────────────────

    public void exportTxt(List<StringRecord> records, Path outputFile) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Frostfuscator String Export\n");
        sb.append("# Archive: ").append(outputFile.getFileName().toString().replace(".txt", ".jar")).append("\n");
        sb.append("# Total Strings: ").append(records.size()).append("\n");

        Map<String, Integer> categoryCounts = new HashMap<>();
        double sumEntropy = 0;
        double minEntropy = Double.MAX_VALUE;
        double maxEntropy = 0;
        int likelyEncodedCount = 0;

        for (StringRecord r : records) {
            categoryCounts.put(r.category(), categoryCounts.getOrDefault(r.category(), 0) + 1);
            double e = r.entropy();
            sumEntropy += e;
            if (e < minEntropy) minEntropy = e;
            if (e > maxEntropy) maxEntropy = e;
            if (r.likelyEncoded()) likelyEncodedCount++;
        }

        if (records.isEmpty()) {
            minEntropy = 0;
        }
        double avgEntropy = records.isEmpty() ? 0 : sumEntropy / records.size();

        double variance = 0;
        for (StringRecord r : records) {
            variance += Math.pow(r.entropy() - avgEntropy, 2);
        }
        double stddev = records.isEmpty() ? 0 : Math.sqrt(variance / records.size());

        sb.append("# Categories: ");
        List<String> cats = new ArrayList<>();
        for (Map.Entry<String, Integer> e : categoryCounts.entrySet()) {
            cats.add(e.getKey() + "=" + e.getValue());
        }
        sb.append(String.join(", ", cats)).append("\n");

        sb.append(String.format(Locale.US, "# Entropy Distribution: min=%.2f / avg=%.2f / max=%.2f / stddev=%.2f\n", minEntropy, avgEntropy, maxEntropy, stddev));

        double perc = records.isEmpty() ? 0 : (likelyEncodedCount * 100.0 / records.size());
        sb.append(String.format(Locale.US, "# Likely Encoded: %d (%.1f%%)\n\n", likelyEncodedCount, perc));

        sb.append("=== STRINGS ===\n");
        for (StringRecord r : records) {
            sb.append(String.format("[%s] %s\n", r.category(), r.value()));
            sb.append(String.format("  Class: %s\n", r.className()));
            sb.append(String.format("  Method: %s %s\n", r.methodName(), r.methodDescriptor()));
            sb.append(String.format("  Source: %s @ instruction #%d\n", r.sourceType(), r.instructionIndex()));
            sb.append(String.format(Locale.US, "  Entropy: H=%.2f (freq=%d)\n", r.entropy(), r.frequency()));
            if (r.decodedValue() != null && !r.decodedValue().equals(r.value())) {
                sb.append(String.format("  Decoded: %s\n", r.decodedValue()));
            }
        }

        Files.write(outputFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void exportCsv(List<StringRecord> records, Path outputFile) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Value,DecodedValue,ClassName,MethodName,MethodDescriptor,InstructionIndex,SourceType,Category,Entropy,Frequency,LikelyEncoded\n");
        for (StringRecord r : records) {
            sb.append("\"").append(csvEscape(r.value())).append("\",")
                    .append("\"").append(csvEscape(r.decodedValue())).append("\",")
                    .append("\"").append(csvEscape(r.className())).append("\",")
                    .append("\"").append(csvEscape(r.methodName())).append("\",")
                    .append("\"").append(csvEscape(r.methodDescriptor())).append("\",")
                    .append(r.instructionIndex()).append(",")
                    .append("\"").append(r.sourceType()).append("\",")
                    .append("\"").append(r.category()).append("\",")
                    .append(r.entropy()).append(",")
                    .append(r.frequency()).append(",")
                    .append(r.likelyEncoded()).append("\n");
        }
        Files.write(outputFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void exportJson(List<StringRecord> records, Path outputFile) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < records.size(); i++) {
            sb.append(toJsonRecord(records.get(i), "  "));
            if (i < records.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.write(outputFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void exportJsonl(List<StringRecord> records, Path outputFile) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (StringRecord r : records) {
            sb.append(toJsonRecord(r, "")).append("\n");
        }
        Files.write(outputFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String toJsonRecord(StringRecord r, String indent) {
        return indent + "{"
                + "\"value\":\"" + jsonEscape(r.value()) + "\","
                + "\"decodedValue\":\"" + jsonEscape(r.decodedValue()) + "\","
                + "\"className\":\"" + jsonEscape(r.className()) + "\","
                + "\"methodName\":\"" + jsonEscape(r.methodName()) + "\","
                + "\"methodDescriptor\":\"" + jsonEscape(r.methodDescriptor()) + "\","
                + "\"instructionIndex\":" + r.instructionIndex() + ","
                + "\"sourceType\":\"" + jsonEscape(r.sourceType()) + "\","
                + "\"category\":\"" + jsonEscape(r.category()) + "\","
                + "\"entropy\":" + r.entropy() + ","
                + "\"frequency\":" + r.frequency() + ","
                + "\"likelyEncoded\":" + r.likelyEncoded()
                + "}";
    }

    private static String csvEscape(String input) {
        if (input == null) return "";
        return input.replace("\"", "\"\"");
    }

    private static String jsonEscape(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
