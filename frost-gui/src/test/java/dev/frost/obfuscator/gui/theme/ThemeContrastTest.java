package dev.frost.obfuscator.gui.theme;

import dev.frost.obfuscator.gui.state.PreferencesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ThemeContrastTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyBuiltInThemeHasDistinctAccessibleTextRamps() {
        try (PreferencesStore preferences = new PreferencesStore(temporaryDirectory)) {
            ThemeManager manager = new ThemeManager(preferences);
            HashSet<String> signatures = new HashSet<>();
            for (ThemeDefinition theme : manager.builtIns()) {
                assertTrue(contrast(theme.token("text"), theme.token("bg")) >= 4.5,
                        theme.displayName() + " primary text");
                assertTrue(contrast(theme.token("text-muted"), theme.token("surface")) >= 4.5,
                        theme.displayName() + " supporting text");
                assertTrue(signatures.add(theme.token("bg") + theme.token("surface") + theme.token("accent")),
                        theme.displayName() + " duplicates another theme");
            }
            ThemeDefinition oled = manager.builtIns().getFirst();
            assertEquals("#000000", oled.token("bg"));
            assertEquals("#080808", oled.token("surface"));
        }
    }

    private static double contrast(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        double red = channel(hex.substring(1, 3));
        double green = channel(hex.substring(3, 5));
        double blue = channel(hex.substring(5, 7));
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(String value) {
        double channel = Integer.parseInt(value, 16) / 255d;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
