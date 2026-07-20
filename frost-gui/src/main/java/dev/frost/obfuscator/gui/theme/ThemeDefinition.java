package dev.frost.obfuscator.gui.theme;

import java.util.LinkedHashMap;
import java.util.Map;

public record ThemeDefinition(String id, String displayName, Map<String, String> tokens, boolean builtIn) {
    public ThemeDefinition {
        tokens = Map.copyOf(new LinkedHashMap<>(tokens));
    }

    public String token(String name) {
        return tokens.getOrDefault(name, "#000000");
    }
}
