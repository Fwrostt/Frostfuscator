package dev.frost.obfuscator.gui.export;

/**
 * Data model for export options and configuration flags.
 */
public record ExportOptions(
        boolean preservePackageStructure,
        boolean includeSyntheticsAndBridges,
        boolean includeCommentsAndWarnings,
        Format format
) {
    public enum Format {
        DIRECTORY,
        ZIP,
        SINGLE_FILE
    }

    public static ExportOptions defaults() {
        return new ExportOptions(true, false, true, Format.DIRECTORY);
    }
}
