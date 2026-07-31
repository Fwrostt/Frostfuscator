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

    public boolean isLight() {
        String background = token("bg");
        try {
            int red = Integer.parseInt(background.substring(1, 3), 16);
            int green = Integer.parseInt(background.substring(3, 5), 16);
            int blue = Integer.parseInt(background.substring(5, 7), 16);
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue >= 160;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
