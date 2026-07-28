package dev.frost.obfuscator.gui.navigation;

public enum PageId {
    OVERVIEW("Overview", "fth-home"),
    INPUT("Input", "fth-download-cloud"),
    PROTECTION("Protection", "fth-shield"),
    RESOURCES("Resources", "fth-package"),
    ENCRYPTOR("Encryptor", "fth-lock"),
    BYTECODE("Bytecode", "fth-code"),
    BUILD("Build", "fth-play-circle"),
    VALIDATION("Validation", "fth-check-circle"),
    REPORTS("Analytics", "fth-bar-chart-2"),
    CONSOLE("Console", "fth-terminal"),
    PRESETS("Presets", "fth-bookmark"),
    SETTINGS("Settings", "fth-settings");

    private final String label;
    private final String iconLiteral;

    PageId(String label, String iconLiteral) {
        this.label = label;
        this.iconLiteral = iconLiteral;
    }

    public String label() { return label; }
    public String iconLiteral() { return iconLiteral; }
}
