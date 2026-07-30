package dev.frost.obfuscator.gui.theme;

import dev.frost.obfuscator.gui.state.PreferencesStore;
import javafx.beans.property.*;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.*;

public final class ThemeManager {
    public enum Density { COMPACT, COMFORTABLE, SPACIOUS }

    private final PreferencesStore preferences;
    private final List<ThemeDefinition> builtIns;
    private final List<ThemeDefinition> customThemes = new ArrayList<>();
    private final ObjectProperty<ThemeDefinition> activeTheme = new SimpleObjectProperty<>();
    private final ObjectProperty<Density> density = new SimpleObjectProperty<>(Density.COMFORTABLE);
    private final DoubleProperty fontScale = new SimpleDoubleProperty(1.0);
    private final BooleanProperty reducedMotion = new SimpleBooleanProperty(false);
    private final StringProperty accent = new SimpleStringProperty("#7DD3FC");
    private Parent root;

    public ThemeManager(PreferencesStore preferences) {
        this.preferences = preferences;
        this.builtIns = createBuiltIns();
        loadCustomThemes();
        activeTheme.set(find(System.getProperty("frost.gui.theme",
                preferences.get("theme.id", "oled-black"))));
        density.set(parseDensity(preferences.get("ui.density", "COMFORTABLE")));
        fontScale.set(clamp(preferences.getDouble("ui.fontScale", 1.0), 0.85, 1.35));
        reducedMotion.set(preferences.getBoolean("ui.reducedMotion", false));
        accent.set(preferences.get("theme.accent", activeTheme.get().token("accent")));

        activeTheme.addListener((obs, old, value) -> {
            accent.set(value.token("accent"));
            persist();
            apply();
        });
        density.addListener((obs, old, value) -> { persist(); apply(); });
        fontScale.addListener((obs, old, value) -> { persist(); apply(); });
        reducedMotion.addListener((obs, old, value) -> persist());
        accent.addListener((obs, old, value) -> { persist(); apply(); });
    }

    public void attach(Scene scene, Parent root) {
        this.root = root;
        apply();
    }

    public List<ThemeDefinition> builtIns() { return builtIns; }
    public List<ThemeDefinition> availableThemes() {
        List<ThemeDefinition> themes = new ArrayList<>(builtIns);
        themes.addAll(customThemes);
        return List.copyOf(themes);
    }
    public ObjectProperty<ThemeDefinition> activeThemeProperty() { return activeTheme; }
    public ThemeDefinition activeTheme() { return activeTheme.get(); }
    public ObjectProperty<Density> densityProperty() { return density; }
    public DoubleProperty fontScaleProperty() { return fontScale; }
    public BooleanProperty reducedMotionProperty() { return reducedMotion; }
    public StringProperty accentProperty() { return accent; }

    public void select(String id) { activeTheme.set(find(id)); }

    public ThemeDefinition saveCustom(String name, Map<String, String> tokens) {
        String id = "custom-" + name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        Map<String, String> complete = withSoftTokens(tokens);
        ThemeDefinition definition = new ThemeDefinition(id, name.trim(), complete, false);
        preferences.put("customTheme." + id, encode(definition));
        customThemes.removeIf(theme -> theme.id().equals(id));
        customThemes.add(definition);
        activeTheme.set(definition);
        return definition;
    }

    private void apply() {
        if (root == null || activeTheme.get() == null) return;
        ThemeDefinition theme = activeTheme.get();
        double densityFactor = switch (density.get()) {
            case COMPACT -> 0.86;
            case SPACIOUS -> 1.16;
            default -> 1.0;
        };
        StringBuilder style = new StringBuilder();
        theme.tokens().forEach((key, value) -> style.append("-fx-").append(key).append(":").append(value).append(";"));
        style.append("-fx-accent-token:").append(accent.get()).append(';');
        style.append("-fx-accent-soft:")
                .append(blend(accent.get(), theme.token("bg"), 0.17)).append(';');
        style.append("-fx-density:").append(densityFactor).append(';');
        style.append("-fx-font-scale:").append(fontScale.get()).append(';');
        style.append("-fx-font-size:").append(13.5d * fontScale.get()).append("px;");
        root.setStyle(style.toString());
        root.getStyleClass().removeAll("density-compact", "density-comfortable", "density-spacious");
        root.getStyleClass().add("density-" + density.get().name().toLowerCase(Locale.ROOT));
    }

    private void persist() {
        preferences.put("theme.id", activeTheme.get().id());
        preferences.put("theme.accent", accent.get());
        preferences.put("ui.density", density.get().name());
        preferences.putDouble("ui.fontScale", fontScale.get());
        preferences.putBoolean("ui.reducedMotion", reducedMotion.get());
    }

    private ThemeDefinition find(String id) {
        for (ThemeDefinition theme : builtIns) if (theme.id().equals(id)) return theme;
        for (ThemeDefinition theme : customThemes) if (theme.id().equals(id)) return theme;
        String custom = preferences.get("customTheme." + id, "");
        return custom.isBlank() ? builtIns.getFirst() : decode(custom);
    }

    private void loadCustomThemes() {
        for (String key : preferences.keysWithPrefix("customTheme.")) {
            String raw = preferences.get(key, "");
            if (!raw.isBlank()) {
                try {
                    customThemes.add(decode(raw));
                } catch (RuntimeException ignored) {
                    // Ignore one malformed custom theme without hiding all other themes.
                }
            }
        }
    }

    private static Density parseDensity(String value) {
        try { return Density.valueOf(value); } catch (Exception ignored) { return Density.COMFORTABLE; }
    }

    private static String encode(ThemeDefinition theme) {
        StringJoiner joiner = new StringJoiner(";");
        joiner.add(Base64.getEncoder().encodeToString(theme.displayName().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        theme.tokens().forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString();
    }

    private static ThemeDefinition decode(String raw) {
        String[] parts = raw.split(";");
        String name = new String(Base64.getDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int split = parts[i].indexOf('=');
            if (split > 0) tokens.put(parts[i].substring(0, split), parts[i].substring(split + 1));
        }
        return new ThemeDefinition("custom-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"),
                name, withSoftTokens(tokens), false);
    }

    private static List<ThemeDefinition> createBuiltIns() {
        return List.of(
                theme("oled-black", "OLED Black", "#000000", "#080808", "#121212", "#2A2A2A",
                        "#E4E4E7", "#A1A1AA", "#7DD3FC"),
                theme("frost", "Frost", "#F3F8FB", "#FFFFFF", "#E8F0F5", "#C5D3DC",
                        "#172129", "#52626D", "#397EA8"),
                theme("midnight-blue", "Midnight Blue", "#050A12", "#08111D", "#0E1A29", "#1E3045",
                        "#DFE8F2", "#93A5B7", "#6CB6E8"),
                theme("graphite", "Graphite", "#0E0E0F", "#151516", "#1E1E20", "#303034",
                        "#E5E5E7", "#A3A3AA", "#93B8D0"),
                theme("light", "Light", "#F5F5F6", "#FFFFFF", "#ECECEF", "#D1D1D6",
                        "#1C1C1F", "#5E5E66", "#347EA8"),
                theme("high-contrast", "High Contrast", "#000000", "#000000", "#101010", "#F5F5F5",
                        "#FFFFFF", "#EEEEEE", "#55D6FF"),
                theme("arctic-dusk", "Arctic Dusk", "#10151C", "#171E27", "#202A36", "#354355",
                        "#E7EDF4", "#AAB8C7", "#72B7E8"),
                theme("deep-ocean", "Deep Ocean", "#061415", "#0B1D1E", "#122829", "#284344",
                        "#E0F0EF", "#9DB8B6", "#4CC9B0"),
                theme("mulberry", "Mulberry", "#170D16", "#21131F", "#2D1B2A", "#493247",
                        "#F2E6EF", "#C1A3B8", "#D986B8"),
                theme("ember", "Ember", "#17100D", "#211713", "#2D201A", "#4A362C",
                        "#F2E9E4", "#C0AAA0", "#E68A5C"),
                theme("pine", "Pine", "#0C1511", "#121E19", "#1A2A23", "#30473C",
                        "#E4EFE9", "#A5B9AE", "#62C28D"),
                theme("cobalt", "Cobalt", "#0B1020", "#11182B", "#19233A", "#304064",
                        "#E6EBF7", "#A8B2CC", "#6E9BFF"),
                theme("studio-light", "Studio Light", "#F1F2F4", "#FFFFFF", "#E6E8EC", "#C4C8D0",
                        "#1C2028", "#596273", "#5A67D8"),
                theme("sage-light", "Sage Light", "#EEF3F0", "#FAFCFB", "#E1EAE5", "#BFCFC6",
                        "#17231D", "#50655A", "#2F7D5B")
        );
    }

    private static ThemeDefinition theme(String id, String name, String bg, String surface,
                                         String raised, String border, String text, String muted, String accent) {
        int channel = Integer.parseInt(bg.substring(1, 3), 16);
        boolean light = channel > 160;
        String success = light ? "#167A55" : "#45C99A";
        String warning = light ? "#8A5A00" : "#E3A934";
        String error = light ? "#B12E3A" : "#E36A6F";
        String info = light ? "#246B9B" : "#6FA7DD";
        return new ThemeDefinition(id, name, withSoftTokens(Map.ofEntries(
                Map.entry("bg", bg), Map.entry("surface", surface), Map.entry("surface-raised", raised),
                Map.entry("border", border), Map.entry("text", text), Map.entry("text-muted", muted),
                Map.entry("accent", accent), Map.entry("success", success),
                Map.entry("warning", warning), Map.entry("error", error), Map.entry("info", info)
        )), true);
    }

    private static Map<String, String> withSoftTokens(Map<String, String> source) {
        Map<String, String> tokens = new LinkedHashMap<>(source);
        String background = tokens.getOrDefault("bg", "#000000");
        tokens.putIfAbsent("accent-soft", blend(tokens.getOrDefault("accent", "#7DD3FC"), background, 0.17));
        tokens.putIfAbsent("success-soft", blend(tokens.getOrDefault("success", "#45C99A"), background, 0.14));
        tokens.putIfAbsent("warning-soft", blend(tokens.getOrDefault("warning", "#E3A934"), background, 0.14));
        tokens.putIfAbsent("error-soft", blend(tokens.getOrDefault("error", "#E36A6F"), background, 0.14));
        tokens.putIfAbsent("info-soft", blend(tokens.getOrDefault("info", "#6FA7DD"), background, 0.14));
        return Map.copyOf(tokens);
    }

    private static String blend(String foreground, String background, double amount) {
        try {
            int fg = Integer.parseInt(foreground.substring(1), 16);
            int bg = Integer.parseInt(background.substring(1), 16);
            int red = blendChannel((fg >> 16) & 0xff, (bg >> 16) & 0xff, amount);
            int green = blendChannel((fg >> 8) & 0xff, (bg >> 8) & 0xff, amount);
            int blue = blendChannel(fg & 0xff, bg & 0xff, amount);
            return String.format("#%02X%02X%02X", red, green, blue);
        } catch (RuntimeException ignored) {
            return background;
        }
    }

    private static int blendChannel(int foreground, int background, double amount) {
        return (int) Math.round(background + (foreground - background) * amount);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
