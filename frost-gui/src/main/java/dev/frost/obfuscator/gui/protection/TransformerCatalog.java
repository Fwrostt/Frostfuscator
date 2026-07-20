package dev.frost.obfuscator.gui.protection;

import dev.frost.obfuscator.transformer.TransformerRegistry;

import java.util.*;

public final class TransformerCatalog {
    public enum Category {
        RENAMING("Renaming", "Identifiers and symbol visibility."),
        CONSTANTS("Constants", "Strings, numbers, parameters, and literal values."),
        CONTROL_FLOW("Control Flow", "Execution paths, predicates, and stack behavior."),
        INDIRECTION("Indirection", "Calls, references, class loading, and virtualization."),
        METADATA("Metadata", "Debug data, resources, reports, and archive metadata."),
        RUNTIME_GUARDS("Runtime Guards", "Tamper, debugger, integrity, and license defenses."),
        NATIVE("Native Protection", "FrostJNI native-code conversion and packaging."),
        OPTIMIZATION("Optimization", "Output cleanup, inlining, shrinking, and compression."),
        FUNSIES("Funsies", "Unconventional passes intended for deliberate, playful use.");

        private final String display;
        private final String description;

        Category(String display, String description) {
            this.display = display;
            this.description = description;
        }

        public String display() { return display; }
        public String description() { return description; }
    }

    public record Descriptor(String name, String title, Category category, String description,
                             String compatibility, String impact, boolean experimental) {}

    private final List<Descriptor> descriptors;

    public TransformerCatalog() {
        List<Descriptor> all = new ArrayList<>(TransformerRegistry.getAllNames().stream().map(this::describe).toList());
        all.add(new Descriptor("frostjni", "FrostJNI", Category.NATIVE,
                "Converts selected Java methods into native code and packages platform-specific libraries.",
                "Native toolchain required", "High", false));
        descriptors = all.stream().sorted(Comparator.comparing((Descriptor value) -> value.category().ordinal())
                .thenComparing(Descriptor::title)).toList();
    }

    public List<Descriptor> all() { return descriptors; }
    public List<Descriptor> category(Category category) {
        return descriptors.stream().filter(value -> value.category() == category).toList();
    }

    public Descriptor find(String name) {
        return descriptors.stream().filter(value -> value.name().equals(name)).findFirst()
                .orElseGet(() -> describe(name));
    }

    private Descriptor describe(String name) {
        Category category = category(name);
        boolean experimental = category == Category.FUNSIES;
        String compatibility = switch (name) {
            case "virtualization", "classloader-encryption", "fake-application", "chinese-mode" -> "Project-specific";
            case "flow-obfuscation", "invoke-dynamic", "reflection-hiding", "resource-steganography" -> "Review recommended";
            default -> "Generally compatible";
        };
        String impact = switch (name) {
            case "virtualization", "flow-obfuscation", "fake-classes", "junk-code" -> "High";
            case "string-encryption", "invoke-dynamic", "reference-hiding", "resource-encryption" -> "Medium";
            default -> "Low";
        };
        return new Descriptor(name, title(name), category, description(name), compatibility, impact, experimental);
    }

    private static Category category(String name) {
        if (name.equals("frostjni")) return Category.NATIVE;
        String packageName = Optional.ofNullable(TransformerRegistry.getByName(name))
                .map(value -> value.getClass().getPackageName())
                .orElse("");
        if (packageName.endsWith(".funsies")) return Category.FUNSIES;
        if (packageName.endsWith(".rename") || name.contains("rename")) return Category.RENAMING;
        if (packageName.endsWith(".encryption")
                || name.contains("string") || name.contains("number") || name.contains("parameter")) {
            return Category.CONSTANTS;
        }
        if (packageName.endsWith(".flow")
                || name.startsWith("flow") || name.contains("boolean") || name.contains("stack")) {
            return Category.CONTROL_FLOW;
        }
        if (packageName.endsWith(".indirection") || packageName.endsWith(".virtualization")
                || name.contains("dynamic") || name.contains("reference") || name.contains("reflection")
                || name.contains("classloader") || name.contains("virtualization")) {
            return Category.INDIRECTION;
        }
        if (packageName.endsWith(".optimization")
                || name.contains("optimizer") || name.contains("elimination") || name.contains("inlining")
                || name.contains("shrinker") || name.equals("resource-compression")) {
            return Category.OPTIMIZATION;
        }
        if (packageName.endsWith(".protection") || packageName.endsWith(".license")
                || name.contains("anti-") || name.contains("checksum") || name.contains("integrity")
                || name.contains("license") || name.contains("hardening")) {
            return Category.RUNTIME_GUARDS;
        }
        if (packageName.endsWith(".cleanup") || packageName.endsWith(".resources")
                || packageName.endsWith(".reporting") || packageName.endsWith(".watermark")
                || name.contains("resource") || name.contains("metadata") || name.contains("watermark")) {
            return Category.METADATA;
        }
        return Category.FUNSIES;
    }

    private static String title(String name) {
        if ("frostjni".equalsIgnoreCase(name)) return "FrostJNI";
        StringBuilder out = new StringBuilder();
        for (String part : name.split("-")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString()
                .replace("Jni", "JNI")
                .replace("Jar", "JAR")
                .replace("Debug", "Debug")
                .replace("Classloader", "ClassLoader");
    }

    private static String description(String name) {
        return switch (name) {
            case "class-rename" -> "Renames classes while respecting package and compatibility rules.";
            case "field-rename" -> "Renames eligible fields without changing program behavior.";
            case "method-rename" -> "Renames methods with hierarchy-aware safety checks.";
            case "string-encryption" -> "Encrypts string constants and reconstructs them at runtime.";
            case "flow-obfuscation" -> "Restructures method control flow using bounded opaque dispatch.";
            case "invoke-dynamic" -> "Moves eligible calls behind invokedynamic call sites.";
            case "reflection-hiding" -> "Hides selected API calls behind encrypted method handles.";
            case "virtualization" -> "Translates eligible methods to an embedded virtual instruction set.";
            case "resource-encryption" -> "Encrypts selected resources and writes a runtime index.";
            case "statistics-report" -> "Writes a machine-readable or HTML build report.";
            case "frostjni" -> "Converts selected Java methods into native code and packages platform-specific libraries.";
            default -> "Configure " + title(name).toLowerCase(Locale.ROOT) + " behavior and compatibility.";
        };
    }
}
