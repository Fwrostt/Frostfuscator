package dev.frost.obfuscator.gui.protection;

import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.config.TransformerProfiles;
import dev.frost.obfuscator.gui.state.ProjectState;
import dev.frost.obfuscator.transformer.TransformerConfig;

import java.util.List;

public final class ProtectionProfiles {
    public record Definition(String name, String coreProfile, String description, String compatibility,
                             String strength, String output, String overhead) {}

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("Development", "basic", "Fast builds and safe diagnostics while developing.",
                    "Excellent", "Light", "Small", "Minimal"),
            new Definition("Balanced", "balanced", "Recommended protection for most desktop and server applications.",
                    "High", "Balanced", "Moderate", "Low"),
            new Definition("Strong", "strong", "Deeper indirection and control-flow protection for releases.",
                    "Good", "Strong", "Higher", "Moderate"),
            new Definition("Maximum", "maximum", "All compatible high-cost protections for hardened releases.",
                    "Project dependent", "Maximum", "Largest", "Highest"),
            new Definition("Custom", "", "A profile created by editing individual transformer settings.",
                    "Varies", "Custom", "Varies", "Varies")
    );

    private ProtectionProfiles() {}

    public static List<Definition> definitions() { return DEFINITIONS; }

    public static void apply(ProjectState state, String name) {
        Definition definition = DEFINITIONS.stream().filter(item -> item.name().equalsIgnoreCase(name))
                .findFirst().orElse(DEFINITIONS.get(1));
        if (!definition.coreProfile().isBlank()) {
            ObfuscationConfig config = state.configuration();
            TransformerProfiles.apply(config, definition.coreProfile());
        }
        state.profileProperty().set(definition.name());
        state.touch();
    }

    public static TransformerConfig recommended(String profile, String transformerName) {
        ObfuscationConfig config = new ObfuscationConfig();
        String core = definitions().stream()
                .filter(item -> item.name().equalsIgnoreCase(profile))
                .map(Definition::coreProfile)
                .filter(value -> !value.isBlank())
                .findFirst().orElse("balanced");
        TransformerProfiles.apply(config, core);
        return config.getTransformers().getOrDefault(transformerName, new TransformerConfig());
    }
}
