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
            assertEquals(22, manager.builtIns().size());
            HashSet<String> signatures = new HashSet<>();
            HashSet<String> ids = new HashSet<>();
            HashSet<String> names = new HashSet<>();
            for (ThemeDefinition theme : manager.builtIns()) {
                assertTrue(ids.add(theme.id()), theme.id() + " duplicates another id");
                assertTrue(names.add(theme.displayName()), theme.displayName() + " duplicates another name");
                assertTrue(contrast(theme.token("text"), theme.token("bg")) >= 4.5,
                        theme.displayName() + " primary text");
                assertTrue(contrast(theme.token("text-muted"), theme.token("surface")) >= 4.5,
                        theme.displayName() + " supporting text");
                assertTrue(contrast(theme.token("accent"), theme.token("bg")) >= 3.0,
                        theme.displayName() + " accent against background");
                assertTrue(signatures.add(theme.token("bg") + theme.token("surface") + theme.token("accent")),
                        theme.displayName() + " duplicates another theme");
            }
            ThemeDefinition oled = manager.builtIns().getFirst();
            assertEquals("#000000", oled.token("bg"));
            assertEquals("#080808", oled.token("surface"));
        }
    }

    @Test
    void namesAndPalettesExpressDistinctColorIdentities() {
        try (PreferencesStore preferences = new PreferencesStore(temporaryDirectory)) {
            ThemeManager manager = new ThemeManager(preferences);
            ThemeDefinition ocean = theme(manager, "deep-ocean");
            assertEquals("Deep Ocean", ocean.displayName());
            assertTrue(blue(ocean.token("bg")) > green(ocean.token("bg")));
            assertTrue(blue(ocean.token("accent")) - green(ocean.token("accent")) >= 70,
                    "Deep Ocean accent must read blue rather than teal or green");

            ThemeDefinition studio = theme(manager, "studio-light");
            ThemeDefinition light = theme(manager, "light");
            assertEquals("Lavender Studio", studio.displayName());
            assertTrue(colorDistance(studio.token("bg"), light.token("bg")) >= 20,
                    "Lavender Studio must not reuse the neutral Light background ramp");
            assertHue(studio, 250, 285);

            assertHue(theme(manager, "amethyst-night"), 260, 285);
            assertHue(theme(manager, "crimson-noir"), 345, 360);
            assertHue(theme(manager, "amber-terminal"), 35, 55);
            assertHue(theme(manager, "moss-circuit"), 80, 105);
            assertHue(theme(manager, "rose-quartz"), 325, 345);
            assertHue(theme(manager, "canary-light"), 65, 80);
            assertHue(theme(manager, "coral-bloom"), 5, 20);
            assertHue(theme(manager, "aqua-day"), 175, 195);

            long lightThemes = manager.builtIns().stream().filter(ThemeDefinition::isLight).count();
            assertEquals(8, lightThemes);
            assertEquals(14, manager.builtIns().size() - lightThemes);
        }
    }

    private static ThemeDefinition theme(ThemeManager manager, String id) {
        return manager.builtIns().stream().filter(theme -> theme.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertHue(ThemeDefinition theme, double minimum, double maximum) {
        double hue = hue(theme.token("accent"));
        assertTrue(hue >= minimum && hue <= maximum,
                theme.displayName() + " accent hue " + hue + " does not match its name");
    }

    private static double hue(String hex) {
        double red = red(hex) / 255d;
        double green = green(hex) / 255d;
        double blue = blue(hex) / 255d;
        double maximum = Math.max(red, Math.max(green, blue));
        double minimum = Math.min(red, Math.min(green, blue));
        double delta = maximum - minimum;
        if (delta == 0) return 0;
        double hue;
        if (maximum == red) hue = 60 * (((green - blue) / delta) % 6);
        else if (maximum == green) hue = 60 * (((blue - red) / delta) + 2);
        else hue = 60 * (((red - green) / delta) + 4);
        return hue < 0 ? hue + 360 : hue;
    }

    private static double colorDistance(String first, String second) {
        int red = red(first) - red(second);
        int green = green(first) - green(second);
        int blue = blue(first) - blue(second);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static int red(String hex) { return Integer.parseInt(hex.substring(1, 3), 16); }
    private static int green(String hex) { return Integer.parseInt(hex.substring(3, 5), 16); }
    private static int blue(String hex) { return Integer.parseInt(hex.substring(5, 7), 16); }

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
