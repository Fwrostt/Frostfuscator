package dev.frost.obfuscator.gui.viewer;

import java.time.Duration;
import java.util.List;

public record DecompileResult(
        String source,
        Duration elapsed,
        List<String> diagnostics
) {
    public DecompileResult {
        source = source == null ? "" : source;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
