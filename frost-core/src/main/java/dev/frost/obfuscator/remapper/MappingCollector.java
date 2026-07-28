package dev.frost.obfuscator.remapper;

import dev.frost.obfuscator.crypto.PasswordFileCipher;
import dev.frost.obfuscator.transformer.rename.MethodNameAllocator;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class MappingCollector {

    private static final String AMBIGUOUS_MEMBER_NAME = "\u0000";

    private final Map<String, String> classMappings = new ConcurrentHashMap<>();
    private final Map<String, String> reverseClassMappings = new ConcurrentHashMap<>();
    private final Map<String, String> fieldMappings = new ConcurrentHashMap<>();
    private final Map<String, String> methodMappings = new ConcurrentHashMap<>();
    private final Map<String, String> fieldMappingsByUniqueName = new ConcurrentHashMap<>();
    private final Map<String, String> methodMappingsByUniqueName = new ConcurrentHashMap<>();
    private final java.util.Set<String> mappedMemberOwners = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preservedMethods = ConcurrentHashMap.newKeySet();
    private MethodNameAllocator methodNameAllocator;

    public synchronized MethodNameAllocator methodNames(String dictionaryType, Collection<ClassNode> classes) {
        if (methodNameAllocator == null || !methodNameAllocator.usesDictionary(dictionaryType)) {
            methodNameAllocator = new MethodNameAllocator(dictionaryType, classes);
        }
        return methodNameAllocator;
    }

    public void mapClass(String oldName, String newName) {
        String previous = classMappings.put(oldName, newName);
        if (previous != null) reverseClassMappings.remove(previous, oldName);
        reverseClassMappings.put(newName, oldName);
    }

    public void mapField(String owner, String oldName, String desc, String newName) {
        fieldMappings.put(fieldKey(owner, oldName, desc), newName);
        mappedMemberOwners.add(owner);
        indexUniqueMemberName(fieldMappingsByUniqueName, oldName, newName);
    }

    public void mapMethod(String owner, String oldName, String desc, String newName) {
        methodMappings.put(methodKey(owner, oldName, desc), newName);
        mappedMemberOwners.add(owner);
        indexUniqueMemberName(methodMappingsByUniqueName, oldName, newName);
    }

    /** Marks a generated method name as final without emitting an identity mapping. */
    public void preserveMethod(String owner, String name, String desc) {
        preservedMethods.add(methodKey(owner, name, desc));
    }

    public boolean isMethodPreserved(String owner, String name, String desc) {
        if (preservedMethods.contains(methodKey(owner, name, desc))) return true;

        String mappedOwner = classMappings.get(owner);
        if (mappedOwner != null && preservedMethods.contains(methodKey(mappedOwner, name, desc))) return true;

        String originalOwner = reverseClassMappings.get(owner);
        return originalOwner != null && preservedMethods.contains(methodKey(originalOwner, name, desc));
    }

    public String getMappedClass(String oldName) {
        return classMappings.getOrDefault(oldName, oldName);
    }

    public String getMappedField(String owner, String oldName, String desc) {
        String mapped = fieldMappings.get(fieldKey(owner, oldName, desc));
        if (mapped != null) return mapped;

        String mappedOwner = classMappings.get(owner);
        if (mappedOwner != null) {
            mapped = fieldMappings.get(fieldKey(mappedOwner, oldName, desc));
            if (mapped != null) return mapped;
        }

        String originalOwner = reverseClassMappings.get(owner);
        if (originalOwner != null) {
            mapped = fieldMappings.get(fieldKey(originalOwner, oldName, desc));
            if (mapped != null) return mapped;
        }

        return oldName;
    }

    public String getMappedMethod(String owner, String oldName, String desc) {
        String mapped = methodMappings.get(methodKey(owner, oldName, desc));
        if (mapped != null) return mapped;

        String mappedOwner = classMappings.get(owner);
        if (mappedOwner != null) {
            mapped = methodMappings.get(methodKey(mappedOwner, oldName, desc));
            if (mapped != null) return mapped;
        }

        String originalOwner = reverseClassMappings.get(owner);
        if (originalOwner != null) {
            mapped = methodMappings.get(methodKey(originalOwner, oldName, desc));
            if (mapped != null) return mapped;
        }

        return oldName;
    }

    public boolean hasClassMapping(String oldName) {
        return classMappings.containsKey(oldName);
    }

    public boolean hasFieldMapping(String owner, String name, String desc) {
        return fieldMappings.containsKey(fieldKey(owner, name, desc));
    }

    public boolean hasMethodMapping(String owner, String name, String desc) {
        return methodMappings.containsKey(methodKey(owner, name, desc));
    }

    public boolean hasAnyMappingForClass(String owner) {
        return classMappings.containsKey(owner) || mappedMemberOwners.contains(owner);
    }

    public Map<String, String> getClassMappings() {
        return Collections.unmodifiableMap(classMappings);
    }

    public Map<String, String> getFieldMappings() {
        return Collections.unmodifiableMap(fieldMappings);
    }

    public Map<String, String> getMethodMappings() {
        return Collections.unmodifiableMap(methodMappings);
    }

    public String getMappedMethodByName(String oldName) {
        String mapped = methodMappingsByUniqueName.get(oldName);
        return mapped == null || AMBIGUOUS_MEMBER_NAME.equals(mapped) ? oldName : mapped;
    }

    public String getMappedFieldByName(String oldName) {
        String mapped = fieldMappingsByUniqueName.get(oldName);
        return mapped == null || AMBIGUOUS_MEMBER_NAME.equals(mapped) ? oldName : mapped;
    }

    public void exportMappings(Path outputPath) throws IOException {
        Path destination = normalizedOutput(outputPath);
        Files.writeString(destination, renderMappings(), StandardCharsets.UTF_8);
        Logger.info("Mapping file exported to {}", destination);
    }

    public void exportEncryptedMappings(Path outputPath, char[] password) throws IOException {
        Path destination = normalizedOutput(outputPath);
        PasswordFileCipher.encrypt(renderMappings().getBytes(StandardCharsets.UTF_8), destination, password);
        Logger.info("AES-256 encrypted mapping file exported to {}", destination);
    }

    public String renderMappings() {
        StringBuilder output = new StringBuilder(256 + totalMappings() * 48);
        output.append("# Frostfuscator Mapping File\n");
        output.append("# Generated at ").append(java.time.LocalDateTime.now()).append("\n\n");

        output.append("# Class Mappings\n");
        new TreeMap<>(classMappings).forEach((oldName, newName) -> output
                .append(oldName.replace('/', '.')).append(" -> ")
                .append(newName.replace('/', '.')).append('\n'));
        output.append('\n');

        output.append("# Field Mappings\n");
        new TreeMap<>(fieldMappings).forEach((key, value) -> output
                .append("    ").append(key).append(" -> ").append(value).append('\n'));
        output.append('\n');

        output.append("# Method Mappings\n");
        new TreeMap<>(methodMappings).forEach((key, value) -> output
                .append("    ").append(key).append(" -> ").append(value).append('\n'));
        return output.toString();
    }

    public int totalMappings() {
        return classMappings.size() + fieldMappings.size() + methodMappings.size();
    }

    private static String fieldKey(String owner, String name, String desc) {
        return owner + "." + name + ":" + desc;
    }

    private static String methodKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    private static void indexUniqueMemberName(Map<String, String> index, String oldName, String newName) {
        index.merge(oldName, newName,
                (existing, candidate) -> existing.equals(candidate) ? existing : AMBIGUOUS_MEMBER_NAME);
    }

    private static Path normalizedOutput(Path outputPath) throws IOException {
        if (outputPath == null) throw new IllegalArgumentException("Mapping output path is required");
        Path destination = outputPath.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Mapping output path must have a parent directory");
        Files.createDirectories(parent);
        return destination;
    }
}
