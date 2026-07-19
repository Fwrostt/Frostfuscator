package dev.frost.obfuscator.remapper;

import dev.frost.obfuscator.util.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MappingCollector {

    private final Map<String, String> classMappings = new ConcurrentHashMap<>();
    private final Map<String, String> fieldMappings = new ConcurrentHashMap<>();
    private final Map<String, String> methodMappings = new ConcurrentHashMap<>();

    public void mapClass(String oldName, String newName) {
        classMappings.put(oldName, newName);
    }

    public void mapField(String owner, String oldName, String desc, String newName) {
        fieldMappings.put(fieldKey(owner, oldName, desc), newName);
    }

    public void mapMethod(String owner, String oldName, String desc, String newName) {
        methodMappings.put(methodKey(owner, oldName, desc), newName);
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
        if (classMappings.containsKey(owner)) {
            return true;
        }
        String prefix = owner + ".";
        return fieldMappings.keySet().stream().anyMatch(k -> k.startsWith(prefix))
                || methodMappings.keySet().stream().anyMatch(k -> k.startsWith(prefix));
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

    public void exportMappings(Path outputPath) {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("# Frostfuscator Mapping File");
            writer.newLine();
            writer.write("# Generated at " + java.time.LocalDateTime.now());
            writer.newLine();
            writer.newLine();

            writer.write("# Class Mappings");
            writer.newLine();
            for (Map.Entry<String, String> entry : classMappings.entrySet()) {
                writer.write(entry.getKey().replace('/', '.') + " -> " + entry.getValue().replace('/', '.'));
                writer.newLine();
            }
            writer.newLine();

            writer.write("# Field Mappings");
            writer.newLine();
            for (Map.Entry<String, String> entry : fieldMappings.entrySet()) {
                writer.write("    " + entry.getKey() + " -> " + entry.getValue());
                writer.newLine();
            }
            writer.newLine();

            writer.write("# Method Mappings");
            writer.newLine();
            for (Map.Entry<String, String> entry : methodMappings.entrySet()) {
                writer.write("    " + entry.getKey() + " -> " + entry.getValue());
                writer.newLine();
            }

            Logger.info("Mapping file exported to {}", outputPath);
        } catch (IOException e) {
            Logger.error("Failed to export mapping file", e);
        }
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
}
