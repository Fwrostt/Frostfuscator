package dev.frost.obfuscator.jni.core.selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-facing native selection settings.
 */
public record NativeSelectionConfig(
        Set<String> includePackages,
        Set<String> includeClasses,
        Set<String> includeMethods,
        Set<String> annotationDescriptors
) {
    private static final Pattern ARRAY_PROPERTY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_VALUE = Pattern.compile("\"([^\"]+)\"");

    public NativeSelectionConfig {
        includePackages = normalize(Objects.requireNonNull(includePackages, "includePackages"));
        includeClasses = normalize(Objects.requireNonNull(includeClasses, "includeClasses"));
        includeMethods = normalize(Objects.requireNonNull(includeMethods, "includeMethods"));
        annotationDescriptors = normalizeAnnotations(Objects.requireNonNull(annotationDescriptors, "annotationDescriptors"));
    }

    public static NativeSelectionConfig includeEverything() {
        return new NativeSelectionConfig(Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static NativeSelectionConfig fromJson(Path path) throws IOException {
        return fromJson(Files.readString(path));
    }

    public static NativeSelectionConfig fromJson(String json) {
        Set<String> packages = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        Set<String> annotations = new LinkedHashSet<>();

        Matcher matcher = ARRAY_PROPERTY.matcher(json);
        while (matcher.find()) {
            List<String> values = readStringArray(matcher.group(2));
            switch (matcher.group(1)) {
                case "includePackages" -> packages.addAll(values);
                case "includeClasses" -> classes.addAll(values);
                case "includeMethods" -> methods.addAll(values);
                case "annotationFilters", "includeAnnotations" -> annotations.addAll(values);
                default -> {
                    // Unknown config keys are ignored for forward compatibility.
                }
            }
        }

        return new NativeSelectionConfig(packages, classes, methods, annotations);
    }

    public boolean isEmpty() {
        return includePackages.isEmpty()
                && includeClasses.isEmpty()
                && includeMethods.isEmpty()
                && annotationDescriptors.isEmpty();
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.replace('.', '/'));
            }
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeAnnotations(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("L") && trimmed.endsWith(";")) {
                normalized.add(trimmed);
            } else {
                normalized.add("L" + trimmed.replace('.', '/') + ";");
            }
        }
        return Set.copyOf(normalized);
    }

    private static List<String> readStringArray(String arrayBody) {
        return STRING_VALUE.matcher(arrayBody)
                .results()
                .map(match -> match.group(1))
                .toList();
    }
}


