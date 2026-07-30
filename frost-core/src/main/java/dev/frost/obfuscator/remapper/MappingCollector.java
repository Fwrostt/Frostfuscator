package dev.frost.obfuscator.remapper;

import dev.frost.obfuscator.crypto.PasswordFileCipher;
import dev.frost.obfuscator.transformer.rename.MethodNameAllocator;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    private final java.util.Set<String> preservedClasses = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preservedFields = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preservedMethods = ConcurrentHashMap.newKeySet();
    private MethodNameAllocator methodNameAllocator;

    public synchronized MethodNameAllocator methodNames(String dictionaryType, Collection<ClassNode> classes) {
        if (methodNameAllocator == null || !methodNameAllocator.usesDictionary(dictionaryType)) {
            methodNameAllocator = new MethodNameAllocator(dictionaryType, classes);
        }
        return methodNameAllocator;
    }

    public void mapClass(String oldName, String newName) {
        if (isClassPreserved(oldName)) return;
        String previous = classMappings.put(oldName, newName);
        if (previous != null) reverseClassMappings.remove(previous, oldName);
        reverseClassMappings.put(newName, oldName);
    }

    public void mapField(String owner, String oldName, String desc, String newName) {
        if (isFieldPreserved(owner, oldName, desc)) return;
        fieldMappings.put(fieldKey(owner, oldName, desc), newName);
        mappedMemberOwners.add(owner);
        indexUniqueMemberName(fieldMappingsByUniqueName, oldName, newName);
    }

    /**
     * Records the single logical rename shared by a record component, its backing field,
     * and its generated accessor method.
     */
    public void mapRecordComponent(String owner, String oldName, String desc, String newName) {
        if (isFieldPreserved(owner, oldName, desc)) return;
        mapField(owner, oldName, desc, newName);
        mapMethod(owner, oldName, "()" + desc, newName);
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

    public void preserveClass(String internalName) {
        preservedClasses.add(internalName);
    }

    public boolean isClassPreserved(String internalName) {
        if (preservedClasses.contains(internalName)) return true;
        String mapped = classMappings.get(internalName);
        if (mapped != null && preservedClasses.contains(mapped)) return true;
        String original = reverseClassMappings.get(internalName);
        return original != null && preservedClasses.contains(original);
    }

    public void preserveField(String owner, String name, String desc) {
        preservedFields.add(fieldKey(owner, name, desc));
    }

    public boolean isFieldPreserved(String owner, String name, String desc) {
        if (preservedFields.contains(fieldKey(owner, name, desc))) return true;
        String mappedOwner = classMappings.get(owner);
        if (mappedOwner != null && preservedFields.contains(fieldKey(mappedOwner, name, desc))) return true;
        String originalOwner = reverseClassMappings.get(owner);
        return originalOwner != null && preservedFields.contains(fieldKey(originalOwner, name, desc));
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

    public String getMappedRecordComponent(String owner, String oldName, String desc) {
        return getMappedField(owner, oldName, desc);
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
        exportMappings(outputPath, MappingFormat.YAML);
    }

    public void exportMappings(Path outputPath, MappingFormat format) throws IOException {
        Path destination = normalizedOutput(outputPath);
        Files.writeString(destination, renderMappings(format), StandardCharsets.UTF_8);
        Logger.info("{} mapping file exported to {}", format.id(), destination);
    }

    public void exportEncryptedMappings(Path outputPath, char[] password) throws IOException {
        exportEncryptedMappings(outputPath, password, MappingFormat.YAML);
    }

    public void exportEncryptedMappings(Path outputPath, char[] password, MappingFormat format) throws IOException {
        Path destination = normalizedOutput(outputPath);
        PasswordFileCipher.encrypt(renderMappings(format).getBytes(StandardCharsets.UTF_8), destination, password);
        Logger.info("AES-256 encrypted {} mapping file exported to {}", format.id(), destination);
    }

    public String renderMappings() {
        return renderMappings(MappingFormat.YAML);
    }

    public String renderMappings(MappingFormat format) {
        return switch (format == null ? MappingFormat.YAML : format) {
            case YAML -> renderYamlMappings();
            case PROGUARD -> renderProGuardMappings();
            case TINY -> renderTinyMappings();
        };
    }

    public int totalMappings() {
        return classMappings.size() + fieldMappings.size() + methodMappings.size();
    }

    private String renderYamlMappings() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "frostfuscator-mappings");
        document.put("version", 1);
        Map<String, String> classes = new LinkedHashMap<>();
        new TreeMap<>(classMappings).forEach((oldName, newName) ->
                classes.put(oldName.replace('/', '.'), newName.replace('/', '.')));
        document.put("classes", classes);
        document.put("fields", fieldEntries().stream().map(entry -> mappingEntry(
                entry.owner(), entry.name(), entry.descriptor(), entry.mappedName())).toList());
        document.put("methods", methodEntries().stream().map(entry -> mappingEntry(
                entry.owner(), entry.name(), entry.descriptor(), entry.mappedName())).toList());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(document);
    }

    private String renderProGuardMappings() {
        StringBuilder output = new StringBuilder(256 + totalMappings() * 56);
        Set<String> owners = mappedOwners();
        List<FieldEntry> fields = fieldEntries();
        List<MethodEntry> methods = methodEntries();
        for (String owner : owners) {
            output.append(owner.replace('/', '.')).append(" -> ")
                    .append(getMappedClass(owner).replace('/', '.')).append(":\n");
            for (FieldEntry field : fields) {
                if (!field.owner().equals(owner)) continue;
                output.append("    ").append(Type.getType(field.descriptor()).getClassName())
                        .append(' ').append(field.name()).append(" -> ").append(field.mappedName()).append('\n');
            }
            for (MethodEntry method : methods) {
                if (!method.owner().equals(owner)) continue;
                Type methodType = Type.getMethodType(method.descriptor());
                output.append("    ").append(methodType.getReturnType().getClassName()).append(' ')
                        .append(method.name()).append('(');
                Type[] arguments = methodType.getArgumentTypes();
                for (int index = 0; index < arguments.length; index++) {
                    if (index > 0) output.append(',');
                    output.append(arguments[index].getClassName());
                }
                output.append(") -> ").append(method.mappedName()).append('\n');
            }
        }
        return output.toString();
    }

    private String renderTinyMappings() {
        StringBuilder output = new StringBuilder(256 + totalMappings() * 48);
        output.append("tiny\t2\t0\toriginal\tobfuscated\n");
        List<FieldEntry> fields = fieldEntries();
        List<MethodEntry> methods = methodEntries();
        for (String owner : mappedOwners()) {
            output.append("c\t").append(owner).append('\t').append(getMappedClass(owner)).append('\n');
            for (FieldEntry field : fields) {
                if (field.owner().equals(owner)) {
                    output.append("\tf\t").append(field.descriptor()).append('\t')
                            .append(field.name()).append('\t').append(field.mappedName()).append('\n');
                }
            }
            for (MethodEntry method : methods) {
                if (method.owner().equals(owner)) {
                    output.append("\tm\t").append(method.descriptor()).append('\t')
                            .append(method.name()).append('\t').append(method.mappedName()).append('\n');
                }
            }
        }
        return output.toString();
    }

    private Set<String> mappedOwners() {
        Set<String> owners = new TreeSet<>(classMappings.keySet());
        fieldEntries().forEach(entry -> owners.add(entry.owner()));
        methodEntries().forEach(entry -> owners.add(entry.owner()));
        return owners;
    }

    private List<FieldEntry> fieldEntries() {
        List<FieldEntry> entries = new ArrayList<>();
        new TreeMap<>(fieldMappings).forEach((key, mappedName) -> {
            int descriptor = key.lastIndexOf(':');
            int member = descriptor < 0 ? -1 : key.lastIndexOf('.', descriptor);
            if (member > 0) {
                entries.add(new FieldEntry(key.substring(0, member), key.substring(member + 1, descriptor),
                        key.substring(descriptor + 1), mappedName));
            }
        });
        return List.copyOf(entries);
    }

    private List<MethodEntry> methodEntries() {
        List<MethodEntry> entries = new ArrayList<>();
        new TreeMap<>(methodMappings).forEach((key, mappedName) -> {
            int descriptor = key.indexOf('(');
            int member = descriptor < 0 ? -1 : key.lastIndexOf('.', descriptor);
            if (member > 0) {
                entries.add(new MethodEntry(key.substring(0, member), key.substring(member + 1, descriptor),
                        key.substring(descriptor), mappedName));
            }
        });
        return List.copyOf(entries);
    }

    private record FieldEntry(String owner, String name, String descriptor, String mappedName) { }
    private record MethodEntry(String owner, String name, String descriptor, String mappedName) { }

    private static Map<String, String> mappingEntry(String owner, String name,
                                                     String descriptor, String mappedName) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("owner", owner.replace('/', '.'));
        entry.put("name", name);
        entry.put("descriptor", descriptor);
        entry.put("mappedName", mappedName);
        return entry;
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
